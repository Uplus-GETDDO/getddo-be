package com.getddo.core.drawing.service;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawResultConfirmation;
import com.getddo.core.drawing.domain.DrawPublicationConfirmation;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

/** 저장소 대역을 이용한 공개 업무 규칙 검증. 실제 재추첨 연동 검증은 아니다. */
class DrawPublicationServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private final UUID eventId = UUID.randomUUID(), runId = UUID.randomUUID(), prizeId = UUID.randomUUID();
	private final UUID candidateId = UUID.randomUUID(), userId = UUID.randomUUID(), resultId = UUID.randomUUID();
	private final DrawPublicationRepository publications = mock(DrawPublicationRepository.class);
	private final DrawSnapshotRepository snapshots = mock(DrawSnapshotRepository.class);
	private final DrawResultRepository results = mock(DrawResultRepository.class);
	private final DrawPublicationNotificationService notifications = mock(DrawPublicationNotificationService.class);
	private final com.getddo.core.event.service.EventStatusChangeService eventStatuses = mock(com.getddo.core.event.service.EventStatusChangeService.class);
	private final DrawPublicationService service = new DrawPublicationService(publications, snapshots, results,
			new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), notifications, eventStatuses);
	private DrawSnapshot snapshot;
	private DrawResult selected;
	private DrawResult unfilled;

	/** 공개 서비스에 필요한 스냅샷·결과·공개 저장소 대역의 응답을 준비한다. */
	@BeforeEach
	void setUp() {
		DrawSnapshot.Rules rules = new DrawSnapshot.Rules(1, false, "NO_TICKET", "NORMAL", 1, 3, 5,
				List.of(new DrawSnapshotSource.Prize(prizeId, 1, 2)));
		DrawSnapshot.Candidate candidate = new DrawSnapshot.Candidate(candidateId, UUID.randomUUID(), userId,
				0, 1, null, null);
		snapshot = new DrawSnapshot(runId, eventId, DrawRunStatus.CONFIRMED, NOW.minusSeconds(1),
				"test", rules, List.of(candidate));
		selected = new DrawResult(resultId, new DrawSelection(prizeId, 1, 1, 1,
				candidateId, userId, DrawSelectionResultType.SELECTED), NOW);
		unfilled = new DrawResult(UUID.randomUUID(), new DrawSelection(prizeId, 1, 2, null,
				null, null, DrawSelectionResultType.UNFILLED), NOW);
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(NOW.minusSeconds(300), EventStatus.CLOSED)));
		when(snapshots.findInitial(eventId)).thenReturn(Optional.of(snapshot));
		when(results.lockSnapshot(runId)).thenReturn(Optional.of(snapshot));
		when(results.findConfirmed(snapshot)).thenReturn(new DrawConfirmation(runId, eventId, NOW, List.of(selected, unfilled)));
		when(publications.saveInitial(eventId, runId, List.of(resultId), NOW)).thenReturn(publication());
	}

	/** 마감 +5분 경계에 승인 없이 SELECTED 결과 ID만 저장소 대역에 전달하고 반환된 명단을 검증한다. */
	@Test
	@DisplayName("마감 +5분 경계에 승인 없이 SELECTED만 실제 공개 목록에 저장한다")
	void publishesAtBoundaryWithUnfilledSlot() {
		// when
		DrawPublication published = service.publishInitial(eventId).orElseThrow();
		// then
		assertThat(published.getEntries()).containsExactly(new DrawPublication.Entry(resultId, runId, null));
		assertThat(published.getConfirmedBy()).isNull();
		verify(notifications).register(published);
		InOrder order = inOrder(snapshots, results, publications);
		order.verify(snapshots).lockEvent(eventId);
		order.verify(publications).findLatest(eventId);
		order.verify(publications).hasCancellationOrReplacement(eventId);
		order.verify(snapshots).findInitial(eventId);
		order.verify(results).lockSnapshot(runId);
		verify(publications).saveInitial(eventId, runId, List.of(resultId), NOW);
	}

	/** 마감 +5분 직전에는 내부 확정 결과도 읽거나 공개하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("마감 +5분 직전에는 내부 확정 결과도 읽거나 공개하지 않는다")
	void blocksBeforeBoundary() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(NOW.minusSeconds(300).plusNanos(1), EventStatus.CLOSED)));
		// when / then
		assertThat(service.publishInitial(eventId)).isEmpty();
		verifyNoInteractions(results);
		verify(publications, never()).saveInitial(any(), any(), anyList(), any());
	}

	/** 미확정(PREPARING·READY·RUNNING)·실패(FAILED)·정상 종료(NO_ENTRIES·NO_CANDIDATES)에서 공개 차단과 결과 저장소 미호출을 검증한다. */
	@ParameterizedTest
	@EnumSource(value = DrawRunStatus.class, names = {"PREPARING", "READY", "RUNNING", "FAILED", "NO_ENTRIES", "NO_CANDIDATES"})
	@DisplayName("미확정·정상 종료를 공개로 처리하지 않는다")
	void blocksUnconfirmedAndTerminalRuns(DrawRunStatus status) {
		// given
		when(snapshots.findInitial(eventId)).thenReturn(Optional.of(new DrawSnapshot(runId, eventId,
				status, snapshot.fixedAt(), snapshot.algorithmVersion(), snapshot.rules(), snapshot.candidates())));
		// when / then
		assertThat(service.publishInitial(eventId)).isEmpty();
		verifyNoInteractions(results);
		verify(publications, never()).saveInitial(any(), any(), anyList(), any());
	}

	/** 취소 또는 재추첨이 있으면 유지 명단만 먼저 공개하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("취소 또는 재추첨이 있으면 유지 명단만 먼저 공개하지 않는다")
	void delaysWholeInitialListForPendingReplacement() {
		// given
		when(publications.hasCancellationOrReplacement(eventId)).thenReturn(true);
		UUID replacement = UUID.randomUUID();
		when(publications.findReplacementRuns(eventId)).thenReturn(List.of(new DrawPublicationRun(replacement,
				eventId, 1, DrawRunStatus.PREPARING, null, List.of(), null)));
		when(publications.findCancellations(eventId)).thenReturn(List.of(new DrawPublication.Cancellation(
				UUID.randomUUID(), resultId, replacement, "취소 사유")));
		// when / then
		assertThat(service.publishInitial(eventId)).isEmpty();
		verify(results).findConfirmed(snapshot);
		verify(publications, never()).saveInitial(any(), any(), anyList(), any());
	}

	/** 최초 결과 준비 지연 후 재호출하면 실제 완료 시각에 공개한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("최초 결과 준비 지연 후 재호출하면 실제 완료 시각에 공개한다")
	void publishesAfterDelayedPreparation() {
		// given
		when(snapshots.findInitial(eventId)).thenReturn(Optional.empty(), Optional.of(snapshot));
		// when / then
		assertThat(service.publishInitial(eventId)).isEmpty();
		assertThat(service.publishInitial(eventId).orElseThrow().getPublishedAt()).isEqualTo(NOW);
		verify(publications, times(1)).saveInitial(eventId, runId, List.of(resultId), NOW);
	}

	/** 최초 공개 재요청은 저장된 버전·시각을 그대로 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("최초 공개 재요청은 저장된 버전·시각을 그대로 반환한다")
	void replaysExistingPublication() {
		// given
		DrawPublication stored = publication();
		when(publications.findLatest(eventId)).thenReturn(Optional.of(stored));
		// when / then
		assertThat(service.publishInitial(eventId)).contains(stored);
		assertThat(service.publishInitial(eventId)).contains(stored);
		verifyNoInteractions(results, notifications, eventStatuses);
		verify(publications, never()).saveInitial(any(), any(), anyList(), any());
	}

	/** 미충원 자리도 누락된 불완전 결과는 최초 공개를 거절한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("미충원 자리도 누락된 불완전 결과는 최초 공개를 거절한다")
	void rejectsPartialResult() {
		// given
		when(results.findConfirmed(snapshot)).thenReturn(new DrawConfirmation(runId, eventId, NOW, List.of(selected)));
		// when / then
		assertThatThrownBy(() -> service.publishInitial(eventId)).isInstanceOf(BusinessException.class);
		verify(publications, never()).saveInitial(any(), any(), anyList(), any());
	}

	/** 같은 경품 자리의 중복 결과를 공개하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("같은 경품 자리의 중복 결과를 공개하지 않는다")
	void rejectsDuplicateSlot() {
		// given
		DrawResult duplicate = new DrawResult(UUID.randomUUID(), selected.getSelection(), NOW);
		when(results.findConfirmed(snapshot)).thenReturn(new DrawConfirmation(runId, eventId, NOW, List.of(selected, duplicate)));
		// when / then
		assertThatThrownBy(() -> service.publishInitial(eventId)).isInstanceOf(BusinessException.class);
		verify(publications, never()).saveInitial(any(), any(), anyList(), any());
	}

	/** 취소 이벤트에는 최초 공개 버전을 만들지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("취소 이벤트에는 최초 공개 버전을 만들지 않는다")
	void blocksCanceledEvent() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(NOW.minusSeconds(300), EventStatus.CANCELED)));
		// when / then
		assertThat(service.publishInitial(eventId)).isEmpty();
		verifyNoInteractions(results);
	}

	/** 실제 버전 간 변경 이력은 유지 자리를 제외하고 교체·미충원 자리의 전후를 보존한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("실제 버전 간 변경 이력은 유지 자리를 제외하고 교체·미충원 자리의 전후를 보존한다")
	void comparesActualPublishedVersions() {
		// given: 재추첨 서비스 대역이 아니라 실제 공개 이력 조회의 대역이다.
		DrawPublication initial = publication();
		UUID replacementRun = UUID.randomUUID(), replacementResult = UUID.randomUUID(), replacementUser = UUID.randomUUID();
		UUID confirmationId = UUID.randomUUID();
		DrawPublication update = new DrawPublication(UUID.randomUUID(), eventId, replacementRun, 2,
				DrawPublicationType.UPDATE, "변경 사유", UUID.randomUUID(), NOW.minusSeconds(1), NOW,
				List.of(new DrawPublication.Entry(replacementResult, replacementRun, confirmationId)));
		DrawPublication.Slot before = new DrawPublication.Slot(prizeId, 1, resultId, userId);
		DrawPublication.Slot after = new DrawPublication.Slot(prizeId, 1, replacementResult, replacementUser);
		when(publications.findHistory(eventId)).thenReturn(List.of(initial, update));
		Set<UUID> ids = Set.of(initial.getId(), update.getId());
		when(publications.findPublishedSlots(ids)).thenReturn(Map.of(initial.getId(), List.of(before), update.getId(), List.of(after)));
		// when
		List<DrawPublicationChange> changes = service.changes(eventId);
		// then
		assertThat(changes.getLast().getPublication()).isEqualTo(update);
		assertThat(changes.getLast().getChanges()).containsExactly(new DrawPublicationChange.SlotChange(
				prizeId, 1, resultId, userId, replacementResult, replacementUser));
		verify(publications).findPublishedSlots(ids);
		verify(publications).findExecutionEvidence(ids);
		verify(publications, never()).findPublishedSlots(any(UUID.class));
		when(publications.findPublishedSlots(ids)).thenReturn(Map.of(initial.getId(), List.of(before), update.getId(), List.of(before)));
		assertThat(service.changes(eventId).getLast().getChanges()).isEmpty();
		when(publications.findPublishedSlots(ids)).thenReturn(Map.of(initial.getId(), List.of(before), update.getId(), List.of()));
		assertThat(service.changes(eventId).getLast().getChanges()).containsExactly(new DrawPublicationChange.SlotChange(
				prizeId, 1, resultId, userId, null, null));
	}

	/** 최초 발표에 실제 발표자와 최초 실행 확인 근거가 함께 저장되는지 검증한다. */
	@Test
	@DisplayName("최초 정상 결과도 한 요청으로 확인·공개하고 실제 발표자·실행 근거를 저장한다")
	void publishesReviewedInitialWithActorAndEvidence() {
		// given
		UUID actor = UUID.randomUUID();
		DrawResultConfirmation checked = stubReviewedPublication(actor);
		// when
		DrawPublicationConfirmation response = service.publishReviewedInitial(eventId, runId, actor);
		// then
		assertThat(response.getConfirmation()).isEqualTo(checked);
		assertThat(response.getPublication().getConfirmedBy()).isEqualTo(actor);
		assertThat(response.getPublication().getEntries()).containsExactly(new DrawPublication.Entry(resultId, runId, checked.getId()));
		verify(publications).saveExecutionEvidence(response.getPublication().getId(), eventId,
				List.of(new DrawPublication.Execution(runId, checked.getId(), actor, NOW, List.of())));
		verify(eventStatuses).changeStatus(eventId, EventStatus.CLOSED, EventStatus.PUBLISHED);
		verify(notifications).register(response.getPublication());
	}

	/** 새 재추첨으로 검토 명단이 바뀌면 확인 기록을 만들기 전에 거절하는지 검증한다. */
	@Test
	@DisplayName("검토 후 새 재추첨이 생기면 옛 runId 발표를 저장 전에 거절한다")
	void rejectsStaleReviewedRunBeforeSavingConfirmation() {
		// given
		when(publications.findReplacementRuns(eventId)).thenReturn(List.of(new DrawPublicationRun(
				UUID.randomUUID(), eventId, 1, DrawRunStatus.CONFIRMED, NOW, List.of(), null)));
		// when / then
		assertThatThrownBy(() -> service.publishReviewedInitial(eventId, runId, UUID.randomUUID())).isInstanceOf(BusinessException.class);
		verify(publications, never()).saveConfirmation(any(), any(), any());
		verifyNoInteractions(eventStatuses, notifications);
	}

	/** 재추첨이 완료되지 않은 명단을 일부 공개하거나 확인 기록으로 남기지 않는지 검증한다. */
	@Test
	@DisplayName("최신 재추첨이 실패·진행 중이면 일부 명단을 먼저 발표하지 않는다")
	void rejectsUnfinishedReplacementBeforeSavingConfirmation() {
		// given
		UUID latest = UUID.randomUUID();
		when(publications.findReplacementRuns(eventId)).thenReturn(List.of(new DrawPublicationRun(
				latest, eventId, 1, DrawRunStatus.FAILED, null, List.of(), null)));
		// when / then
		assertThatThrownBy(() -> service.publishReviewedInitial(eventId, latest, UUID.randomUUID())).isInstanceOf(BusinessException.class);
		verify(publications, never()).saveConfirmation(any(), any(), any());
		verifyNoInteractions(eventStatuses, notifications);
	}

	/** 같은 최초 발표를 재시도해도 원래 확인자와 공개 결과를 보존하는지 검증한다. */
	@Test
	@DisplayName("동일 발표 재시도는 최초 성공 버전과 확인자를 반환하고 저장·알림을 중복하지 않는다")
	void replaysReviewedPublicationWithoutChangingPublisher() {
		// given
		DrawPublication first = publication();
		DrawResultConfirmation checked = new DrawResultConfirmation(UUID.randomUUID(), runId, UUID.randomUUID(), NOW);
		when(publications.findPrevious(eventId, 2)).thenReturn(Optional.of(first));
		when(publications.findConfirmation(runId)).thenReturn(Optional.of(checked));
		// when
		DrawPublicationConfirmation response = service.publishReviewedInitial(eventId, runId, UUID.randomUUID());
		// then
		assertThat(response.getPublication()).isEqualTo(first);
		assertThat(response.getConfirmation()).isEqualTo(checked);
		verifyNoInteractions(results, eventStatuses, notifications);
		verify(publications, never()).saveConfirmation(any(), any(), any());
	}

	/** 과거 실행 확인자와 현재 명단 발표자가 달라도 각각의 기록을 보존하는지 검증한다. */
	@Test
	@DisplayName("과거 확인자는 보존하고 공개 버전의 처리자에는 실제 발표자를 기록한다")
	void preservesOldCheckButRecordsActualPublisher() {
		// given
		UUID publisher = UUID.randomUUID();
		stubReviewedPublication(publisher);
		DrawResultConfirmation old = new DrawResultConfirmation(UUID.randomUUID(), runId, UUID.randomUUID(), NOW);
		when(publications.findConfirmation(runId)).thenReturn(Optional.of(old));
		// when
		DrawPublicationConfirmation response = service.publishReviewedInitial(eventId, runId, publisher);
		// then
		assertThat(response.getConfirmation()).isEqualTo(old);
		assertThat(response.getPublication().getConfirmedBy()).isEqualTo(publisher);
		verify(publications, never()).saveConfirmation(any(), any(), any());
	}

	/** 저장 대역은 실제 발표 입력의 처리자·명단을 그대로 반환한다. */
	private DrawResultConfirmation stubReviewedPublication(UUID actor) {
		DrawResultConfirmation checked = new DrawResultConfirmation(UUID.randomUUID(), runId, actor, NOW);
		when(publications.saveConfirmation(runId, actor, NOW)).thenReturn(checked);
		when(publications.saveReviewedInitial(eq(eventId), eq(runId), any(), eq(actor), anyList(), eq(NOW)))
				.thenAnswer(invocation -> {
					UUID publisher = invocation.getArgument(3);
					return new DrawPublication(UUID.randomUUID(), eventId, runId, 1, DrawPublicationType.INITIAL,
						null, publisher, NOW, NOW, invocation.getArgument(4));
				});
		return checked;
	}

	/** 지정 마감 시각과 상태를 가진 시험용 이벤트를 구성한다. */
	private DrawSnapshotSource.Event event(Instant endsAt, EventStatus status) {
		return new DrawSnapshotSource.Event(eventId, EventType.NO_TICKET, false, endsAt, status, false, "NORMAL", List.of());
	}
	/** 시험에 필요한 공개 버전 메타데이터와 포함 결과를 구성한다. */
	private DrawPublication publication() {
		return new DrawPublication(UUID.randomUUID(), eventId, runId, 1, DrawPublicationType.INITIAL,
				null, null, null, NOW, List.of(new DrawPublication.Entry(resultId, runId, null)));
	}
}
