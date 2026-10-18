package com.getddo.core.drawing.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawCandidate;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawPrize;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.service.EventStatusChangeService;

import static com.getddo.core.drawing.exception.DrawingErrorCode.EVENT_NOT_FOUND;
import static com.getddo.core.drawing.exception.DrawingErrorCode.NOT_EXECUTABLE;
import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;

/** 선정 결과와 추첨 완료 상태를 같은 트랜잭션에 저장한다. */
@Service
public class DrawConfirmationService {
	private final DrawResultRepository repository;
	private final DrawSnapshotRepository snapshots;
	private final DrawingSelectionService selection;
	private final TimeProvider time;
	private final EventStatusChangeService eventStatuses;

	/** 선정·저장·시각·이벤트 상태 변경을 주입하며 트랜잭션은 confirm에서 관리한다. */
	public DrawConfirmationService(DrawResultRepository repository, DrawSnapshotRepository snapshots,
			DrawingSelectionService selection, TimeProvider time, EventStatusChangeService eventStatuses) {
		this.repository = repository;
		this.snapshots = snapshots;
		this.selection = selection;
		this.time = time;
		this.eventStatuses = eventStatuses;
	}

	/**
	 * 저장된 최초/재추첨 실행 스냅샷으로 결과를 확정하고, 확정 재요청에는 기존 저장값을 반환한다.
	 * 이벤트 → 실행 순서로 잠그며 결과와 상태 변경은 서비스의 같은 트랜잭션에서 커밋한다.
	 * READ_COMMITTED는 이벤트 잠금 대기 후 이전 요청의 확정 결과를 읽기 위한 격리 수준이다.
	 * 확정 전 실패는 결과·상태·시각을 롤백하여 동일 READY 스냅샷으로 다시 시도할 수 있게 한다.
	 *
	 * @param runId 등록된 추첨 실행 식별자
	 * @return 실제 DB에 저장된 결과 ID·순서·확정 시각
	 * @throws BusinessException 실행·스냅샷·배정 관계가 잘못됐거나 이벤트가 실행 불가한 경우
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DrawConfirmation confirm(UUID runId) {
		return confirmLocked(runId, null);
	}

	/** 공통 실행은 자신의 시도 토큰으로만 저장 입력을 확정한다. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DrawConfirmation confirm(UUID runId, UUID attemptId) {
		return confirmLocked(runId, attemptId);
	}

	/** 이벤트·실행 잠금 아래 저장 입력으로 선정·확정한다. CONFIRMED 재요청은 토큰 검사 없이 저장 결과를 반환하며, 미확정 실행에만 시도 토큰 검증을 적용한다. */
	private DrawConfirmation confirmLocked(UUID runId, UUID attemptId) {
		UUID eventId = repository.findEventId(runId).orElseThrow(() -> new BusinessException(INVALID_RUN));
		DrawSnapshotSource.Event event = snapshots.lockEvent(eventId)
				.orElseThrow(() -> new BusinessException(EVENT_NOT_FOUND));
		DrawSnapshot snapshot = repository.lockSnapshot(runId).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (!eventId.equals(snapshot.eventId())) invalid();
		if (snapshot.status() == DrawRunStatus.CONFIRMED) {
			validateInput(snapshot);
			DrawConfirmation confirmed = repository.findConfirmed(snapshot);
			List<DrawSelection> storedSelections = new ArrayList<>();
			for (DrawResult result : confirmed.getResults()) {
				storedSelections.add(result.getSelection());
			}
			validateResults(snapshot, storedSelections);
			return confirmed;
		}
		if (snapshot.status() == DrawRunStatus.RUNNING && attemptId == null) invalid();
		repository.validateAttempt(runId, attemptId);
		if ((snapshot.status() != DrawRunStatus.READY && snapshot.status() != DrawRunStatus.RUNNING)
				|| repository.hasResults(runId)) invalid();
		if (!DrawExecutionEligibility.isExecutable(event, snapshot.drawType(), time.now())) {
			throw new BusinessException(NOT_EXECUTABLE);
		}
		validateInput(snapshot);
		repository.validateRelations(snapshot);
		List<DrawSelection> awards = repository.findCurrentAwards(eventId);
		Set<UUID> currentUsers = new HashSet<>();
		Set<DrawSnapshot.Slot> targets = new HashSet<>(targetSlots(snapshot));
		for (DrawSelection award : awards) {
			if (!currentUsers.add(award.getUserId()) || targets.stream().anyMatch(target ->
					target.prizeId().equals(award.getPrizeId()) && target.slotNumber() == award.getSlotNumber())) invalid();
		}
		if (snapshot.drawType() == DrawType.REPLACEMENT) {
			Set<UUID> retainedUsers = new HashSet<>();
			for (DrawSnapshot.RetainedAward retained : snapshot.rules().retainedAwards()) {
				if (!retainedUsers.add(retained.userId()) || awards.stream().noneMatch(award ->
						award.getUserId().equals(retained.userId()) && award.getPrizeId().equals(retained.prizeId())
						&& award.getSlotNumber() == retained.slotNumber())) invalid();
			}
			if (!retainedUsers.equals(currentUsers)) invalid();
		}
		for (DrawSnapshot.Candidate candidate : snapshot.candidates()) {
			if (currentUsers.contains(candidate.userId())) {
				invalid();
			}
		}
		List<DrawSelection> selections = selectTargets(snapshot);
		validateResults(snapshot, selections);
		DrawConfirmation confirmed = repository.saveConfirmed(snapshot, selections, time.now());
		if (snapshot.drawType() == DrawType.INITIAL) {
			// 확정 재요청은 위에서 저장 결과를 반환하므로 공개·재추첨 상태를 되돌리지 않는다.
			eventStatuses.changeStatus(eventId, event.status(), EventStatus.DRAW_CONFIRMED);
		}
		return confirmed;
	}

	/** 확정된 스냅샷의 버전·중복 후보·가중치·경품 조건을 검증한다. 최신 원본으로 입력을 교체하지 않는다. */
	private static void validateInput(DrawSnapshot snapshot) {
		if (snapshot.fixedAt() == null || !DrawSnapshotService.ALGORITHM_VERSION.equals(snapshot.algorithmVersion())
				|| snapshot.rules() == null || snapshot.rules().prizes().isEmpty()) invalid();
		if (snapshot.drawType() == DrawType.INITIAL) {
			if (!snapshot.rules().initialFormat() || snapshot.candidates().isEmpty()
					|| snapshot.rules().originalRunId() != null || !snapshot.rules().targetSlots().isEmpty()
					|| !snapshot.rules().retainedAwards().isEmpty()) invalid();
		} else if (snapshot.drawType() != DrawType.REPLACEMENT || !snapshot.rules().replacementFormat()
				|| snapshot.rules().originalRunId() == null || snapshot.rules().targetSlots().isEmpty()) invalid();
		// 자리 목록을 펼치기 전에 중복·음수·총 인원 오버플로를 검증한다.
		try {
			DrawingSelectionValidator.validatePrizes(toPrizes(snapshot));
		} catch (IllegalArgumentException | NullPointerException invalidInput) {
			invalid();
		}
		List<DrawSnapshot.Slot> targets = targetSlots(snapshot);
		Set<UUID> retainedIds = new HashSet<>();
		Set<UUID> retainedUsers = new HashSet<>();
		Set<String> retainedSlots = new HashSet<>();
		for (DrawSnapshot.RetainedAward retained : snapshot.rules().retainedAwards()) {
			DrawSnapshotSource.Prize prize = snapshot.rules().prizes().stream()
					.filter(p -> p.id().equals(retained.prizeId())).findFirst().orElse(null);
			if (retained.resultId() == null || retained.userId() == null || prize == null
					|| retained.slotNumber() < 1 || retained.slotNumber() > prize.winnerCount()
					|| !retainedIds.add(retained.resultId()) || !retainedUsers.add(retained.userId())
					|| !retainedSlots.add(retained.prizeId() + ":" + retained.slotNumber())
					|| targets.stream().anyMatch(slot -> slot.prizeId().equals(retained.prizeId())
					&& slot.slotNumber() == retained.slotNumber())) invalid();
		}
		Set<UUID> participants = new HashSet<>();
		for (DrawSnapshot.Candidate candidate : snapshot.candidates()) {
			// 버전 1에 저장된 후보 형식을 검증한다. 현재 어뷰징 판단을 조회하거나 후보를 재선별하지 않는다.
			if (candidate.id() == null || candidate.userId() == null || candidate.participantId() == null
					|| !participants.add(candidate.participantId()) || retainedUsers.contains(candidate.userId()) || candidate.ticketCount() < 0
					|| candidate.eligibilityEvidence() == null || candidate.eligibilityEvidence().excluded()
					|| !candidate.userId().equals(candidate.eligibilityEvidence().userId())) invalid();
		}
		try {
			if (!snapshot.candidates().isEmpty()) DrawingSelectionValidator.validateCandidates(toCandidates(snapshot),
					snapshot.rules().weightingEnabled());
		} catch (IllegalArgumentException | NullPointerException invalidInput) {
			// 저장 스냅샷 검증은 선정기의 입력 오류를 일관된 도메인 오류로 보고한다.
			invalid();
		}
	}

	/** 저장된 후보의 식별자·사용자·가중치를 선정기 입력으로 변환한다. */
	private static List<DrawCandidate> toCandidates(DrawSnapshot snapshot) {
		List<DrawCandidate> candidates = new ArrayList<>();
		for (DrawSnapshot.Candidate candidate : snapshot.candidates()) {
			candidates.add(new DrawCandidate(candidate.id(), candidate.userId(), candidate.weight()));
		}
		return List.copyOf(candidates);
	}

	/** 저장된 경품의 식별자·등수·당첨 인원을 선정기 입력으로 변환한다. */
	private static List<DrawPrize> toPrizes(DrawSnapshot snapshot) {
		List<DrawPrize> prizes = new ArrayList<>();
		for (DrawSnapshotSource.Prize prize : snapshot.rules().prizes()) {
			prizes.add(new DrawPrize(prize.id(), prize.rank(), prize.winnerCount()));
		}
		return List.copyOf(prizes);
	}

	/** 실제 대상 자리만 선정기에 전달하고 가상 자리 번호를 저장된 실제 번호로 변환한다. */
	private List<DrawSelection> selectTargets(DrawSnapshot snapshot) {
		List<DrawSnapshot.Slot> slots = targetSlots(snapshot);
		if (snapshot.candidates().isEmpty()) {
			return slots.stream().map(slot -> new DrawSelection(slot.prizeId(), slot.rank(), slot.slotNumber(),
					null, null, null, DrawSelectionResultType.UNFILLED)).toList();
		}
		Map<UUID, Integer> counts = new HashMap<>();
		for (DrawSnapshot.Slot slot : slots) counts.merge(slot.prizeId(), 1, Integer::sum);
		List<DrawPrize> prizes = new ArrayList<>();
		for (DrawSnapshotSource.Prize prize : snapshot.rules().prizes()) {
			if (counts.containsKey(prize.id())) prizes.add(new DrawPrize(prize.id(), prize.rank(),
					counts.get(prize.id())));
		}
		List<DrawSelection> selected = selection.select(toCandidates(snapshot), prizes,
				snapshot.rules().weightingEnabled());
		if (selected == null || selected.size() != slots.size()) invalid();
		Map<UUID, Integer> virtualSlots = new HashMap<>();
		List<DrawSelection> mapped = new ArrayList<>();
		for (int index = 0; index < selected.size(); index++) {
			DrawSelection result = selected.get(index);
			DrawSnapshot.Slot slot = slots.get(index);
			int virtualSlot = virtualSlots.merge(slot.prizeId(), 1, Integer::sum);
			if (!result.getPrizeId().equals(slot.prizeId()) || result.getRank() != slot.rank()
					|| result.getSlotNumber() != virtualSlot) invalid();
			mapped.add(new DrawSelection(slot.prizeId(), slot.rank(), slot.slotNumber(), result.getSelectionOrder(),
					result.getCandidateId(), result.getUserId(), result.getResultType()));
		}
		return List.copyOf(mapped);
	}

	/** 최초는 전 자리, 재추첨은 버전 2에 저장된 빈 자리만 사용한다. */
	static List<DrawSnapshot.Slot> targetSlots(DrawSnapshot snapshot) {
		List<DrawSnapshot.Slot> slots = new ArrayList<>();
		Map<UUID, DrawSnapshotSource.Prize> prizes = new HashMap<>();
		for (DrawSnapshotSource.Prize prize : snapshot.rules().prizes()) {
			prizes.put(prize.id(), prize);
			if (snapshot.drawType() == DrawType.INITIAL) {
				for (int slot = 1; slot <= prize.winnerCount(); slot++) slots.add(new DrawSnapshot.Slot(prize.id(), prize.rank(), slot));
			}
		}
		if (snapshot.drawType() == DrawType.REPLACEMENT) {
			Set<DrawSnapshot.Slot> unique = new HashSet<>();
			for (DrawSnapshot.Slot slot : snapshot.rules().targetSlots()) {
				DrawSnapshotSource.Prize prize = prizes.get(slot.prizeId());
				if (prize == null || slot.rank() != prize.rank() || slot.slotNumber() < 1
						|| slot.slotNumber() > prize.winnerCount() || !unique.add(slot)) invalid();
				slots.add(slot);
			}
		}
		slots.sort(Comparator.comparingInt(DrawSnapshot.Slot::rank).thenComparingInt(DrawSnapshot.Slot::slotNumber));
		return List.copyOf(slots);
	}

	/** 저장된 실제 자리·후보·선정 순서와 후보 소진 이후 미충원을 검증한다. */
	static void validateResults(DrawSnapshot snapshot, List<DrawSelection> results) {
		List<DrawSnapshot.Slot> slots = targetSlots(snapshot);
		if (results.size() != slots.size()) invalid();
		int order = 0;
		Set<UUID> users = new HashSet<>(), candidates = new HashSet<>();
		Map<UUID, UUID> candidateUsers = new HashMap<>();
		for (DrawSnapshot.Candidate candidate : snapshot.candidates()) candidateUsers.put(candidate.id(),
				candidate.userId());
		for (int index = 0; index < slots.size(); index++) {
			DrawSnapshot.Slot slot = slots.get(index);
			DrawSelection result = results.get(index);
			if (!slot.prizeId().equals(result.getPrizeId()) || result.getRank() != slot.rank()
					|| result.getSlotNumber() != slot.slotNumber()) invalid();
			if (result.getResultType() == DrawSelectionResultType.SELECTED) {
				if (++order != result.getSelectionOrder() || !users.add(result.getUserId()) || !candidates.add(result.getCandidateId())
						|| !result.getUserId().equals(candidateUsers.get(result.getCandidateId()))) invalid();
			} else if (order != snapshot.candidates().size()) invalid();
		}
		if (order != Math.min(snapshot.candidates().size(), slots.size())) invalid();
	}

	/** 실행·스냅샷·선정 결과의 불일치를 공통 도메인 오류로 전달해 확정 트랜잭션을 롤백한다. */
	private static void invalid() {
		throw new BusinessException(INVALID_RUN);
	}
}
