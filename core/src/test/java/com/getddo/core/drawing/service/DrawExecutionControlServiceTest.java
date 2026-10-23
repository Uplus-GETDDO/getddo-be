package com.getddo.core.drawing.service;

import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawExecutionState;
import com.getddo.core.drawing.domain.DrawFailure;
import com.getddo.core.drawing.domain.DrawFailureCode;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.repository.DrawExecutionRepository;
import com.getddo.core.drawing.repository.DrawFailureRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserRepository;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import static com.getddo.core.drawing.exception.DrawingErrorCode.NOT_EXECUTABLE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DrawExecutionControlServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private static final Instant CYCLE = NOW.minusSeconds(120);
	private final UUID runId = UUID.randomUUID(), eventId = UUID.randomUUID(), attemptId = UUID.randomUUID();
	private final DrawResultRepository results = mock(DrawResultRepository.class);
	private final DrawSnapshotRepository snapshots = mock(DrawSnapshotRepository.class);
	private final DrawExecutionRepository executions = mock(DrawExecutionRepository.class);
	private final DrawFailureRepository failures = mock(DrawFailureRepository.class);
	private final AuditLogService audit = mock(AuditLogService.class);
	private final UserRepository users = mock(UserRepository.class);
	private final DrawExecutionControlService control = new DrawExecutionControlService(results, snapshots,
			executions, failures, new DrawAdminAuthorization(users), audit, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));

	/** 고정 시계와 실행 소속·이벤트 잠금 대역을 준비해 소유권 복구와 예약 조건을 재현한다. */
	@BeforeEach void setUp() {
		when(results.findEventId(runId)).thenReturn(Optional.of(eventId));
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(EventStatus.CLOSED)));
	}

	/** 취소 이벤트의 만료 복구는 실패·대기를 확정할 값으로 반환하고 이후 거절을 분리한다. */
	@Test @DisplayName("취소 이벤트의 만료 복구는 실패·대기를 확정할 값으로 반환하고 이후 거절을 분리한다")
	void preservesRecoveryBeforeRejectingCanceledEvent() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(EventStatus.CANCELED)));
		DrawExecutionState running = state(DrawRunStatus.RUNNING, attemptId, NOW, null);
		DrawExecutionState failed = state(DrawRunStatus.FAILED, null, null, null);
		when(executions.lockRun(runId)).thenReturn(Optional.of(running), Optional.of(failed), Optional.of(failed));
		when(failures.countSince(runId, CYCLE)).thenReturn(1L);
		// when
		DrawExecutionControlService.Claim claim = control.claim(runId, null, false, false);
		// then
		assertThat(claim.isClaimed()).isFalse();
		assertThat(claim.getState().getStatus()).isEqualTo(DrawRunStatus.FAILED);
		ArgumentCaptor<DrawFailure> saved = ArgumentCaptor.forClass(DrawFailure.class);
		verify(failures).append(saved.capture());
		assertThat(saved.getValue().getId()).isEqualTo(attemptId);
		assertThat(saved.getValue().getFailureCode()).isEqualTo("INTERRUPTED");
		verify(executions).fail(runId, NOW.plusSeconds(10));
		verify(executions).fail(runId, null);
		verify(executions, never()).claim(any(), any(), any(), any(), any());
		verifyNoInteractions(audit);
	}

	/** 운영자 만료 복구의 실행 불가 거절은 실패 기록 뒤 반환값으로 전달한다. */
	@Test @DisplayName("운영자 만료 복구의 실행 불가 거절은 실패 기록 뒤 반환값으로 전달한다")
	void returnsManualRejectionAfterRecordingRecovery() {
		// given
		UUID actorId = UUID.randomUUID();
		when(users.findById(actorId)).thenReturn(Optional.of(new User(actorId, "관리자", UserRole.ADMIN,
				UserStatus.ACTIVE, Membership.VIP, null, null, null, null, NOW, NOW)));
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(EventStatus.CANCELED)));
		DrawExecutionState running = state(DrawRunStatus.RUNNING, attemptId, NOW, null);
		DrawExecutionState failed = state(DrawRunStatus.FAILED, null, null, null);
		when(executions.lockRun(runId)).thenReturn(Optional.of(running), Optional.of(failed), Optional.of(failed));
		when(failures.countSince(runId, CYCLE)).thenReturn(1L);
		// when
		DrawExecutionControlService.Claim claim = control.claim(runId, actorId, true, true);
		// then
		assertThat(claim.getRejectionCode()).isSameAs(NOT_EXECUTABLE);
		verify(failures).append(argThat(failure -> failure.getId().equals(attemptId)));
		verify(executions).fail(runId, null);
		verify(executions, never()).claim(any(), any(), any(), any(), any());
	}

	/** 만료 복구 후 같은 트랜잭션에서 새 시도를 시작하지 않는다. */
	@Test @DisplayName("만료 복구 후 같은 트랜잭션에서 새 시도를 시작하지 않는다")
	void recoveryDoesNotStartAnotherAttempt() {
		// given
		DrawExecutionState running = state(DrawRunStatus.RUNNING, attemptId, NOW, null);
		DrawExecutionState failed = state(DrawRunStatus.FAILED, null, null, NOW.plusSeconds(10));
		when(executions.lockRun(runId)).thenReturn(Optional.of(running), Optional.of(failed));
		when(failures.countSince(runId, CYCLE)).thenReturn(1L);
		// when
		DrawExecutionControlService.Claim claim = control.claim(runId, null, false, false);
		// then
		assertThat(claim.isClaimed()).isFalse();
		assertThat(claim.getState().getRetryAt()).isEqualTo(NOW.plusSeconds(10));
		verify(executions, never()).claim(any(), any(), any(), any(), any());
	}

	/** 취소된 이벤트의 만기 예약을 해제하되 실제 새 실패를 만들지 않는다. */
	@Test @DisplayName("취소된 이벤트의 만기 예약을 해제하되 실제 새 실패를 만들지 않는다")
	void removesCanceledRetryFromQueue() {
		// given
		when(snapshots.lockEvent(eventId)).thenReturn(Optional.of(event(EventStatus.CANCELED)));
		when(executions.lockRun(runId)).thenReturn(Optional.of(state(DrawRunStatus.FAILED, null, null, NOW)),
				Optional.of(state(DrawRunStatus.FAILED, null, null, null)));
		// when
		DrawExecutionControlService.Claim claim = control.claim(runId, null, false, false);
		// then
		assertThat(claim.getState().getRetryAt()).isNull();
		verify(executions).fail(runId, null);
		verifyNoInteractions(failures, audit);
	}

	/** 운영자 대기를 일반 실행으로 해제하지 않는다. */
	@Test @DisplayName("운영자 대기를 일반 실행으로 해제하지 않는다")
	void doesNotBypassOperatorWaiting() {
		// given
		when(executions.lockRun(runId)).thenReturn(Optional.of(state(DrawRunStatus.FAILED, null, null, null)));
		// when
		DrawExecutionControlService.Claim claim = control.claim(runId, null, false, false);
		// then
		assertThat(claim.isClaimed()).isFalse();
		verify(executions, never()).claim(any(), any(), any(), any(), any());
		verifyNoInteractions(failures, audit);
	}

	/** 실패 이력의 네 번째 실패부터 자동 예약 없이 운영자 대기로 전환한다. */
	@ParameterizedTest @CsvSource({"1,10", "2,30", "3,60", "4,-1"})
	@DisplayName("실패 이력의 네 번째 실패부터 자동 예약 없이 운영자 대기로 전환한다")
	void limitsAutomaticRetries(long failureCount, long delay) {
		// given
		when(executions.lockRun(runId)).thenReturn(Optional.of(state(DrawRunStatus.RUNNING, attemptId, NOW.plusSeconds(30), null)),
				Optional.of(state(DrawRunStatus.FAILED, null, null, null)));
		when(failures.countSince(runId, CYCLE)).thenReturn(failureCount);
		// when
		control.recordFailure(runId, attemptId, DrawFailureCode.TRANSIENT_STORAGE, NOW);
		// then
		verify(executions).fail(runId, delay < 0 ? null : NOW.plusSeconds(delay));
		verify(failures).append(argThat(failure -> failure.getId().equals(attemptId) && failure.getRunId().equals(runId)));
	}

	/** 자동 재시도 불가 오류는 실패 이력을 남기고 예약 없이 운영자 확인을 기다린다. */
	@ParameterizedTest
	@EnumSource(value = DrawFailureCode.class,
			names = {"INVALID_EVIDENCE", "INVALID_RUN", "NOT_EXECUTABLE", "INTERNAL_ERROR"})
	@DisplayName("정합성·실행 불가·미분류 오류는 자동 재시도 없이 관리자 확인을 기다린다")
	void nonRetryableFailureWaitsForOperator(DrawFailureCode code) {
		// given
		when(executions.lockRun(runId)).thenReturn(
				Optional.of(state(DrawRunStatus.RUNNING, attemptId, NOW.plusSeconds(30), null)),
				Optional.of(state(DrawRunStatus.FAILED, null, null, null)));

		// when
		control.recordFailure(runId, attemptId, code, NOW);

		// then
		verify(failures).append(argThat(failure -> failure.getRunId().equals(runId)
				&& failure.getFailureCode().equals(code.name())));
		verify(executions).fail(runId, null);
		verify(failures, never()).countSince(any(), any());
		verify(executions, never()).claim(any(), any(), any(), any(), any());
		verifyNoInteractions(audit);
	}

	/** 이미 저장된 같은 실패 ID는 이력과 예약 횟수를 다시 증가시키지 않는다. */
	@Test @DisplayName("이미 저장된 같은 실패 ID는 이력과 예약 횟수를 다시 증가시키지 않는다")
	void replaysSameFailureWithoutCountingAgain() {
		// given
		when(executions.lockRun(runId)).thenReturn(Optional.of(state(DrawRunStatus.FAILED, null, null, null)));
		when(failures.findById(attemptId)).thenReturn(Optional.of(new DrawFailure(attemptId, runId, "INVALID_RUN", NOW, null)));
		// when
		control.recordFailure(runId, attemptId, DrawFailureCode.INVALID_RUN, NOW);
		// then
		verify(failures, never()).append(any());
		verify(executions, never()).fail(any(), any());
	}

	/** 복구·예약 경계를 검증할 상태와 시도 토큰·임대·재시도 시각을 구성한다. */
	private DrawExecutionState state(DrawRunStatus status, UUID token, Instant lease, Instant retry) {
		return new DrawExecutionState(runId, eventId, DrawType.INITIAL, status, NOW.minusSeconds(300), token, lease, retry, CYCLE);
	}
	/** 마감 경계를 지난 이벤트를 주어진 상태로 구성한다. */
	private DrawSnapshotSource.Event event(EventStatus status) {
		return new DrawSnapshotSource.Event(eventId, EventType.NO_TICKET, false, NOW.minusSeconds(300), status, false, "VIP", List.of());
	}
}
