package com.getddo.core.drawing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.*;
import com.getddo.core.drawing.repository.*;
import com.getddo.core.event.domain.*;
import com.getddo.core.user.domain.*;
import com.getddo.core.user.repository.UserRepository;

import static com.getddo.core.drawing.exception.AwardCancellationErrorCode.*;
import static com.getddo.core.drawing.exception.DrawingErrorCode.ADMIN_REQUIRED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwardCancellationRegistrationServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private final UUID actor = UUID.randomUUID(), win = UUID.randomUUID(), key = UUID.randomUUID();
	private final UUID event = UUID.randomUUID(), origin = UUID.randomUUID(), replacement = UUID.randomUUID();
	private final AwardCancellationRepository cancellations = mock(AwardCancellationRepository.class);
	private final ReplacementDrawRepository replacements = mock(ReplacementDrawRepository.class);
	private final DrawSnapshotRepository snapshots = mock(DrawSnapshotRepository.class);
	private final DrawResultRepository results = mock(DrawResultRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final AuditLogService audit = mock(AuditLogService.class);
	private final AwardCancellationRegistrationService service = new AwardCancellationRegistrationService(cancellations,
			replacements, snapshots, results, new DrawAdminAuthorization(users), audit, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));

	@BeforeEach void setUp() {
		when(users.findById(actor)).thenReturn(Optional.of(user(UserRole.ADMIN, UserStatus.ACTIVE)));
		when(replacements.findTarget(win)).thenReturn(Optional.of(new CancellationTarget(win, origin, event,
				DrawRunStatus.CONFIRMED, DrawSelectionResultType.SELECTED)));
		when(snapshots.lockEvent(event)).thenReturn(Optional.of(new DrawSnapshotSource.Event(event, EventType.NO_TICKET,
				false, NOW.minusSeconds(60), EventStatus.CLOSED, false, "vip", List.of())));
		when(results.lockSnapshot(origin)).thenReturn(Optional.of(new DrawSnapshot(origin, event, DrawRunStatus.CONFIRMED,
				NOW, "v1", null, List.of())));
		DrawResult result = new DrawResult(win, new DrawSelection(UUID.randomUUID(), 1, 1, 1,
				UUID.randomUUID(), UUID.randomUUID(), DrawSelectionResultType.SELECTED), NOW);
		when(results.findConfirmed(any())).thenReturn(new DrawConfirmation(origin, event, NOW, List.of(result)));
		when(replacements.register(event)).thenReturn(replacement);
		when(cancellations.nextId()).thenReturn(UUID.fromString("019a75ce-4d80-7000-8000-000000000001"));
		when(cancellations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test @DisplayName("관리자 사유로 취소·등록·감사를 요청하며 실제 처리자와 연결 ID를 보존한다")
	void registersAndAuditsWithoutAbuseEvidence() {
		// given / when
		AwardCancellation receipt = service.register(actor, win, key, "  관리자 사유  ");
		// then
		assertThat(receipt.id().version()).isEqualTo(7);
		assertThat(receipt.replacementRunId()).isEqualTo(replacement);
		assertThat(receipt.requestKey()).isEqualTo(key);
		assertThat(receipt.reason()).isEqualTo("관리자 사유");
		ArgumentCaptor<AuditLogCommand> command = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(audit).record(command.capture());
		assertThat(command.getValue().getActorId()).isEqualTo(actor);
		assertThat(command.getValue().getTargetId()).isEqualTo(win);
		assertThat(command.getValue().getReason()).isEqualTo("관리자 사유");
	}

	@Test @DisplayName("마감 후 1분 직전에는 당첨 취소와 재추첨을 등록하지 않는다")
	void rejectsCancellationBeforeOneMinuteBoundary() {
		// given
		when(snapshots.lockEvent(event)).thenReturn(Optional.of(new DrawSnapshotSource.Event(event, EventType.NO_TICKET,
				false, NOW.minusSeconds(60).plusNanos(1000), EventStatus.CLOSED, false, "vip", List.of())));
		// when / then
		assertCode(() -> service.register(actor, win, key, "사유"), NOT_CANCELABLE);
		verify(replacements, never()).register(any());
		verify(cancellations, never()).save(any());
		verifyNoInteractions(audit);
	}

	@Test @DisplayName("동일 키·대상·관리자·사유는 기존 취소를 반환하고 새 등록·감사를 하지 않는다")
	void replaysSameRequest() {
		// given
		AwardCancellation existing = new AwardCancellation(UUID.randomUUID(), win, replacement, actor, "사유", NOW, key);
		when(cancellations.findByRequestKey(key)).thenReturn(Optional.of(existing));
		// when / then
		assertThat(service.register(actor, win, key, " 사유 ")).isSameAs(existing);
		verifyNoInteractions(replacements, snapshots, results, audit);
		verify(cancellations, never()).save(any());
	}

	@Test @DisplayName("같은 키의 사유 또는 관리자가 다르면 충돌로 거절한다")
	void rejectsKeyPayloadAndActorConflict() {
		// given
		when(cancellations.findByRequestKey(key)).thenReturn(Optional.of(
				new AwardCancellation(UUID.randomUUID(), win, replacement, actor, "첫 사유", NOW, key)));
		// when / then
		assertCode(() -> service.register(actor, win, key, "다른 사유"), KEY_CONFLICT);
		when(cancellations.findByRequestKey(key)).thenReturn(Optional.of(
				new AwardCancellation(UUID.randomUUID(), win, replacement, UUID.randomUUID(), "첫 사유", NOW, key)));
		assertCode(() -> service.register(actor, win, key, "첫 사유"), KEY_CONFLICT);
		verifyNoInteractions(replacements, audit);
	}

	@Test @DisplayName("비관리자·비활성 관리자 요청은 대상 조회 전에 거절한다")
	void rejectsUnauthorizedActors() {
		// given / when / then
		when(users.findById(actor)).thenReturn(Optional.of(user(UserRole.USER, UserStatus.ACTIVE)));
		assertCode(() -> service.register(actor, win, key, "사유"), ADMIN_REQUIRED);
		when(users.findById(actor)).thenReturn(Optional.of(user(UserRole.ADMIN, UserStatus.INACTIVE)));
		assertCode(() -> service.register(actor, win, key, "사유"), ADMIN_REQUIRED);
		verifyNoInteractions(cancellations, replacements, audit);
	}

	@Test @DisplayName("사유·멱등 키 누락과 존재하지 않는 대상은 등록하지 않는다")
	void rejectsInvalidRequestAndMissingTarget() {
		// given / when / then
		assertCode(() -> service.register(actor, win, null, "사유"), INVALID_REQUEST);
		assertCode(() -> service.register(actor, win, key, " "), INVALID_REQUEST);
		when(replacements.findTarget(win)).thenReturn(Optional.empty());
		assertCode(() -> service.register(actor, win, key, "사유"), NOT_FOUND);
		verify(replacements, never()).register(any());
	}

	@Test @DisplayName("다른 미확정 재추첨 또는 이미 취소된 당첨이 있으면 새 취소를 거절한다")
	void rejectsPendingReplacementAndDuplicateAward() {
		// given / when / then
		when(replacements.hasPendingReplacement(event)).thenReturn(true);
		assertCode(() -> service.register(actor, win, key, "사유"), NOT_CANCELABLE);
		when(replacements.hasPendingReplacement(event)).thenReturn(false);
		when(cancellations.findByDrawResultId(win)).thenReturn(Optional.of(
				new AwardCancellation(UUID.randomUUID(), win, replacement, actor, "사유", NOW)));
		assertCode(() -> service.register(actor, win, key, "사유"), NOT_CANCELABLE);
		verify(replacements, never()).register(any());
		verify(cancellations, never()).save(any());
	}

	@Test @DisplayName("재추첨 등록 실패는 취소 저장이나 감사를 진행하지 않는다")
	void propagatesRegistrationFailure() {
		// given
		when(replacements.register(event)).thenThrow(new IllegalStateException("failure injection"));
		// when / then
		assertThatThrownBy(() -> service.register(actor, win, key, "사유")).isInstanceOf(IllegalStateException.class);
		verify(cancellations, never()).save(any());
		verifyNoInteractions(audit);
	}

	private User user(UserRole role, UserStatus status) {
		return new User(actor, "관리자", role, status, Membership.VIP, null, null, null, null, NOW, NOW);
	}
	private static void assertCode(Runnable operation, com.getddo.core.common.exception.ErrorCode code) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
