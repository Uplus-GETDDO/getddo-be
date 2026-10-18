package com.getddo.core.drawing.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationConfirmation;
import com.getddo.core.drawing.domain.DrawResultConfirmation;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.service.EventStatusChangeService;

import static com.getddo.core.drawing.exception.DrawPublicationErrorCode.INVALID_PHASE;
import static com.getddo.core.drawing.exception.DrawPublicationErrorCode.NOT_CONFIRMABLE;
import static com.getddo.core.drawing.exception.DrawingErrorCode.EVENT_NOT_FOUND;
import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;

/** 최초 결과를 공개하거나 확인된 재추첨으로 명단을 갱신한다. 공개 버전과 알림 작업을 함께 저장한다. */
@Service
@RequiredArgsConstructor
public class DrawPublicationService {
	private final DrawPublicationRepository publications;
	private final DrawSnapshotRepository snapshots;
	private final DrawResultRepository results;
	private final TimeProvider time;
	private final DrawPublicationNotificationService notifications;
	private final EventStatusChangeService eventStatuses;

	/**
	 * 관리자가 검토한 최신 실행을 기준으로 최초 명단을 최종 확인하고 공개한다.
	 * 결과는 확정 후 불변이므로 새 취소·재추첨 실행이 생기면 이전 runId 요청을 거절한다.
	 * 확인·공개·상태·알림 등록은 이벤트 잠금을 가진 하나의 업무 트랜잭션에 참여한다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DrawPublicationConfirmation publishReviewedInitial(UUID eventId, UUID reviewedRunId, UUID actorId) {
		DrawSnapshotSource.Event event = snapshots.lockEvent(eventId)
				.orElseThrow(() -> new BusinessException(EVENT_NOT_FOUND));

		// 삭제됐거나 공개 업무를 허용하지 않는 상태이면 관리자 발표를 거절한다.
		if (!canPublish(event)) throw new BusinessException(INVALID_PHASE);

		Optional<DrawPublication> first = publications.findPrevious(eventId, 2);

		// 최초 공개가 이미 성공했다면 같은 요청의 응답을 재사용해 공개·알림 중복을 막는다.
		if (first.isPresent()) return replayInitial(first.get(), reviewedRunId);

		DrawSnapshot initial = snapshots.findInitial(eventId).orElseThrow(() -> new BusinessException(INVALID_RUN));
		DrawSnapshot locked = results.lockSnapshot(initial.runId()).orElseThrow(() -> new BusinessException(INVALID_RUN));

		// 다른 이벤트의 실행이거나 아직 확정되지 않은 실행으로는 발표할 수 없다.
		if (!eventId.equals(locked.eventId()) || locked.status() != DrawRunStatus.CONFIRMED) notConfirmable();

		results.validateRelations(locked);
		DrawConfirmation original = results.findConfirmed(locked);

		// 조회된 확정 결과가 잠근 최초 실행과 같은 이벤트에 속하는지 검증한다.
		if (!locked.runId().equals(original.getRunId()) || !eventId.equals(original.getEventId())) invalid();

		validateInitial(locked, original);
		List<DrawPublicationRun> runs = publications.findReplacementRuns(eventId).stream()
				.sorted(Comparator.comparingInt(DrawPublicationRun::getRunNumber)).toList();
		UUID latestRunId = runs.isEmpty() ? locked.runId() : runs.getLast().getRunId();

		// 검토 이후 새 재추첨이 생겼다면 오래된 명단을 발표하지 않도록 재검토를 요구한다.
		if (!latestRunId.equals(reviewedRunId)) throw new BusinessException(INVALID_PHASE);

		// 실패하거나 진행 중인 재추첨이 하나라도 남아 있으면 전체 명단을 발표할 수 없다.
		if (runs.stream().anyMatch(run -> run.getStatus() != DrawRunStatus.CONFIRMED)) notConfirmable();

		Instant now = time.now();

		// 미래 시각에 확정된 것으로 기록된 결과는 유효한 발표 근거로 사용할 수 없다.
		if (original.getConfirmedAt().isAfter(now)) invalid();

		DrawResultConfirmation initialCheck = check(locked.runId(), actorId, now, original.getConfirmedAt());
		List<DrawPublicationRun> checkedRuns = new ArrayList<>();
		for (DrawPublicationRun run : runs) {
			// 재추첨도 같은 이벤트의 결과이며 확정 시각이 존재하고 현재를 넘지 않아야 한다.
			if (!eventId.equals(run.getEventId()) || run.getConfirmedAt() == null
					|| run.getConfirmedAt().isAfter(now)) invalid();

			DrawResultConfirmation checked = check(run.getRunId(), actorId, now, run.getConfirmedAt());
			checkedRuns.add(new DrawPublicationRun(run.getRunId(), run.getEventId(), run.getRunNumber(),
					run.getStatus(), run.getConfirmedAt(), run.getResults(), checked));
		}
		DrawPublicationAssembler.Composition composed = DrawPublicationAssembler.compose(locked, original,
				checkedRuns, publications.findCancellations(eventId), reviewedRunId, now);

		// 취소된 자리를 포함한 전체 명단의 준비가 끝나야 최초 공개할 수 있다.
		if (!composed.isComplete()) notConfirmable();

		List<DrawPublication.Entry> entries = composed.getEntries().stream().map(entry ->
				entry.getRunId().equals(locked.runId()) ? new DrawPublication.Entry(entry.getResultId(),
						locked.runId(), initialCheck.getId()) : entry).toList();
		List<DrawPublication.Execution> evidence = new ArrayList<>();
		evidence.add(new DrawPublication.Execution(locked.runId(), initialCheck.getId(), initialCheck.getConfirmedBy(),
				initialCheck.getConfirmedAt(), List.of()));
		evidence.addAll(composed.getExecutions());
		DrawResultConfirmation trigger = reviewedRunId.equals(locked.runId()) ? initialCheck
				: checkedRuns.getLast().getConfirmation();

		// 기존 실행 확인은 보존하고 이번 최초 공개에는 실제 발표자·시각을 별도로 전달한다.
		DrawPublication saved = publications.saveReviewedInitial(eventId, reviewedRunId, composed.getReason(),
				actorId, entries, now);
		publications.saveExecutionEvidence(saved.getId(), eventId, evidence);
		eventStatuses.changeStatus(eventId, event.status(), EventStatus.PUBLISHED);
		notifications.register(saved);
		return new DrawPublicationConfirmation(eventId, trigger, saved);
	}

	/**
	 * 동일 발표 요청의 재시도 응답은 원래 성공한 최초 버전으로 고정한다. 최신 명단 조회가 아니다.
	 * 이후 변경 명단은 조회 API로 읽고 이 요청에서는 공개·알림을 다시 수행하지 않는다.
	 */
	private DrawPublicationConfirmation replayInitial(DrawPublication first, UUID reviewedRunId) {
		// 재시도는 최초 성공 요청의 실행 ID와 일치할 때만 같은 성공 응답을 돌려준다.
		if (!first.getSourceDrawId().equals(reviewedRunId)) throw new BusinessException(INVALID_PHASE);

		DrawResultConfirmation checked = publications.findConfirmation(reviewedRunId).orElseThrow(() ->
				new BusinessException(INVALID_PHASE));
		return new DrawPublicationConfirmation(first.getEventId(), checked, first);
	}

	/** 기존 확인은 덮어쓰지 않는다. 신규 확인 저장도 이후 공개 실패 시 함께 롤백된다. */
	private DrawResultConfirmation check(UUID runId, UUID actorId, Instant now, Instant resultConfirmedAt) {
		DrawResultConfirmation checked = publications.findConfirmation(runId)
				.orElseGet(() -> publications.saveConfirmation(runId, actorId, now));

		// 관리자 확인은 해당 실행의 결과 확정 이후부터 현재까지의 기록이어야 한다.
		if (!runId.equals(checked.getRunId()) || checked.getConfirmedAt().isBefore(resultConfirmedAt)
				|| checked.getConfirmedAt().isAfter(now)) invalid();

		return checked;
	}

	/** 미확정·실패·정상 종료 실행은 관리자 발표로 확정 결과를 대신 만들 수 없다. */
	private static void notConfirmable() {
		throw new BusinessException(NOT_CONFIRMABLE);
	}

	/**
	 * 마감 +5분 이후 저장된 최초 확정 결과만 공개한다. 승인·선정을 수행하지 않고 알림 작업 등록만 연결한다.
	 * 이벤트 → 실행 순서로 잠그며 목록 저장 실패 시 버전도 롤백한다.
	 * 공개 시각 전·미준비·정상 종료·취소·재추첨 대기는 빈 Optional을 반환한다.
	 * GD-86 스케줄러 연결은 별도 계약이며 이 메서드는 스케줄러를 등록하지 않는다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Optional<DrawPublication> publishInitial(UUID eventId) {
		return publish(eventId, true, null);
	}

	/** 관리자 확인과 같은 트랜잭션에서 전체 명단을 반영한다. 공개 전이면 예정 시각을 지킨다. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Optional<DrawPublication> publishConfirmed(UUID eventId, UUID triggerRunId) {
		return publish(eventId, false, triggerRunId);
	}

	/** 이벤트 선잠금 아래 공개 시각과 전체 명단을 검증하고 변경된 명단에만 새 버전을 저장한다. */
	private Optional<DrawPublication> publish(UUID eventId, boolean initialOnly, UUID triggerRunId) {
		DrawSnapshotSource.Event event = snapshots.lockEvent(eventId)
				.orElseThrow(() -> new BusinessException(EVENT_NOT_FOUND));
		Optional<DrawPublication> existing = publications.findLatest(eventId);

		// 최초 공개 전용 호출은 이미 공개된 버전을 반환하고 새 버전을 만들지 않는다.
		if (initialOnly && existing.isPresent()) return existing;

		Instant now = time.now();

		// 공개 업무가 허용되지 않거나 마감 후 5분 전이면 기존 공개 상태를 유지한다.
		if (!canPublish(event) || now.isBefore(event.endsAt().plusSeconds(300))) return existing;

		boolean replacement = publications.hasCancellationOrReplacement(eventId);
		Optional<DrawSnapshot> initial = snapshots.findInitial(eventId);

		// 최초 실행이 없거나 결과가 미확정이면 공개할 명단이 준비되지 않은 상태다.
		if (initial.isEmpty() || initial.get().status() != DrawRunStatus.CONFIRMED) return existing;

		DrawSnapshot snapshot = results.lockSnapshot(initial.get().runId())
				.orElseThrow(() -> new BusinessException(INVALID_RUN));

		// 잠금 후에도 최초 실행의 이벤트 연결과 확정 상태가 유효한지 다시 검증한다.
		if (!eventId.equals(snapshot.eventId()) || snapshot.status() != DrawRunStatus.CONFIRMED) invalid();

		results.validateRelations(snapshot);
		DrawConfirmation confirmation = results.findConfirmed(snapshot);

		// 최초 확정 결과의 이벤트·실행 연결과 확정 시각이 유효해야 명단을 구성할 수 있다.
		if (!eventId.equals(confirmation.getEventId()) || !snapshot.runId().equals(confirmation.getRunId())
				|| confirmation.getConfirmedAt().isAfter(now)) invalid();

		List<UUID> selected = validateInitial(snapshot, confirmation);

		// 취소·재추첨이 없으면 최초 확정 명단만으로 공개할 수 있다.
		if (!replacement) {
			// 같은 최초 명단이 이미 공개됐다면 버전과 알림을 중복 생성하지 않는다.
			if (existing.isPresent()) return existing;

			DrawPublication saved = publications.saveInitial(eventId, snapshot.runId(), selected, now);
			notifications.register(saved);
			return Optional.of(saved);
		}
		DrawPublicationAssembler.Composition composed = DrawPublicationAssembler.compose(snapshot, confirmation,
				publications.findReplacementRuns(eventId), publications.findCancellations(eventId), triggerRunId, now);

		// 최초 공개 전에는 취소·재추첨 처리가 덜 끝난 일부 명단을 먼저 노출하지 않는다.
		if (existing.isEmpty() && !composed.isComplete()) return Optional.empty();

		// 이미 공개된 명단은 현재 구성된 명단과 비교해 실제 변경 여부를 판단한다.
		if (existing.isPresent()) {
			Set<DrawPublication.Entry> previous = new HashSet<>(existing.get().getEntries());

			// 명단 항목이 같으면 새 공개 버전과 알림을 만들 필요가 없다.
			if (previous.equals(new HashSet<>(composed.getEntries()))) return existing;
		}
		int revision = existing.map(value -> Math.addExact(value.getRevision(), 1)).orElse(1);
		DrawPublication saved = publications.savePublication(eventId, composed.getSourceRunId(), revision,
				composed.getReason(), composed.getConfirmation(), composed.getEntries(), now);
		publications.saveExecutionEvidence(saved.getId(), eventId, composed.getExecutions());
		notifications.register(saved);
		return Optional.of(saved);
	}

	/** 확인 전 관계 검증만 수행한다. 기존 실행·결과를 수정하거나 공개 버전을 만들지 않는다. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public DrawPublicationRun validateReplacement(UUID eventId, UUID runId) {
		DrawSnapshotSource.Event event = snapshots.lockEvent(eventId)
				.orElseThrow(() -> new BusinessException(EVENT_NOT_FOUND));

		// 삭제됐거나 공개 업무를 허용하지 않는 이벤트에서는 재추첨 확인도 거절한다.
		if (!canPublish(event)) throw new BusinessException(NOT_CONFIRMABLE);

		DrawSnapshot initial = snapshots.findInitial(eventId).orElseThrow(() -> new BusinessException(INVALID_RUN));
		DrawSnapshot locked = results.lockSnapshot(initial.runId()).orElseThrow(() -> new BusinessException(INVALID_RUN));

		// 재추첨 검증의 기준이 되는 최초 실행이 같은 이벤트의 확정 실행이어야 한다.
		if (locked.status() != DrawRunStatus.CONFIRMED || !eventId.equals(locked.eventId())) invalid();

		results.validateRelations(locked);
		DrawConfirmation confirmed = results.findConfirmed(locked);

		// 최초 확정 결과가 기준 실행과 같은 이벤트에 연결됐는지 검증한다.
		if (!eventId.equals(confirmed.getEventId()) || !locked.runId().equals(confirmed.getRunId())) invalid();

		validateInitial(locked, confirmed);
		List<DrawPublicationRun> runs = publications.findReplacementRuns(eventId);
		DrawPublicationRun requested = runs.stream().filter(run -> run.getRunId().equals(runId)).findFirst()
				.orElseThrow(() -> new BusinessException(NOT_CONFIRMABLE));

		// 요청한 재추첨이 확정되기 전에는 관리자가 확인할 수 없다.
		if (requested.getStatus() != DrawRunStatus.CONFIRMED)
			throw new BusinessException(NOT_CONFIRMABLE);

		DrawPublicationAssembler.compose(locked, confirmed, runs, publications.findCancellations(eventId),
				runId, time.now());
		return requested;
	}

	/** 삭제 여부와 이벤트 상태로 공개 업무를 수행할 수 있는지 판정한다. */
	private static boolean canPublish(DrawSnapshotSource.Event event) {
		return !event.deleted() && (event.status() == EventStatus.OPEN || event.status() == EventStatus.CLOSED
				|| event.status() == EventStatus.DRAW_CONFIRMED || event.status() == EventStatus.REDRAWING
				|| event.status() == EventStatus.PUBLISHED);
	}

	/** 공개 조회 계약과 분리된 내부 이력 조회이며 취소된 결과도 과거 버전에 남는다. */
	@Transactional(readOnly = true)
	public List<DrawPublication> history(UUID eventId) {
		return publications.findHistory(eventId);
	}

	/** 실행 상태가 아니라 해당 결과가 실제 포함됐던 버전으로 판정한다. */
	@Transactional(readOnly = true)
	public boolean wasPublished(UUID resultId) {
		return publications.wasPublished(resultId);
	}

	/** 실제 공개 버전끼리 비교하며 비공개 취소·미확인 실행을 변경 이력으로 노출하지 않는다. */
	@Transactional(readOnly = true)
	public List<DrawPublicationChange> changes(UUID eventId) {
		List<DrawPublicationChange> history = new ArrayList<>();
		List<DrawPublication> versions = publications.findHistory(eventId);

		// 공개 이력이 없으면 명단·실행 근거를 추가 조회할 필요가 없다.
		if (versions.isEmpty()) return List.of();

		Set<UUID> ids = new HashSet<>();
		for (DrawPublication version : versions) ids.add(version.getId());
		Map<UUID, List<DrawPublication.Slot>> slots = publications.findPublishedSlots(ids);
		Map<UUID, List<DrawPublication.Execution>> evidence = publications.findExecutionEvidence(ids);
		List<DrawPublication.Slot> before = List.of();
		for (DrawPublication publication : versions) {
			List<DrawPublication.Slot> after = slots.getOrDefault(publication.getId(), List.of());
			DrawPublicationChange change = compare(publication, before, after);
			history.add(new DrawPublicationChange(publication, change.getChanges(),
					evidence.getOrDefault(publication.getId(), List.of())));
			before = after;
		}
		return List.copyOf(history);
	}

	/** 최신 버전부터 페이징하되 각 버전을 실제 직전 버전과 비교한다. 페이지 경계도 비교에서 누락하지 않는다. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResult<DrawPublicationChange> changes(UUID eventId, PageQuery query) {
		PageResult<DrawPublication> page = publications.findHistory(eventId, query);

		// 빈 페이지는 비교할 버전이 없으므로 전체 건수와 페이지 정보만 반환한다.
		if (page.getItems().isEmpty()) return new PageResult<>(List.of(), query.getPage(),
				query.getSize(), page.getTotalElements());

		List<DrawPublication> versions = page.getItems();
		Optional<DrawPublication> boundary = publications.findPrevious(eventId, versions.getLast().getRevision());
		Set<UUID> ids = new HashSet<>();
		for (DrawPublication version : versions) ids.add(version.getId());
		boundary.ifPresent(version -> ids.add(version.getId()));
		Map<UUID, List<DrawPublication.Slot>> slots = publications.findPublishedSlots(ids);
		Map<UUID, List<DrawPublication.Execution>> evidence = publications.findExecutionEvidence(ids);
		List<DrawPublicationChange> changes = new ArrayList<>();
		for (int index = 0; index < versions.size(); index++) {
			DrawPublication current = versions.get(index);
			DrawPublication previous = index + 1 < versions.size() ? versions.get(index + 1) : boundary.orElse(null);
			DrawPublicationChange change = compare(current, previous == null ? List.of() : slots.getOrDefault(previous.getId(),
					List.of()),
					slots.getOrDefault(current.getId(), List.of()));
			changes.add(new DrawPublicationChange(current, change.getChanges(), evidence.getOrDefault(current.getId(),
					List.of())));
		}
		return new PageResult<>(changes, query.getPage(), query.getSize(), page.getTotalElements());
	}

	/** 두 실제 공개 명단을 경품 자리로 비교해 결과 또는 사용자가 바뀐 자리만 반환한다. */
	private static DrawPublicationChange compare(DrawPublication publication, List<DrawPublication.Slot> previous,
			List<DrawPublication.Slot> current) {
		Map<Slot, DrawPublication.Slot> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
		for (DrawPublication.Slot row : previous) before.put(new Slot(row.getPrizeId(), row.getSlotNumber()), row);
		for (DrawPublication.Slot row : current) after.put(new Slot(row.getPrizeId(), row.getSlotNumber()), row);
		Set<Slot> all = new LinkedHashSet<>(before.keySet());
		all.addAll(after.keySet());
		List<DrawPublicationChange.SlotChange> changes = new ArrayList<>();
		for (Slot slot : all) {
			DrawPublication.Slot old = before.get(slot), updated = after.get(slot);

			// 같은 자리의 공개 결과가 그대로이면 변경 이력에서 제외한다.
			if (Objects.equals(old, updated)) continue;

			changes.add(new DrawPublicationChange.SlotChange(slot.getPrizeId(), slot.getNumber(),
					old == null ? null : old.getResultId(), old == null ? null : old.getUserId(),
					updated == null ? null : updated.getResultId(), updated == null ? null : updated.getUserId()));
		}
		return new DrawPublicationChange(publication, changes);
	}

	@Getter
	@EqualsAndHashCode
	private static final class Slot {
		private final UUID prizeId;
		private final int number;

		/** 동일 경품의 동일 자리를 식별하는 비교 키를 구성한다. */
		private Slot(UUID prizeId, int number) {
			this.prizeId = prizeId;
			this.number = number;
		}
	}

	/** 미충원을 포함해 모든 자리가 완성돼야 최초 공개한다. 누락·사용자 중복·자리 중복을 거절한다. */
	private static List<UUID> validateInitial(DrawSnapshot snapshot, DrawConfirmation confirmation) {
		Map<UUID, DrawSnapshotSource.Prize> prizes = new HashMap<>();
		long slotCount = 0;
		for (DrawSnapshotSource.Prize prize : snapshot.rules().prizes()) {
			// 당첨 인원이 잘못됐거나 경품 ID가 중복되면 자리 수를 신뢰할 수 없다.
			if (prize.winnerCount() < 1 || prizes.put(prize.id(), prize) != null) invalid();

			slotCount += prize.winnerCount();
		}

		// 미충원 결과까지 포함해 모든 경품 자리가 기록돼야 누락 없는 최초 명단이다.
		if (slotCount != confirmation.getResults().size()) invalid();

		Set<UUID> ids = new HashSet<>();
		Set<UUID> users = new HashSet<>();
		Map<UUID, Set<Integer>> slots = new HashMap<>();
		List<UUID> selected = new ArrayList<>();
		for (DrawResult result : confirmation.getResults()) {
			DrawSelection selection = result.getSelection();
			DrawSnapshotSource.Prize prize = prizes.get(selection.getPrizeId());

			// 각 결과의 경품·등수·자리 범위를 검사하고 결과 ID와 경품별 자리 중복을 차단한다.
			if (prize == null || prize.rank() != selection.getRank()
					|| selection.getSlotNumber() > prize.winnerCount() || !ids.add(result.getId())
					|| !slots.computeIfAbsent(prize.id(), ignored -> new HashSet<>()).add(selection.getSlotNumber())) invalid();

			// 실제 당첨 결과에만 후보·사용자 검증을 적용하고 공개할 결과 ID를 모은다.
			if (selection.getResultType() == DrawSelectionResultType.SELECTED) {
				boolean candidateMatches = snapshot.candidates().stream().anyMatch(candidate ->
						candidate.id().equals(selection.getCandidateId()) && candidate.userId().equals(selection.getUserId()));

				// 스냅샷 후보와 사용자가 일치해야 하며 같은 사용자가 여러 자리에 당첨될 수 없다.
				if (!candidateMatches || !users.add(selection.getUserId())) invalid();

				selected.add(result.getId());
			}
		}
		return selected;
	}

	/** 최초 실행과 확정 결과의 불일치를 업무 예외로 거절한다. */
	private static void invalid() {
		throw new BusinessException(INVALID_RUN);
	}
}
