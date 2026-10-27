package com.getddo.core.event.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventCancellationResult;
import com.getddo.core.event.domain.EventCancellationState;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.notification.domain.EventNotificationChange;
import com.getddo.core.notification.service.EventChangeNotificationService;
import com.getddo.core.ticket.service.TicketBulkRefundService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventCancellationServiceTest {
	private static final UUID ADMIN = UUID.randomUUID();
	private static final UUID EVENT = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00.123456789Z");
	private EventRepository events;
	private EventActorRepository actors;
	private TicketBulkRefundService refunds;
	private EventChangeNotificationService notifications;
	private AuditLogService auditLogs;
	private EventCancellationService service;

	@BeforeEach
	void setUp() {
		events = mock(EventRepository.class);
		actors = mock(EventActorRepository.class);
		refunds = mock(TicketBulkRefundService.class);
		notifications = mock(EventChangeNotificationService.class);
		auditLogs = mock(AuditLogService.class);
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(true, true)));
		when(events.findCancellationByIdForUpdate(EVENT))
				.thenReturn(Optional.of(new EventCancellationState("취소 이벤트", EventStatus.OPEN)));
		when(events.findChargedEntryIds(EVENT)).thenReturn(List.of());
		service = new EventCancellationService(events, actors, refunds, notifications, auditLogs,
				new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
	}

	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"SCHEDULED", "OPEN", "CLOSED", "DRAW_CONFIRMED", "PUBLISHED", "REDRAWING", "NO_ENTRANTS"})
	@DisplayName("취소 전 상태나 시작 여부와 관계없이 취소하고 최초 처리 상태·시각을 반환한다")
	void allowsEveryNonCanceledStatus(EventStatus status) {
		// given
		when(events.findCancellationByIdForUpdate(EVENT)).thenReturn(Optional.of(new EventCancellationState("취소 이벤트", status)));
		// when
		EventCancellationResult result = service.cancel(EVENT, ADMIN, "운영 취소");
		// then
		assertThat(result.getPreviousStatus()).isEqualTo(status);
		assertThat(result.getProcessedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
		assertThat(result.getRefundedTicketCount()).isZero();
		verify(events).markCanceled(EVENT, result.getProcessedAt());
	}

	@Test
	@DisplayName("응모별 실제 반환 수량을 합산하고 취소 사유·변경 전후 상태를 감사 기록에 전달한다")
	void sumsRefundsAndRecordsReason() {
		// given
		List<UUID> entryIds = List.of(UUID.randomUUID(), UUID.randomUUID());
		when(events.findChargedEntryIds(EVENT)).thenReturn(entryIds);
		when(refunds.refundAll(entryIds, "운영 취소")).thenReturn(2L);
		// when
		EventCancellationResult result = service.cancel(EVENT, ADMIN, "운영 취소");
		// then
		assertThat(result.getRefundedTicketCount()).isEqualTo(2);
		verify(refunds).refundAll(entryIds, "운영 취소");
		ArgumentCaptor<AuditLogCommand> audit = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(audit.capture());
		assertThat(audit.getValue().getReason()).isEqualTo("운영 취소");
		assertThat(audit.getValue().getBeforeData()).containsEntry("status", "OPEN");
		assertThat(audit.getValue().getAfterData()).containsEntry("status", "CANCELED").containsEntry("refundedTicketCount", 2L);
		ArgumentCaptor<EventNotificationChange> notice = ArgumentCaptor.forClass(EventNotificationChange.class);
		verify(notifications).register(notice.capture());
		assertThat(notice.getValue().getEventId()).isEqualTo(EVENT);
	}

	@Test
	@DisplayName("이미 취소되었으면 409로 거절하고 반환·알림·감사를 다시 실행하지 않는다")
	void rejectsRepeatedCancellation() {
		// given
		when(events.findCancellationByIdForUpdate(EVENT))
				.thenReturn(Optional.of(new EventCancellationState("취소 이벤트", EventStatus.CANCELED)));
		// when / then
		assertError(EventErrorCode.ALREADY_CANCELED, EVENT, ADMIN, "취소");
		verify(events, never()).markCanceled(any(), any());
		verifyNoInteractions(refunds, notifications, auditLogs);
	}

	@Test
	@DisplayName("관리자 권한이 없거나 비활성이면 이벤트 잠금·저장·반환을 시작하지 않는다")
	void rejectsUnauthorizedActor() {
		// given / when / then
		assertError(EventErrorCode.USER_CONTEXT_REQUIRED, EVENT, null, "취소");
		for (EventActorRepository.Actor actor : List.of(new EventActorRepository.Actor(false, true),
				new EventActorRepository.Actor(true, false))) {
			when(actors.findById(ADMIN)).thenReturn(Optional.of(actor));
			assertError(EventErrorCode.ADMIN_REQUIRED, EVENT, ADMIN, "취소");
		}
		verifyNoInteractions(events, refunds, notifications, auditLogs);
	}

	@Test
	@DisplayName("사유의 공백·TEXT 바이트 초과는 잠금 전에 거절하고 없는 이벤트도 변경하지 않는다")
	void rejectsInvalidReasonAndMissingEvent() {
		// given / when / then
		for (String reason : new String[]{null, " ", "가".repeat(21_846), "😀".repeat(16_384)}) {
			assertError(EventErrorCode.INVALID_CANCELLATION_REASON, EVENT, ADMIN, reason);
		}
		verifyNoInteractions(events);
		when(events.findCancellationByIdForUpdate(EVENT)).thenReturn(Optional.empty());
		assertError(EventErrorCode.EVENT_NOT_FOUND, EVENT, ADMIN, "취소");
		assertError(EventErrorCode.EVENT_NOT_FOUND, null, ADMIN, "취소");
		verify(events, never()).markCanceled(any(), any());
		verifyNoInteractions(refunds, notifications, auditLogs);
	}

	private void assertError(EventErrorCode expected, UUID eventId, UUID actorId, String reason) {
		assertThatThrownBy(() -> service.cancel(eventId, actorId, reason)).isInstanceOfSatisfying(EventException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(expected));
	}
}
