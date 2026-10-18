package com.getddo.db.drawing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.db.drawing.entity.DrawResultEntity;
import com.getddo.db.drawing.entity.DrawRunEntity;
import com.getddo.db.drawing.mapper.DrawResultMapper;
import com.getddo.db.drawing.mapper.DrawSnapshotMapper;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;
import static com.getddo.db.common.util.UuidBinary.fromBytes;
import static com.getddo.db.common.util.UuidBinary.toBytes;

/** 결과는 JPA로 저장하며 다른 영역·취소 이력은 읽기 전용 SQL로 검증한다. */
@Repository
@RequiredArgsConstructor
public class DrawResultRepositoryImpl implements DrawResultRepository {
	private final DrawRunJpaRepository runs;
	private final DrawRunCandidateJpaRepository links;
	private final DrawResultJpaRepository results;
	private final DrawSnapshotMapper snapshots;
	private final DrawResultMapper mapper;
	private final JdbcTemplate jdbc;
	private final EntityManager entityManager;

	/** 이벤트 선잠금에 필요한 소속 ID만 조회한다. 트랜잭션의 시작·종료는 확정 서비스가 담당한다. */
	@Override
	public Optional<UUID> findEventId(UUID runId) {
		return jdbc.query("select event_id from draw_runs where id = ?",
				(row, index) -> fromBytes(row.getBytes("event_id")), toBytes(runId)).stream().findFirst();
	}

	/** 서비스의 이벤트 잠금 이후 실행과 불변 입력을 읽는다. 실행 종류별로 저장된 입력을 그대로 복원한다. */
	@Override
	public Optional<DrawSnapshot> lockSnapshot(UUID runId) {
		Optional<DrawRunEntity> lockedRun = runs.findForUpdate(runId);
		if (lockedRun.isEmpty()) {
			return Optional.empty();
		}
		DrawRunEntity run = lockedRun.get();
		// 재추첨 입력은 버전 2 규칙과 최초 후보 연결을 공통 확정 서비스에서 검증한다.
		if ((run.getDrawType() == DrawType.INITIAL && run.getRunNumber() != 0)
				|| (run.getDrawType() == DrawType.REPLACEMENT && run.getRunNumber() < 1)) invalid();
		return Optional.of(snapshots.toDomain(run, links.findCandidates(runId)));
	}

	/** JDBC 조인으로 후보 연결·이벤트·사용자 관계를 검증하고 스냅샷 경품의 소속을 대조한다. */
	@Override
	public void validateRelations(DrawSnapshot snapshot) {
		Map<UUID, UUID> owners = new HashMap<>();
		jdbc.query("""
			select c.id, p.user_id, p.event_id as participant_event, origin.event_id as origin_event, origin.id as origin_id
			from draw_run_candidates link join draw_candidates c on c.id = link.candidate_id
			join event_participants p on p.id = c.participant_id
			join draw_runs origin on origin.id = c.draw_run_id
			where link.draw_run_id = ?
			""", (org.springframework.jdbc.core.RowCallbackHandler) row -> collectCandidateOwner(row, snapshot, owners),
		toBytes(snapshot.runId()));
		if (owners.size() != snapshot.candidates().size()) invalid();
		for (DrawSnapshot.Candidate candidate : snapshot.candidates()) {
			if (!candidate.userId().equals(owners.get(candidate.id()))) {
				invalid();
			}
		}
		Set<UUID> prizeIds = new HashSet<>(jdbc.query("select id from event_prizes where event_id = ?",
				(row, index) -> fromBytes(row.getBytes("id")), toBytes(snapshot.eventId())));
		for (DrawSnapshotSource.Prize prize : snapshot.rules().prizes()) {
			if (!prizeIds.contains(prize.id())) {
				invalid();
			}
		}
		if (snapshot.drawType() == DrawType.REPLACEMENT) validateReplacement(snapshot);
	}

	/** 요청 시도 토큰과 저장된 소유권 토큰이 일치하는지 확인한다. 허용 실행 상태와 RUNNING의 토큰 필수 규칙은 서비스가 담당한다. */
	@Override public void validateAttempt(UUID runId, UUID attemptId) {
		DrawRunEntity run = runs.findById(runId).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (!java.util.Objects.equals(run.getAttemptId(), attemptId)) invalid();
	}

	/** 최초 조건·역대 당첨 제외·연결 취소 자리·유지 결과를 검증한다. 현재 원본으로 입력을 바꾸지 않는다. */
	private void validateReplacement(DrawSnapshot snapshot) {
		DrawRunEntity original = runs.findById(snapshot.rules().originalRunId()).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (original.getDrawType() != DrawType.INITIAL || original.getStatus() != DrawRunStatus.CONFIRMED
				|| !original.getEventId().equals(snapshot.eventId())) invalid();
		DrawSnapshot.Rules rules = snapshots.toDomain(original, List.of()).rules();
		DrawSnapshot.Rules replacement = snapshot.rules();
		if (!rules.prizes().equals(replacement.prizes()) || rules.weightingEnabled() != replacement.weightingEnabled()
				|| !java.util.Objects.equals(rules.eventType(), replacement.eventType())
				|| !java.util.Objects.equals(rules.membershipRule(), replacement.membershipRule())
				|| rules.bronzeWeight() != replacement.bronzeWeight() || rules.silverWeight() != replacement.silverWeight()
				|| rules.goldWeight() != replacement.goldWeight() || !java.util.Objects.equals(rules.platinumWeight(), replacement.platinumWeight())) invalid();
		Integer winners = jdbc.queryForObject("""
			select count(*) from draw_run_candidates link
			where link.draw_run_id = ? and exists
			(select 1 from draw_results winner join draw_runs run on run.id = winner.draw_run_id
			 where winner.candidate_id = link.candidate_id and run.event_id = ?
			 and run.status = 'CONFIRMED' and winner.result_type = 'SELECTED')
			""", Integer.class, toBytes(snapshot.runId()), toBytes(snapshot.eventId()));
		if (winners == null || winners != 0) invalid();
		Set<String> targetSlots = new HashSet<>();
		for (DrawSnapshot.Slot slot : replacement.targetSlots()) targetSlots.add(slot.prizeId() + ":" + slot.slotNumber());
		List<String> canceledSlots = jdbc.query("""
			select r.event_prize_id, r.slot_number from award_cancellations cancellation
			join draw_results r on r.id = cancellation.draw_result_id
			join draw_runs origin on origin.id = r.draw_run_id
			where cancellation.draw_run_id = ? and origin.event_id = ? and origin.status = 'CONFIRMED'
			and r.result_type = 'SELECTED'
			""", (row, index) -> fromBytes(row.getBytes("event_prize_id")) + ":" + row.getInt("slot_number"),
		toBytes(snapshot.runId()), toBytes(snapshot.eventId()));
		if (targetSlots.isEmpty() || canceledSlots.size() != targetSlots.size() || !targetSlots.equals(new HashSet<>(canceledSlots))) invalid();
		validateRetainedAwards(snapshot);
	}

	/** 서비스가 검증한 유지 결과 ID를 일괄 조회해 사용자·경품·자리와 취소 여부를 대조한다. */
	private void validateRetainedAwards(DrawSnapshot snapshot) {
		List<DrawSnapshot.RetainedAward> expected = snapshot.rules().retainedAwards();
		if (expected.isEmpty()) return;
		String placeholders = String.join(",", Collections.nCopies(expected.size(), "?"));
		List<Object> parameters = new ArrayList<>();
		parameters.add(toBytes(snapshot.eventId()));
		for (DrawSnapshot.RetainedAward retained : expected) parameters.add(toBytes(retained.resultId()));
		List<DrawSnapshot.RetainedAward> stored = jdbc.query("""
			select r.id, p.user_id, r.event_prize_id, r.slot_number
			from draw_results r join draw_runs run on run.id = r.draw_run_id
			join draw_candidates c on c.id = r.candidate_id join event_participants p on p.id = c.participant_id
			where run.event_id = ? and r.id in (%s) and run.status = 'CONFIRMED' and r.result_type = 'SELECTED'
			and not exists (select 1 from award_cancellations cancellation where cancellation.draw_result_id = r.id)
			""".formatted(placeholders), (row, index) -> new DrawSnapshot.RetainedAward(
		fromBytes(row.getBytes("id")), fromBytes(row.getBytes("user_id")),
		fromBytes(row.getBytes("event_prize_id")), row.getInt("slot_number")), parameters.toArray());
		if (!new HashSet<>(stored).equals(new HashSet<>(expected))) invalid();
	}

	/** 후보의 참여 이벤트·생성 원본 실행을 검증하고 후보와 사용자의 연결을 기록한다. */
	private static void collectCandidateOwner(ResultSet row, DrawSnapshot snapshot, Map<UUID, UUID> owners)
	throws SQLException {
		if (!snapshot.eventId().equals(fromBytes(row.getBytes("participant_event")))
				|| !snapshot.eventId().equals(fromBytes(row.getBytes("origin_event")))
				|| !(snapshot.drawType() == DrawType.INITIAL ? snapshot.runId() : snapshot.rules().originalRunId())
				.equals(fromBytes(row.getBytes("origin_id")))) {
			invalid();
		}
		owners.put(fromBytes(row.getBytes("id")), fromBytes(row.getBytes("user_id")));
	}

	/** READY 실행에 저장된 부분 결과가 있는지 조회한다. 결과를 삭제하거나 보정하지 않는다. */
	@Override
	public boolean hasResults(UUID runId) {
		return results.existsByDrawRunId(runId);
	}

	/** CONFIRMED 선정 결과에서 취소된 배정을 제외한다. 원래 결과 이력과 공개 명단은 변경하지 않는다. */
	@Override
	public List<DrawSelection> findCurrentAwards(UUID eventId) {
		return jdbc.query("""
			select r.event_prize_id, prize.prize_rank, r.slot_number, r.selection_order, r.candidate_id, p.user_id
			from draw_results r join draw_runs run on run.id = r.draw_run_id
			join draw_candidates c on c.id = r.candidate_id
			join event_participants p on p.id = c.participant_id
			join event_prizes prize on prize.id = r.event_prize_id
			where run.event_id = ? and run.status = 'CONFIRMED' and r.result_type = 'SELECTED'
			and not exists (select 1 from award_cancellations cancellation where cancellation.draw_result_id = r.id)
			""", DrawResultRepositoryImpl::toCurrentAward, toBytes(eventId));
	}

	/** 취소되지 않은 확정 당첨 행을 현재 유효 배정 모델로 변환한다. */
	private static DrawSelection toCurrentAward(ResultSet row, int index) throws SQLException {
		return new DrawSelection(fromBytes(row.getBytes("event_prize_id")), row.getInt("prize_rank"),
		row.getInt("slot_number"), row.getInt("selection_order"), fromBytes(row.getBytes("candidate_id")),
		fromBytes(row.getBytes("user_id")), DrawSelectionResultType.SELECTED);
	}

	/**
	 * JPA 결과 행을 스냅샷 기준으로 복원하고 등수·자리 순서로 반환한다. 난수는 사용하지 않는다.
	 * 전체 결과 반환 계약에 따라 목록을 구성하며, 현재 경품 등수로 DB 정렬하면 확정 당시 순서가 달라질 수 있다.
	 * 조회한 행 수만큼 용량을 확보하고 반환 목록 자체를 정렬하여 별도 정렬 목록을 만들지 않는다.
	 */
	@Override
	public DrawConfirmation findConfirmed(DrawSnapshot snapshot) {
		DrawRunEntity run = runs.findById(snapshot.runId()).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (run.getStatus() != DrawRunStatus.CONFIRMED || run.getConfirmedAt() == null) invalid();
		List<DrawResultEntity> storedEntities = results.findByDrawRunId(snapshot.runId());
		List<DrawResult> stored = new ArrayList<>(storedEntities.size());
		for (DrawResultEntity result : storedEntities) {
			stored.add(mapper.toDomain(result, snapshot));
		}
		stored.sort(Comparator.comparingInt((DrawResult result) -> result.getSelection().getRank())
				.thenComparingInt(result -> result.getSelection().getSlotNumber()));
		return new DrawConfirmation(run.getId(), run.getEventId(), run.getConfirmedAt(), stored);
	}

	/** 서비스 트랜잭션 안에서 결과와 상태를 flush한 뒤 재조회한다. 불변 결과는 detach하여 DB 시각 정밀도를 반영한다. */
	@Override
	public DrawConfirmation saveConfirmed(DrawSnapshot snapshot, List<DrawSelection> selections, Instant confirmedAt) {
		DrawRunEntity run = runs.findById(snapshot.runId()).orElseThrow(() -> new BusinessException(INVALID_RUN));
		List<DrawResultEntity> entities = new ArrayList<>();
		for (DrawSelection selection : selections) {
			entities.add(new DrawResultEntity(run, selection));
		}
		List<DrawResultEntity> saved = results.saveAllAndFlush(entities);
		run.confirm(confirmedAt);
		runs.flush();
		// 첫 응답도 실제 DATETIME(6) 값으로 반환한다.
		entityManager.refresh(run);
		// 불변 엔티티의 INSERT 잠금 모드로 refresh하지 않고 실제 저장값을 다시 조회한다.
		for (DrawResultEntity result : saved) {
			entityManager.detach(result);
		}
		return findConfirmed(snapshot);
	}

	/** 실행·스냅샷 관계의 불일치를 도메인 오류로 보고하여 상위 서비스가 전체 작업을 롤백하게 한다. */
	private static void invalid() {
		throw new BusinessException(INVALID_RUN);
	}
}
