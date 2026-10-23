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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventRepository;
import com.getddo.core.notification.domain.EventNotificationChange;
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

class EventModificationServiceTest {
	private static final UUID ADMIN = UUID.randomUUID();
	private static final UUID EVENT = UUID.randomUUID();
	private static final UUID PRIZE = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
	private static final Instant START = NOW.plusSeconds(3600);
	private EventRepository events;
	private EventActorRepository actors;
	private EventChangeNotificationService notifications;
	private EventModificationService service;
	private AuditLogService auditLogs;

	@BeforeEach
	void setUp() {
		events = mock(EventRepository.class);
		actors = mock(EventActorRepository.class);
		notifications = mock(EventChangeNotificationService.class);
		auditLogs = mock(AuditLogService.class);
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(true, true)));
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(saved(EventStatus.SCHEDULED, START, "기존")));
		service = new EventModificationService(events, actors, notifications, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), auditLogs);
	}

	@Test
	@DisplayName("시작 연기는 저장 후 같은 업무 흐름에서 원본·변경 시각과 제목으로 알림을 등록한다")
	void savesBeforeRegisteringScheduleNotification() {
		// given
		EventRegistration input = settings(START.plusSeconds(600), "변경", PRIZE);
		when(events.update(EVENT, input)).thenReturn(saved(EventStatus.SCHEDULED, input.getStartsAt(), input.getTitle()));
		// when
		RegisteredEvent result = service.modify(EVENT, input);
		// then
		InOrder order = inOrder(events, notifications);
		order.verify(events).findByIdForUpdate(EVENT);
		order.verify(events).update(EVENT, input);
		ArgumentCaptor<EventNotificationChange> change = ArgumentCaptor.forClass(EventNotificationChange.class);
		order.verify(notifications).register(change.capture());
		assertThat(result.getTitle()).isEqualTo("변경");
		assertThat(change.getValue().getEventId()).isEqualTo(EVENT);
		assertThat(change.getValue().getPreviousStartsAt()).isEqualTo(START);
		assertThat(change.getValue().getStartsAt()).isEqualTo(input.getStartsAt());
		assertThat(change.getValue().getOccurredAt()).isEqualTo(NOW);
		assertThat(change.getValue().getOccurrenceId()).isNotNull();
	}

	@Test
	@DisplayName("제목만 바뀌면 저장하지만 일정 변경 알림은 만들지 않는다")
	void sameStartDoesNotRegisterScheduleNotification() {
		// given
		EventRegistration input = settings(START, "변경", PRIZE);
		when(events.update(EVENT, input)).thenReturn(saved(EventStatus.SCHEDULED, START, "변경"));
		// when
		service.modify(EVENT, input);
		// then
		verify(events).update(EVENT, input);
		verifyNoInteractions(notifications);
	}

	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"OPEN", "CLOSED", "DRAW_CONFIRMED", "PUBLISHED", "CANCELED", "REDRAWING", "NO_ENTRANTS"})
	@DisplayName("진행 예정 이외 상태에서는 시작 시각 전이어도 수정하지 않는다")
	void rejectsNonScheduledEvents(EventStatus status) {
		// given
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(saved(status, START, "기존")));
		// when / then
		assertError(EventErrorCode.MODIFICATION_NOT_ALLOWED, settings(START, "변경", PRIZE));
		verify(events, never()).update(any(), any());
		verifyNoInteractions(notifications);
	}

	@Test
	@DisplayName("잠금을 얻은 시점에 시작 시간이 지났으면 오래된 SCHEDULED 상태도 수정하지 않는다")
	void checksTimeAfterLockAcquisition() {
		// given
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.of(saved(EventStatus.SCHEDULED, NOW, "기존")));
		// when / then
		assertError(EventErrorCode.MODIFICATION_NOT_ALLOWED, settings(START, "변경", PRIZE));
		verify(events, never()).update(any(), any());
		verifyNoInteractions(notifications);
	}

	@Test
	@DisplayName("기존 시작 시각보다 앞당기는 요청은 거절한다")
	void rejectsEarlierStart() {
		// given / when / then
		assertError(EventErrorCode.START_CANNOT_ADVANCE, settings(START.minusSeconds(1), "변경", PRIZE));
		verify(events, never()).update(any(), any());
		verifyNoInteractions(notifications);
	}

	@Test
	@DisplayName("현재 이벤트에 없는 경품 ID는 새 경품으로 취급하지 않고 거절한다")
	void rejectsUnknownPrizeId() {
		// given / when / then
		assertError(EventErrorCode.INVALID_PRIZES, settings(START, "변경", UUID.randomUUID()));
		verify(events, never()).update(any(), any());
		verifyNoInteractions(notifications);
	}

	@Test
	@DisplayName("기존 경품 ID와 전체 설정이 같은 PUT 재요청은 저장·알림을 반복하지 않는다")
	void identicalPutIsANoOp() {
		// given / when
		RegisteredEvent first = service.modify(EVENT, settings(START, "기존", PRIZE));
		RegisteredEvent repeated = service.modify(EVENT, settings(START, "기존", PRIZE));
		// then
		assertThat(repeated).isEqualTo(first);
		verify(events, never()).update(any(), any());
		verifyNoInteractions(notifications);
	}

	@ParameterizedTest
	@ValueSource(strings = {"기존", "변경"})
	@DisplayName("ID 없는 경품은 내용과 제목 변경 여부에 관계없이 새 경품으로 저장한다")
	void prizeWithoutIdAlwaysRequiresUpdate(String title) {
		// given
		EventRegistration input = settings(START, title, null);
		RegisteredEvent updated = saved(EventStatus.SCHEDULED, START, title);
		when(events.update(EVENT, input)).thenReturn(updated);
		// when
		RegisteredEvent result = service.modify(EVENT, input);
		// then
		assertThat(result).isSameAs(updated);
		verify(events).update(EVENT, input);
		verifyNoInteractions(notifications);
	}

	@Test
	@DisplayName("중복 경품 ID는 등수가 서로 달라도 거절한다")
	void rejectsDuplicatePrizeIds() {
		// given
		EventRegistration input = new EventRegistration(ADMIN, "변경", "설명", null, null, EventType.NO_TICKET, false,
				null, MembershipRule.vip, START, START.plusSeconds(3600), List.of(
					new EventRegistration.Prize(PRIZE, 1, "경품", null, null, 1),
					new EventRegistration.Prize(PRIZE, 2, "경품", null, null, 1)));
		// when / then
		assertError(EventErrorCode.INVALID_PRIZES, input);
		verify(events, never()).update(any(), any());
		verifyNoInteractions(notifications);
	}

	@Test
	@DisplayName("미등록·비활성·일반 사용자와 없는 이벤트는 수정할 수 없다")
	void rejectsUnauthorizedAndMissingEvent() {
		// given / when / then
		when(actors.findById(ADMIN)).thenReturn(Optional.empty());
		assertError(EventErrorCode.USER_CONTEXT_REQUIRED, settings(START, "변경", PRIZE));
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(true, false)));
		assertError(EventErrorCode.ADMIN_REQUIRED, settings(START, "변경", PRIZE));
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(false, true)));
		assertError(EventErrorCode.ADMIN_REQUIRED, settings(START, "변경", PRIZE));
		when(actors.findById(ADMIN)).thenReturn(Optional.of(new EventActorRepository.Actor(true, true)));
		when(events.findByIdForUpdate(EVENT)).thenReturn(Optional.empty());
		assertError(EventErrorCode.EVENT_NOT_FOUND, settings(START, "변경", PRIZE));
		verifyNoInteractions(notifications);
	}

	private void assertError(EventErrorCode error, EventRegistration input) {
		assertThatThrownBy(() -> service.modify(EVENT, input)).isInstanceOf(EventException.class)
				.satisfies(exception -> assertThat(((EventException) exception).getErrorCode()).isEqualTo(error));
		verifyNoInteractions(auditLogs);
	}

	private EventRegistration settings(Instant start, String title, UUID prizeId) {
		return new EventRegistration(ADMIN, title, "설명", null, null, EventType.NO_TICKET, false, null, MembershipRule.vip,
				start, START.plusSeconds(3600), List.of(new EventRegistration.Prize(prizeId, 1, "경품", null, null, 1)));
	}

	private RegisteredEvent saved(EventStatus status, Instant start, String title) {
		return new RegisteredEvent(EVENT, title, "설명", null, null, EventType.NO_TICKET, false, null, MembershipRule.vip,
				start, START.plusSeconds(3600), status, NOW, NOW,
				List.of(new RegisteredEvent.Prize(PRIZE, 1, "경품", null, null, 1)));
	}
}
