package com.getddo.core.event.service;

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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.notification.service.EventChangeNotificationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventDeletionServiceTest {
	private static final UUID ADMIN = UUID.randomUUID();
	private static final UUID EVENT = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-13T00:00:00.123456789Z");
	private static final Instant START = NOW.plusSeconds(3600);
	private EventRepository events;
	private EventActorRepository actors;
	private EventChangeNotificationService notifications;
	private AuditLogService auditLogs;
	private EventDeletionService service;

	@BeforeEach
	void setUp() {
		events = mock(EventRepository.class);
		actors = mock(EventActorRepository.class);
		notifications = mock(EventChangeNotificationService.class);
		auditLogs = mock(AuditLogService.class);
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(true, true)));
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(saved(EventStatus.SCHEDULED, START)));
		service = new EventDeletionService(events, actors, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), notifications, auditLogs);
	}

	@Test
	@DisplayName("이벤트 잠금과 응모 이력 확인 후 논리 삭제·알림 중지·변경 전후 감사 기록을 처리한다")
	void deletesAfterLockAndHistoryCheck() {
		// given / when
		service.delete(EVENT, ADMIN);
		// then
		InOrder order = inOrder(events, notifications, auditLogs);
		order.verify(events).findByIdForUpdate(EVENT);
		order.verify(events).hasParticipationHistory(EVENT);
		order.verify(events).softDelete(EVENT, Instant.parse("2026-10-13T00:00:00.123456Z"));
		order.verify(notifications).stopForDeletedEvent(EVENT);
		ArgumentCaptor<AuditLogCommand> command = ArgumentCaptor.forClass(AuditLogCommand.class);
		order.verify(auditLogs).record(command.capture());
		assertThat(command.getValue().getActorId()).isEqualTo(ADMIN);
		assertThat(command.getValue().getAction()).isEqualTo("EVENT_DELETE");
		assertThat(command.getValue().getTargetType()).isEqualTo("EVENT");
		assertThat(command.getValue().getTargetId()).isEqualTo(EVENT);
		assertThat(command.getValue().getBeforeData()).hasSize(1).containsEntry("deletedAt", null);
		assertThat(command.getValue().getAfterData()).hasSize(1).containsEntry("deletedAt", "2026-10-13T00:00:00.123456Z");
		assertThat(command.getValue().getReason()).isNull();
		assertThat(command.getValue().getRequestId()).isNull();
	}

	@Test
	@DisplayName("취소 상태라도 시작 전이고 응모 이력이 없으면 삭제할 수 있다")
	void cancellationBeforeStartDoesNotPreventDeletion() {
		// given
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(saved(EventStatus.CANCELED, START)));
		// when
		service.delete(EVENT, ADMIN);
		// then
		verify(events).softDelete(EVENT, NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	@DisplayName("시작 시각과 같거나 시작 이후이면 오래된 진행 예정 상태라도 삭제하지 않는다")
	void rejectsStartedEvents(long offset) {
		// given
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(saved(EventStatus.SCHEDULED, NOW.plusSeconds(offset))));
		// when / then
		assertError(EventErrorCode.DELETION_NOT_ALLOWED, EVENT, ADMIN);
		verify(events, never()).softDelete(any(), any());
	}

	@Test
	@DisplayName("잠금 대기 중 시작 시각에 도달하면 잠금 획득 후 시각으로 삭제를 거절한다")
	void checksTimeAfterLockAcquisition() {
		// given
		Clock clock = mock(Clock.class);
		when(clock.instant()).thenReturn(NOW);
		when(events.findByIdForUpdate(EVENT)).thenAnswer(invocation -> {
			when(clock.instant()).thenReturn(START);
			return Optional.of(saved(EventStatus.SCHEDULED, START));
		});
		service = new EventDeletionService(events, actors, new TimeProvider(clock), notifications, auditLogs);
		// when / then
		assertError(EventErrorCode.DELETION_NOT_ALLOWED, EVENT, ADMIN);
		verify(events, never()).softDelete(any(), any());
	}

	@Test
	@DisplayName("시작 전이라도 접수된 응모 이력이 남아 있으면 삭제하지 않는다")
	void rejectsParticipationHistory() {
		// given
		when(events.hasParticipationHistory(EVENT)).thenReturn(true);
		// when / then
		assertError(EventErrorCode.DELETION_NOT_ALLOWED, EVENT, ADMIN);
		verify(events, never()).softDelete(any(), any());
	}

	@Test
	@DisplayName("없는 이벤트·이미 삭제된 이벤트·null 식별자는 찾을 수 없음으로 처리한다")
	void rejectsMissingEvents() {
		// given
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.empty());
		// when / then
		assertError(EventErrorCode.EVENT_NOT_FOUND, EVENT, ADMIN);
		assertError(EventErrorCode.EVENT_NOT_FOUND, null, ADMIN);
		verify(events, never()).softDelete(any(), any());
	}

	@Test
	@DisplayName("미등록·일반·비활성 사용자는 이벤트 잠금·삭제 전에 거절한다")
	void rejectsUnauthorizedActors() {
		// given / when / then
		assertError(EventErrorCode.USER_CONTEXT_REQUIRED, EVENT, null);
		when(actors.findById(ADMIN)).thenReturn(Optional.empty());
		assertError(EventErrorCode.USER_CONTEXT_REQUIRED, EVENT, ADMIN);
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(true, false)));
		assertError(EventErrorCode.ADMIN_REQUIRED, EVENT, ADMIN);
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(false, true)));
		assertError(EventErrorCode.ADMIN_REQUIRED, EVENT, ADMIN);
		verify(events, never()).findByIdForUpdate(any());
		verify(events, never()).softDelete(any(), any());
	}

	private void assertError(EventErrorCode error, UUID eventId, UUID actorId) {
		assertThatThrownBy(() -> service.delete(eventId, actorId)).isInstanceOf(EventException.class)
				.satisfies(exception -> assertThat(((EventException) exception).getErrorCode()).isEqualTo(error));
		verifyNoInteractions(notifications, auditLogs);
	}

	private RegisteredEvent saved(EventStatus status, Instant start) {
		return new RegisteredEvent(EVENT, "삭제 이벤트", "설명", null, null, EventType.NO_TICKET, false, null,
				MembershipRule.vip, start, START.plusSeconds(3600), status, NOW, NOW, List.of());
	}
}
