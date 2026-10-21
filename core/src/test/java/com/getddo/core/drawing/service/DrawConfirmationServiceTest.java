package com.getddo.core.drawing.service;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

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
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.service.EventStatusChangeService;
import com.getddo.core.event.domain.EventType;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;
import static com.getddo.core.drawing.exception.DrawingErrorCode.NOT_EXECUTABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DrawConfirmationServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
	private final UUID runId = UUID.randomUUID(), eventId = UUID.randomUUID(), prizeId = UUID.randomUUID();
	private final DrawResultRepository repository = mock(DrawResultRepository.class);
	private final DrawSnapshotRepository snapshots = mock(DrawSnapshotRepository.class);
	private final DrawRandom random = mock(DrawRandom.class);
	private final DrawingSelectionService selection = spy(new DrawingSelectionService(random));
	private final EventStatusChangeService eventStatuses = mock(EventStatusChangeService.class);
	private final DrawConfirmationService service = new DrawConfirmationService(repository, snapshots, selection,
			new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), eventStatuses);
	private DrawSnapshot snapshot;

	/** 마감 후 실행 가능한 READY 스냅샷과 제어 가능한 난수를 준비한다. */
	@BeforeEach
	void setUp() {
		snapshot = snapshot(DrawRunStatus.READY, List.of(candidate(UUID.randomUUID(), 1)),
				List.of(new DrawSnapshotSource.Prize(prizeId, 1, 2)));
		when(repository.findEventId(runId)).thenReturn(Optional.of(eventId));
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(new DrawSnapshotSource.Event(eventId,
				EventType.NO_TICKET, false, NOW.minusSeconds(60), EventStatus.CLOSED, false, "vip", List.of())));
		when(repository.lockSnapshot(runId)).thenReturn(Optional.of(snapshot));
		when(repository.findCurrentAwards(eventId)).thenReturn(List.of());
		when(random.nextBelow(any())).thenReturn(BigInteger.ZERO);
	}

	/** 입력 경품 순서와 무관하게 상위 등수를 채우고 후보 소진 후 미충원을 저장하는지 검증한다. */
	@Test
	@DisplayName("상위 등수부터 선정하고 남은 자리와 결과를 한 번 저장한다")
	void storesSelectionAndUnfilledInRankOrder() {
		// given
		UUID secondPrize = UUID.randomUUID();
		snapshot = snapshot(DrawRunStatus.READY, List.of(candidate(UUID.randomUUID(), 1)),
				List.of(new DrawSnapshotSource.Prize(secondPrize, 2, 1), new DrawSnapshotSource.Prize(prizeId, 1, 1)));
		when(repository.lockSnapshot(runId)).thenReturn(Optional.of(snapshot));
		when(repository.saveConfirmed(eq(snapshot), anyList(), eq(NOW))).thenAnswer(invocation -> {
			List<DrawSelection> results = invocation.getArgument(1);
			return new DrawConfirmation(runId, eventId, NOW,
					results.stream().map(result -> new DrawResult(UUID.randomUUID(), result, NOW)).toList());
		});
		// when
		DrawConfirmation confirmed = service.confirm(runId);
		// then
		assertThat(confirmed.getResults()).hasSize(2);
		assertThat(confirmed.getResults().getFirst().getSelection().getPrizeId()).isEqualTo(prizeId);
		assertThat(confirmed.getResults().getFirst().getSelection().getSelectionOrder()).isEqualTo(1);
		assertThat(confirmed.getResults().getLast().getSelection().getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
		verify(repository).saveConfirmed(eq(snapshot), anyList(), eq(NOW));
		verify(eventStatuses).changeStatus(eventId, EventStatus.CLOSED, EventStatus.DRAW_CONFIRMED);
		InOrder ordered = inOrder(repository, snapshots);
		ordered.verify(repository).findEventId(runId);
		ordered.verify(snapshots).lockEvent(eventId);
		ordered.verify(repository).lockSnapshot(runId);
	}

	/** 확정 재요청이 난수와 현재 배정을 읽지 않고 저장된 결과를 그대로 반환하는지 검증한다. */
	@Test
	@DisplayName("확정된 실행은 선정기·현재 배정 조회 없이 저장된 ID와 순서를 반환한다")
	void confirmedRequestDoesNotSelectAgain() {
		// given
		snapshot = snapshot(DrawRunStatus.CONFIRMED, snapshot.candidates(), snapshot.rules().prizes());
		when(repository.lockSnapshot(runId)).thenReturn(Optional.of(snapshot));
		List<DrawSelection> selections = new DrawingSelectionService(bound -> BigInteger.ZERO).select(
				List.of(new DrawCandidate(snapshot.candidates().getFirst().id(), snapshot.candidates().getFirst().userId(), 1)),
				List.of(new DrawPrize(prizeId, 1, 2)), false);
		DrawConfirmation stored = new DrawConfirmation(runId, eventId, NOW,
				selections.stream().map(result -> new DrawResult(UUID.randomUUID(), result, NOW)).toList());
		when(repository.findConfirmed(snapshot)).thenReturn(stored);
		// when / then
		assertThat(service.confirm(runId)).isSameAs(stored);
		verifyNoInteractions(selection, random, eventStatuses);
		verify(repository, never()).findCurrentAwards(any());
		verify(repository, never()).saveConfirmed(any(), anyList(), any());
	}

	/** 미완료·실패·대상 없는 정상 종료 상태를 결과 확정으로 우회하지 못하게 한다. */
	@ParameterizedTest
	@EnumSource(value = DrawRunStatus.class, names = {"PREPARING", "RUNNING", "FAILED", "NO_ENTRIES", "NO_CANDIDATES"})
	@DisplayName("READY·CONFIRMED 이외 실행을 재선정하지 않는다")
	void rejectsUnreadyStates(DrawRunStatus status) {
		// given
		when(repository.lockSnapshot(runId)).thenReturn(Optional.of(snapshot(status, snapshot.candidates(), snapshot.rules().prizes())));
		// when / then
		assertInvalid();
		verifyNoInteractions(selection);
	}

	/** 서로 다른 후보 ID가 같은 사용자를 가리키는 잘못된 스냅샷을 거절하는지 검증한다. */
	@Test
	@DisplayName("같은 사용자의 서로 다른 후보 ID를 거절한다")
	void rejectsDuplicateUserBeforeSelection() {
		// given
		UUID user = UUID.randomUUID();
		when(repository.lockSnapshot(runId)).thenReturn(Optional.of(snapshot(DrawRunStatus.READY,
				List.of(candidate(user, 1), candidate(user, 1)), snapshot.rules().prizes())));
		// when / then
		assertInvalid();
		verifyNoInteractions(selection);
	}

	/** 최초 실행의 대상 경품에 유효 당첨이 있으면 기존 배정을 덮어쓰지 않는지 검증한다. */
	@Test
	@DisplayName("기존 유효 당첨의 대상 경품·사용자 충돌을 거절한다")
	void rejectsCurrentAwardConflict() {
		// given
		when(repository.findCurrentAwards(eventId)).thenReturn(List.of(new DrawSelection(prizeId, 1, 1, 1,
				UUID.randomUUID(), UUID.randomUUID(), DrawSelectionResultType.SELECTED)));
		// when / then
		assertInvalid();
		verifyNoInteractions(selection);
	}

	/** 선정기가 실행에 연결되지 않은 후보를 반환해도 영속화하지 않는지 검증한다. */
	@Test
	@DisplayName("실행에 없는 후보를 선정기가 반환해도 저장하지 않는다")
	void rejectsForeignCandidateReturnedBySelection() {
		// given
		doReturn(List.of(new DrawSelection(prizeId, 1, 1, 1, UUID.randomUUID(), UUID.randomUUID(), DrawSelectionResultType.SELECTED),
				DrawSelection.unfilled(new DrawPrize(prizeId, 1, 2), 2))).when(selection).select(anyList(), anyList(), anyBoolean());
		// when / then
		assertInvalid();
		verify(repository, never()).saveConfirmed(any(), anyList(), any());
	}

	/** 확정 전 저장 실패 이후 원본을 다시 읽지 않고 동일 스냅샷으로 재시도하는지 검증한다. */
	@Test
	@DisplayName("확정 전 실패 후 같은 저장 스냅샷으로 다시 선정할 수 있다")
	void retriesUsingSameSnapshotAfterFailure() {
		// given
		when(repository.saveConfirmed(eq(snapshot), anyList(), any())).thenThrow(new IllegalStateException("저장 실패"))
				.thenAnswer(invocation -> {
			List<DrawSelection> results = invocation.getArgument(1);
			return new DrawConfirmation(runId, eventId, NOW,
					results.stream().map(result -> new DrawResult(UUID.randomUUID(), result, NOW)).toList());
		});
		// when / then
		assertThatThrownBy(() -> service.confirm(runId)).isInstanceOf(IllegalStateException.class);
		assertThat(service.confirm(runId).getRunId()).isEqualTo(runId);
		verify(selection, times(2)).select(anyList(), anyList(), eq(false));
		verify(snapshots, never()).findParticipants(any());
	}

	/** 다른 경품의 유효 당첨자도 다시 선정하지 못하게 하는 이벤트 단위 중복 방지를 검증한다. */
	@Test
	@DisplayName("다른 경품의 기존 당첨 사용자도 후보에 있으면 거절한다")
	void rejectsCurrentWinnerInCandidates() {
		// given
		when(repository.findCurrentAwards(eventId)).thenReturn(List.of(new DrawSelection(UUID.randomUUID(), 2, 1, 1,
				UUID.randomUUID(), snapshot.candidates().getFirst().userId(), DrawSelectionResultType.SELECTED)));
		// when / then
		assertInvalid();
		verifyNoInteractions(selection);
	}

	/** READY에 남아 있는 일부 결과를 정상 재시도로 해석하여 덮어쓰지 않는지 검증한다. */
	@Test
	@DisplayName("READY에 일부 결과가 남아 있으면 기존 결과를 덮어쓰지 않는다")
	void rejectsPartialResults() {
		// given
		when(repository.hasResults(runId)).thenReturn(true);
		// when / then
		assertInvalid();
		verifyNoInteractions(selection);
	}

	/** 미지원 알고리즘의 스냅샷을 현재 선정기로 임의 해석하지 않는지 검증한다. */
	@Test
	@DisplayName("알 수 없는 알고리즘 버전을 현재 선정기로 재해석하지 않는다")
	void rejectsUnknownAlgorithm() {
		// given
		when(repository.lockSnapshot(runId)).thenReturn(Optional.of(new DrawSnapshot(runId, eventId,
				DrawRunStatus.READY, NOW, "unknown", snapshot.rules(), snapshot.candidates())));
		// when / then
		assertInvalid();
		verifyNoInteractions(selection);
	}

	/** 이벤트 취소 이후 새 결과 확정을 차단하고 선정기를 호출하지 않는지 검증한다. */
	@Test
	@DisplayName("취소된 이벤트의 READY 실행은 확정하지 않는다")
	void refusesCanceledEvent() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(new DrawSnapshotSource.Event(eventId,
				EventType.NO_TICKET, false, NOW.minusSeconds(60), EventStatus.CANCELED, false, "vip", List.of())));
		// when / then
		assertThatThrownBy(() -> service.confirm(runId)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(NOT_EXECUTABLE);
		verifyNoInteractions(selection);
	}

	/** 마감 후 1분 직전의 요청이 난수·결과 저장에 도달하지 않는지 나노초 경계로 검증한다. */
	@Test
	@DisplayName("마감 후 1분보다 1ns 이르면 확정하지 않는다")
	void rejectsBeforeOneMinuteBoundary() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(new DrawSnapshotSource.Event(eventId,
				EventType.NO_TICKET, false, NOW.minusSeconds(60).plusNanos(1), EventStatus.CLOSED,
				false, "vip", List.of())));
		// when / then
		assertThatThrownBy(() -> service.confirm(runId)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(NOT_EXECUTABLE);
		verifyNoInteractions(selection);
		verify(repository, never()).saveConfirmed(any(), anyList(), any());
	}

	/** SCHEDULED를 포함한 최초 추첨 상태의 1분 경계 이후 실행을 고정 시각으로 검증한다. */
	@ParameterizedTest
	@CsvSource({"SCHEDULED,60000000000", "CLOSED,60000000000", "OPEN,60000000001"})
	@DisplayName("마감 1분 이후에는 SCHEDULED·OPEN·CLOSED 이벤트를 확정할 수 있다")
	void confirmsAtAndAfterOneMinute(EventStatus status, long elapsedNanos) {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(new DrawSnapshotSource.Event(eventId,
				EventType.NO_TICKET, false, NOW.minusNanos(elapsedNanos), status, false, "vip", List.of())));
		when(repository.saveConfirmed(eq(snapshot), anyList(), eq(NOW))).thenAnswer(invocation -> {
			List<DrawSelection> results = invocation.getArgument(1);
			return new DrawConfirmation(runId, eventId, NOW,
					results.stream().map(result -> new DrawResult(UUID.randomUUID(), result, NOW)).toList());
		});
		// when / then
		assertThat(service.confirm(runId).getRunId()).isEqualTo(runId);
		verify(repository).saveConfirmed(eq(snapshot), anyList(), eq(NOW));
		verify(eventStatuses).changeStatus(eventId, status, EventStatus.DRAW_CONFIRMED);
	}

	/** 검토 시간이 지났더라도 논리 삭제된 이벤트는 확정하지 않는지 검증한다. */
	@Test
	@DisplayName("논리 삭제된 이벤트는 확정하지 않는다")
	void rejectsDeletedEvent() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(new DrawSnapshotSource.Event(eventId,
				EventType.NO_TICKET, false, NOW.minusSeconds(60), EventStatus.CLOSED, true, "vip", List.of())));
		// when / then
		assertThatThrownBy(() -> service.confirm(runId)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(NOT_EXECUTABLE);
		verifyNoInteractions(selection);
	}

	/** 공용 검증기의 입력 오류가 선정 전에 INVALID_RUN으로 변환되는지 확인한다. */
	@Test
	@DisplayName("후보·경품 공용 검증 실패는 일관된 실행 오류로 반환한다")
	void rejectsInvalidSelectionInputsAsDomainErrors() {
		// given
		List<DrawSnapshot> invalidInputs = List.of(
				snapshot(DrawRunStatus.READY, List.of(candidate(UUID.randomUUID(), 2)), snapshot.rules().prizes()),
				snapshot(DrawRunStatus.READY, List.of(candidate(UUID.randomUUID(), 0)), snapshot.rules().prizes()),
				snapshot(DrawRunStatus.READY, snapshot.candidates(), List.of(
						new DrawSnapshotSource.Prize(prizeId, 1, 1), new DrawSnapshotSource.Prize(prizeId, 2, 1))),
				snapshot(DrawRunStatus.READY, snapshot.candidates(), List.of(
						new DrawSnapshotSource.Prize(prizeId, 1, 1), new DrawSnapshotSource.Prize(UUID.randomUUID(), 1, 1))),
				snapshot(DrawRunStatus.READY, snapshot.candidates(), List.of(
						new DrawSnapshotSource.Prize(prizeId, 1, Integer.MAX_VALUE),
						new DrawSnapshotSource.Prize(UUID.randomUUID(), 2, 1))),
				snapshot(DrawRunStatus.READY, snapshot.candidates(), List.of(new DrawSnapshotSource.Prize(null, 1, 1))));
		// when / then
		for (DrawSnapshot input : invalidInputs) {
			when(repository.lockSnapshot(runId)).thenReturn(Optional.of(input));
			assertInvalid();
		}
		verifyNoInteractions(selection);
		verify(repository, never()).saveConfirmed(any(), anyList(), any());
	}

	/** 중복 당첨·순서 누락·조기 미충원·자리 오류를 결과 저장 전에 거절하는지 검증한다. */
	@Test
	@DisplayName("결과의 중복 당첨·순서 누락·조기 미충원·잘못된 자리를 저장 전에 거절한다")
	void rejectsInvalidSelectionShape() {
		// given
		DrawSnapshot.Candidate first = candidate(UUID.randomUUID(), 1);
		DrawSnapshot.Candidate second = candidate(UUID.randomUUID(), 1);
		DrawSnapshot input = snapshot(DrawRunStatus.READY, List.of(first, second), snapshot.rules().prizes());
		DrawPrize prize = new DrawPrize(prizeId, 1, 2);
		DrawSelection selected = DrawSelection.selected(prize, 1, 1, new DrawCandidate(first.id(), first.userId(), 1));
		List<List<DrawSelection>> invalidResults = List.of(
				List.of(selected, DrawSelection.selected(prize, 2, 2, new DrawCandidate(first.id(), first.userId(), 1))),
				List.of(selected, DrawSelection.selected(prize, 2, 3, new DrawCandidate(second.id(), second.userId(), 1))),
				List.of(selected, DrawSelection.unfilled(prize, 2)),
				List.of(selected, DrawSelection.selected(prize, 3, 2, new DrawCandidate(second.id(), second.userId(), 1))));
		// when / then
		for (List<DrawSelection> results : invalidResults) {
			assertThatThrownBy(() -> DrawConfirmationService.validateResults(input, results))
					.isInstanceOf(BusinessException.class)
					.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(INVALID_RUN);
		}
	}

	/** 잘못된 실행이 예상 도메인 오류로 거절되는지 확인한다. */
	private void assertInvalid() {
		assertThatThrownBy(() -> service.confirm(runId)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(INVALID_RUN);
	}
	/** 원본 조회 없이 재사용할 수 있는 버전 1 최초 실행 스냅샷을 구성한다. */
	private DrawSnapshot snapshot(DrawRunStatus status, List<DrawSnapshot.Candidate> candidates, List<DrawSnapshotSource.Prize> prizes) {
		return new DrawSnapshot(runId, eventId, status, NOW.minusSeconds(1), DrawSnapshotService.ALGORITHM_VERSION,
				new DrawSnapshot.Rules(1, false, "NO_TICKET", "vip", 1, 3, 5, prizes), candidates);
	}
	/** 응모권 미사용 이벤트의 자격 근거와 최초 후보 식별자를 가진 테스트 후보를 구성한다. */
	private DrawSnapshot.Candidate candidate(UUID user, long weight) {
		return new DrawSnapshot.Candidate(UUID.randomUUID(), UUID.randomUUID(), user, 0, weight,
				new DrawSnapshot.EntryEvidence(1, 0, 0, 0, List.of()),
				new DrawSnapshot.EligibilityEvidence(1, user, "VIP", "USER", false, NOW.minusSeconds(1)));
	}
}
