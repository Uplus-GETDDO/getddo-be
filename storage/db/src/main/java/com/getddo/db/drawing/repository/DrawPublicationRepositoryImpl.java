package com.getddo.db.drawing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawResultConfirmation;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.db.drawing.entity.DrawPublicationEntity;
import com.getddo.db.drawing.entity.DrawPublicationExecutionEntity;
import com.getddo.db.drawing.entity.DrawPublicationResultEntity;
import com.getddo.db.drawing.entity.DrawResultConfirmationEntity;

import static com.getddo.core.drawing.exception.DrawPublicationErrorCode.INVALID_PUBLICATION;
import static com.getddo.db.common.util.UuidBinary.fromBytes;
import static com.getddo.db.common.util.UuidBinary.toBytes;

/** 공개 전용 JPA 저장. 선정·취소·재추첨 등록의 공용 파일을 변경하지 않는다. */
@Repository
@RequiredArgsConstructor
public class DrawPublicationRepositoryImpl implements DrawPublicationRepository {
	private final DrawPublicationJpaRepository publications;
	private final DrawPublicationResultJpaRepository entries;
	private final EntityManager entityManager;
	private final JdbcTemplate jdbc;
	private final DrawResultConfirmationJpaRepository confirmations;
	private final DrawPublicationExecutionJpaRepository executionEvidence;

	/** 취소 근거는 실행 등록 시 확정된 award_cancellations의 불변 필드를 통해 조회한다. */
	@Override
	public void saveExecutionEvidence(UUID publicationId, UUID eventId, List<DrawPublication.Execution> executions) {
		if (executions.isEmpty()) return;
		executionEvidence.saveAllAndFlush(executions.stream()
				.map(value -> new DrawPublicationExecutionEntity(publicationId, eventId, value)).toList());
	}

	/** 고정된 버전 연결과 수정하지 않는 확인·취소 근거만 읽는다. */
	@Override
	public Map<UUID, List<DrawPublication.Execution>> findExecutionEvidence(Set<UUID> publicationIds) {
		if (publicationIds.isEmpty()) return Map.of();
		String placeholders = String.join(",", Collections.nCopies(publicationIds.size(), "?"));
		String sql = """
			select link.publication_id, link.draw_run_id, checked.id confirmation_id, checked.confirmed_by, checked.confirmed_at
			from draw_publication_executions link
			join draw_result_confirmations checked on checked.id = link.confirmation_id and checked.draw_run_id = link.draw_run_id
			join draw_runs run on run.id = link.draw_run_id
			where link.publication_id in (
			""" + placeholders + ") order by link.publication_id, run.run_number";
		List<EvidenceRow> rows = jdbc.query(sql, (row, index) -> new EvidenceRow(fromBytes(row.getBytes("publication_id")),

				fromBytes(row.getBytes("draw_run_id")), fromBytes(row.getBytes("confirmation_id")),
				fromBytes(row.getBytes("confirmed_by")), instant(row, "confirmed_at")),
				publicationIds.stream().map(id -> toBytes(id)).toArray());
		Set<UUID> runIds = new HashSet<>();
		for (EvidenceRow row : rows) runIds.add(row.runId());
		Map<UUID, List<DrawPublication.Cancellation>> causes = new HashMap<>();
		if (!runIds.isEmpty()) {
			String cancellationSql = "select id, draw_result_id, draw_run_id, reason from award_cancellations where draw_run_id in ("
			+ String.join(",", Collections.nCopies(runIds.size(), "?")) + ") order by canceled_at, id";
			jdbc.query(cancellationSql, (RowCallbackHandler) row -> {
						UUID runId = fromBytes(row.getBytes("draw_run_id"));
						causes.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(new DrawPublication.Cancellation(
						fromBytes(row.getBytes("id")), fromBytes(row.getBytes("draw_result_id")), runId, row.getString("reason")));
					}, runIds.stream().map(id -> toBytes(id)).toArray());
		}
		Map<UUID, List<DrawPublication.Execution>> loaded = new HashMap<>();
		for (EvidenceRow row : rows) loaded.computeIfAbsent(row.publicationId(), ignored -> new ArrayList<>()).add(
				new DrawPublication.Execution(row.runId(), row.confirmationId(), row.confirmedBy(), row.confirmedAt(),
				causes.getOrDefault(row.runId(), List.of())));

		return immutableCopy(loaded);
	}

	/** 반환 맵과 각 값 목록을 복사해 원본 조회 자료구조의 변경이 결과에 전파되지 않게 한다. */
	private static <T> Map<UUID, List<T>> immutableCopy(Map<UUID, List<T>> loaded) {
		Map<UUID, List<T>> immutable = new HashMap<>();
		loaded.forEach((id, values) -> immutable.put(id, List.copyOf(values)));
		return Map.copyOf(immutable);
	}

	private record EvidenceRow(UUID publicationId, UUID runId, UUID confirmationId,
			UUID confirmedBy, Instant confirmedAt) {
	}

	/** 이벤트의 가장 높은 버전인 실제 공개 기록을 조회한다. */
	@Override
	public Optional<DrawPublication> findLatest(UUID eventId) {
		return publications.findFirstByEventIdOrderByRevisionDesc(eventId).map(this::toDomain);
	}

	/** 이벤트의 실제 공개 이력을 조회한다. 전체 조회는 버전 오름차순, 페이지 조회는 내림차순이다. */
	@Override
	public List<DrawPublication> findHistory(UUID eventId) {
		return publications.findByEventIdOrderByRevisionAsc(eventId).stream().map(this::toDomain).toList();
	}

	/** 이벤트의 실제 공개 이력을 조회한다. 전체 조회는 버전 오름차순, 페이지 조회는 내림차순이다. */
	@Override
	public PageResult<DrawPublication> findHistory(UUID eventId, PageQuery query) {
		long total = publications.countByEventId(eventId);
		if (query.offset() >= total) return new PageResult<>(List.of(), query.getPage(), query.getSize(), total);
		List<DrawPublicationEntity> stored = publications.findByEventIdOrderByRevisionDesc(eventId,
				PageRequest.of(query.getPage() - 1, query.getSize()));
		Set<UUID> ids = new HashSet<>();
		for (DrawPublicationEntity publication : stored) ids.add(publication.getId());
		Map<UUID, List<DrawPublication.Entry>> included = new HashMap<>();
		if (!ids.isEmpty()) {
			for (DrawPublicationResultEntity entry : entries.findByIdPublicationIdIn(ids)) {
				included.computeIfAbsent(entry.getId().getPublicationId(), ignored -> new ArrayList<>()).add(entry.toDomain());
			}
		}
		included.values().forEach(rows -> rows.sort(Comparator.comparing(DrawPublication.Entry::getResultId)));
		List<DrawPublication> page = stored.stream().map(publication -> publication.toDomain(
				included.getOrDefault(publication.getId(), List.of()))).toList();
		return new PageResult<>(page, query.getPage(), query.getSize(), total);
	}

	/** 지정 버전보다 작은 버전 중 가장 최근 실제 공개 기록을 조회한다. */
	@Override
	public Optional<DrawPublication> findPrevious(UUID eventId, int revision) {
		return publications.findFirstByEventIdAndRevisionLessThanOrderByRevisionDesc(eventId, revision)
				.map(this::toDomain);
	}

	/** 이벤트 식별자의 저장 여부를 확인한다. */
	@Override
	public boolean eventExists(UUID eventId) {
		Integer exists = jdbc.queryForObject("select exists(select 1 from events where id = ?)",
				Integer.class, toBytes(eventId));
		return exists != null && exists == 1;
	}

	/** 결과가 현재 취소됐는지와 무관하게 과거 공개 포함 기록의 존재를 확인한다. */
	@Override
	public boolean wasPublished(UUID resultId) {
		return entries.existsByIdResultId(resultId);
	}

	/** 실제 포함 결과만 조인한다. 취소 여부·현재 유효 배정을 과거 공개 여부로 해석하지 않는다. */
	@Override
	public List<DrawPublication.Slot> findPublishedSlots(UUID publicationId) {
		return jdbc.query("""
			select r.event_prize_id, r.slot_number, r.id, participant.user_id
			from draw_publication_results published join draw_results r on r.id = published.draw_result_id
			join draw_candidates candidate on candidate.id = r.candidate_id
			join event_participants participant on participant.id = candidate.participant_id
			where published.publication_id = ?
			order by r.event_prize_id, r.slot_number
			""", (row, index) -> new DrawPublication.Slot(fromBytes(row.getBytes("event_prize_id")),
		row.getInt("slot_number"), fromBytes(row.getBytes("id")), fromBytes(row.getBytes("user_id"))),
		toBytes(publicationId));
	}

	/** 페이지의 포함 결과와 경계 이전 버전을 한 번에 조회한다. UUID 값은 모두 바인딩한다. */
	@Override
	public Map<UUID, List<DrawPublication.Slot>> findPublishedSlots(Set<UUID> publicationIds) {
		if (publicationIds.isEmpty()) return Map.of();
		Map<UUID, List<DrawPublication.Slot>> loaded = new HashMap<>();
		for (UUID id : publicationIds) loaded.put(id, new ArrayList<>());
		String placeholders = String.join(",", Collections.nCopies(publicationIds.size(), "?"));
		String sql = """
			select published.publication_id, r.event_prize_id, r.slot_number, r.id, participant.user_id
			from draw_publication_results published join draw_results r on r.id = published.draw_result_id
			join draw_candidates candidate on candidate.id = r.candidate_id
			join event_participants participant on participant.id = candidate.participant_id
			where published.publication_id in (
			""" + placeholders + ") order by published.publication_id, r.event_prize_id, r.slot_number";
		jdbc.query(sql, (RowCallbackHandler) row -> loaded.get(fromBytes(row.getBytes("publication_id"))).add(
		new DrawPublication.Slot(fromBytes(row.getBytes("event_prize_id")), row.getInt("slot_number"),
				fromBytes(row.getBytes("id")), fromBytes(row.getBytes("user_id")))),
		publicationIds.stream().map(id -> toBytes(id)).toArray());
		return immutableCopy(loaded);
	}

	/** 이벤트에 기존 취소나 재추첨 실행이 존재하는지 확인한다. */
	@Override
	public boolean hasCancellationOrReplacement(UUID eventId) {
		Integer count = jdbc.queryForObject("""
			select count(*) from draw_runs run where run.event_id = ? and
			(run.draw_type = 'REPLACEMENT' or exists (
			  select 1 from award_cancellations c join draw_results r on r.id = c.draw_result_id
			  where r.draw_run_id = run.id))
			""", Integer.class, toBytes(eventId));
		return count != null && count > 0;
	}

	/** 기존 추첨 실행이 속한 이벤트 식별자를 조회한다. */
	@Override
	public Optional<UUID> findEventId(UUID runId) {
		return jdbc.query("select event_id from draw_runs where id = ?",
		(row, index) -> fromBytes(row.getBytes("event_id")), toBytes(runId)).stream().findFirst();
	}

	/** 이벤트의 재추첨 실행과 결과·관리자 확인을 일괄 조회해 실행 ID로 연결한다. */
	@Override
	public List<DrawPublicationRun> findReplacementRuns(UUID eventId) {
		List<ReplacementRunRow> runs = jdbc.query("""
			select id, event_id, run_number, status, confirmed_at from draw_runs
			where event_id = ? and draw_type = 'REPLACEMENT' order by run_number
			""", (row, index) -> new ReplacementRunRow(fromBytes(row.getBytes("id")), fromBytes(row.getBytes("event_id")),
		row.getInt("run_number"), DrawRunStatus.valueOf(row.getString("status")), instant(row,
		"confirmed_at")), toBytes(eventId));
		if (runs.isEmpty()) return List.of();
		Set<UUID> runIds = new HashSet<>();
		for (ReplacementRunRow run : runs) runIds.add(run.id());
		Map<UUID, List<DrawResult>> results = findResults(runIds, eventId);
		Map<UUID, DrawResultConfirmation> checked = new HashMap<>();
		for (DrawResultConfirmationEntity confirmation : confirmations.findByRunIdIn(runIds)) {
			DrawResultConfirmation value = confirmation.toDomain();
			checked.put(value.getRunId(), value);
		}
		return runs.stream().map(run -> new DrawPublicationRun(run.id(), run.eventId(), run.number(), run.status(),
		run.confirmedAt(), results.getOrDefault(run.id(), List.of()), checked.get(run.id()))).toList();
	}

	/** JDBC 조회의 실행 메타데이터. 확정 결과·확인 기록은 실행 ID로 일괄 결합한다. */
	private record ReplacementRunRow(UUID id, UUID eventId, int number, DrawRunStatus status, Instant confirmedAt) {
	}

	/** 이벤트의 기존 결과에 연결된 취소 사유와 재추첨 실행 참조를 조회한다. */
	@Override
	public List<DrawPublication.Cancellation> findCancellations(UUID eventId) {
		return jdbc.query("""
			select c.id, c.draw_result_id, c.draw_run_id, c.reason from award_cancellations c
			join draw_results result on result.id = c.draw_result_id
			join draw_runs origin on origin.id = result.draw_run_id
			where origin.event_id = ? order by c.canceled_at, c.id
			""", (row, index) -> new DrawPublication.Cancellation(fromBytes(row.getBytes("id")),
		fromBytes(row.getBytes("draw_result_id")), nullableUuid(row, "draw_run_id"), row.getString("reason")),
		toBytes(eventId));
	}

	/** 실행 종류와 관계없이 기존 확인을 읽어 중복 저장과 과거 확인자 덮어쓰기를 막는다. */
	@Override
	public Optional<DrawResultConfirmation> findConfirmation(UUID runId) {
		return confirmations.findByRunId(runId).map(DrawResultConfirmationEntity::toDomain);
	}

	/** 이벤트 선잠금 안에서만 호출한다. UNIQUE와 서비스의 재조회로 확인자를 덮어쓰지 않는다. */
	@Override
	public DrawResultConfirmation saveConfirmation(UUID runId, UUID actorId, Instant confirmedAt) {
		// DATETIME(6)에 맞춰 절삭하여 저장 후 반올림된 시각을 미래로 오판하지 않게 한다.
		Instant storedAt = confirmedAt.truncatedTo(ChronoUnit.MICROS);
		DrawResultConfirmationEntity saved = confirmations.saveAndFlush(
				new DrawResultConfirmationEntity(runId, actorId, storedAt));
		UUID id = saved.getId();
		entityManager.detach(saved);
		return confirmations.findById(id).orElseThrow().toDomain();
	}

	/** 공개 메타데이터와 포함 결과를 저장하고 재조회한 실제 공개 버전을 반환한다. */
	@Override
	public DrawPublication savePublication(UUID eventId, UUID sourceRunId, int revision, String reason,
			DrawResultConfirmation confirmation, List<DrawPublication.Entry> included, Instant publishedAt) {
		DrawPublicationEntity publication = new DrawPublicationEntity(eventId, sourceRunId, revision, reason,
				confirmation == null ? null : confirmation.getConfirmedBy(),
				confirmation == null ? null : confirmation.getConfirmedAt(), publishedAt);
		return savePublicationRows(publication, included);
	}

	/** 과거 확인 행을 수정하지 않고 최초 공개 버전에 실제 발표자와 시각을 기록한다. */
	@Override
	public DrawPublication saveReviewedInitial(UUID eventId, UUID sourceRunId, String reason, UUID publisherId,
			List<DrawPublication.Entry> included, Instant publishedAt) {
		return savePublicationRows(new DrawPublicationEntity(eventId, sourceRunId, 1, reason,
				publisherId, publishedAt, publishedAt), included);
	}

	/** 같은 업무 트랜잭션에서 공개 행·포함 결과를 저장하고 DB 시각으로 재조회한다. */
	private DrawPublication savePublicationRows(DrawPublicationEntity publication, List<DrawPublication.Entry> included) {
		included = DrawPublication.validatedEntries(included);
		DrawPublicationEntity saved = publications.saveAndFlush(publication);
		entries.saveAllAndFlush(included.stream()
				.map(entry -> new DrawPublicationResultEntity(saved.getId(), saved.getEventId(), entry)).toList());
		UUID id = saved.getId();
		entityManager.detach(saved);
		return toDomain(publications.findById(id).orElseThrow());
	}

	/** GD-87의 확정 결과를 읽으며 경품·원본 후보·실행 후보 연결의 이벤트 일치를 대조한다. */
	private Map<UUID, List<DrawResult>> findResults(Set<UUID> runIds, UUID eventId) {
		Map<UUID, List<DrawResult>> results = new HashMap<>();
		for (UUID runId : runIds) results.put(runId, new ArrayList<>());
		String placeholders = String.join(",", Collections.nCopies(runIds.size(), "?"));
		String sql = """
			select r.draw_run_id, r.id, r.event_prize_id, r.slot_number, r.selection_order, r.candidate_id, r.result_type,
			r.created_at, prize.prize_rank, prize.event_id as prize_event, p.user_id,
			p.event_id as participant_event, origin.event_id as candidate_event, link.candidate_id as linked_candidate
			from draw_results r join event_prizes prize on prize.id = r.event_prize_id
			left join draw_candidates c on c.id = r.candidate_id
			left join event_participants p on p.id = c.participant_id
			left join draw_runs origin on origin.id = c.draw_run_id
			left join draw_run_candidates link on link.draw_run_id = r.draw_run_id and link.candidate_id = r.candidate_id
			where r.draw_run_id in (
			""" + placeholders + ") order by r.draw_run_id, prize.prize_rank, r.slot_number";
		jdbc.query(sql, (RowCallbackHandler) row -> {
			results.get(fromBytes(row.getBytes("draw_run_id"))).add(readResult(row, eventId));
		}, runIds.stream().map(id -> toBytes(id)).toArray());
		Map<UUID, List<DrawResult>> immutable = new HashMap<>();
		results.forEach((id, rows) -> immutable.put(id, List.copyOf(rows)));
		return Map.copyOf(immutable);
	}

	/** SQL 결과의 이벤트·후보 연결을 대조하고 선정 또는 미충원 결과를 복원한다. */
	private DrawResult readResult(ResultSet row, UUID eventId) throws SQLException {
		DrawSelectionResultType type = DrawSelectionResultType.valueOf(row.getString("result_type"));
		if (!eventId.equals(nullableUuid(row, "prize_event")) || (type == DrawSelectionResultType.SELECTED
				&& (!eventId.equals(nullableUuid(row, "participant_event"))
				|| !eventId.equals(nullableUuid(row, "candidate_event")) || nullableUuid(row, "linked_candidate") == null))) {
			throw new BusinessException(INVALID_PUBLICATION);
		}
		Integer order = row.getObject("selection_order", Integer.class);
		DrawSelection selection = new DrawSelection(fromBytes(row.getBytes("event_prize_id")),
		row.getInt("prize_rank"), row.getInt("slot_number"), order, nullableUuid(row, "candidate_id"),
		nullableUuid(row, "user_id"), type);
		return new DrawResult(fromBytes(row.getBytes("id")), selection, instant(row, "created_at"));
	}

	/** SQL의 선택적인 바이너리 UUID를 변환하고 값이 없으면 null을 반환한다. */
	private static UUID nullableUuid(ResultSet row, String column) throws SQLException {
		byte[] bytes = row.getBytes(column);
		return bytes == null ? null : fromBytes(bytes);
	}

	/** DB 시각을 UTC Instant로 변환하며 SQL NULL은 그대로 유지한다. */
	private static Instant instant(ResultSet row, String column) throws SQLException {
		java.time.LocalDateTime value = row.getObject(column, java.time.LocalDateTime.class);
		return value == null ? null : value.toInstant(java.time.ZoneOffset.UTC);
	}

	/** 이벤트 선잠금 아래 하나의 트랜잭션으로 저장하고 DB의 실제 시각을 반환한다. */
	@Override
	public DrawPublication saveInitial(UUID eventId, UUID runId, List<UUID> selectedResultIds, Instant publishedAt) {
		List<DrawPublication.Entry> included = selectedResultIds.stream()
				.map(resultId -> new DrawPublication.Entry(resultId, runId, null)).toList();
		DrawPublicationEntity saved = publications.saveAndFlush(new DrawPublicationEntity(eventId, runId, publishedAt));
		List<DrawPublicationResultEntity> stored = included.stream()
				.map(entry -> new DrawPublicationResultEntity(saved.getId(), eventId, entry)).toList();
		entries.saveAllAndFlush(stored);
		UUID publicationId = saved.getId();
		entityManager.detach(saved);
		return toDomain(publications.findById(publicationId).orElseThrow());
	}

	/** 저장된 식별자와 업무 참조·시각을 공개 업무의 도메인 값으로 복원한다. */
	private DrawPublication toDomain(DrawPublicationEntity publication) {
		return publication.toDomain(entries.findByIdPublicationIdOrderByIdResultId(publication.getId())
				.stream().map(DrawPublicationResultEntity::toDomain).toList());
	}
}
