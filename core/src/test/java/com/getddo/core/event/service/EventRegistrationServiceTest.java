package com.getddo.core.event.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.audit.domain.AuditLogCommand;
import org.mockito.ArgumentCaptor;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.repository.EventActorRepository;
import com.getddo.core.event.repository.EventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventRegistrationServiceTest {
	private static final UUID ADMIN_ID = UUID.fromString("0199abcd-1234-7000-8000-000000000001");
	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
	private EventRepository events;
	private EventActorRepository actors;
	private EventRegistrationService service;
	private AuditLogService auditLogs;

	@BeforeEach
	void setUp() {
		events = mock(EventRepository.class);
		actors = mock(EventActorRepository.class);
		auditLogs = mock(AuditLogService.class);
		when(events.create(any(), any())).thenAnswer(invocation -> {
			EventRegistration input = invocation.getArgument(0);
			return new RegisteredEvent(UUID.randomUUID(), input.getTitle(), input.getDescription(), input.getImageKey(), input.getDetailImageKey(),
					input.getEventType(), input.isWeightingEnabled(), input.getMaxTicketsPerUser(), input.getMembershipRule(),
					input.getStartsAt(), input.getEndsAt(), invocation.getArgument(1), NOW, NOW,
					input.getPrizes().stream().map(prize -> new RegisteredEvent.Prize(UUID.randomUUID(), prize.getRank(),
							prize.getName(), prize.getDescription(), prize.getImageKey(), prize.getWinnerCount())).toList());
		});
		when(actors.findById(ADMIN_ID)).thenReturn(Optional.of(new EventActorRepository.Actor(true, true)));
		service = new EventRegistrationService(events, actors,
				new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), auditLogs);
	}

	@Test
	@DisplayName("정상 등록은 저장된 이벤트·경품과 요청 관리자를 생성 감사 명령에 담는다")
	void validRegistrationPassesScheduledEventAndAllPrizesToRepository() {
		// given
		EventRegistration registration = validRegistration();
		// when
		RegisteredEvent saved = service.register(registration);
		// then
		verify(events).create(registration, EventStatus.SCHEDULED);
		ArgumentCaptor<AuditLogCommand> commandCaptor = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(commandCaptor.capture());
		AuditLogCommand command = commandCaptor.getValue();
		assertThat(command.getActorId()).isEqualTo(ADMIN_ID);
		assertThat(command.getAction()).isEqualTo("EVENT_CREATE");
		assertThat(command.getTargetType()).isEqualTo("EVENT");
		assertThat(command.getTargetId()).isEqualTo(saved.getId());
		assertThat(command.getBeforeData()).isNull();
		assertThat(command.getAfterData())
				.containsEntry("title", "가을 이벤트").containsEntry("description", "경품 응모")
				.containsEntry("eventType", "TICKET").containsEntry("weightingEnabled", true)
				.containsEntry("maxTicketsPerUser", 5).containsEntry("membershipRule", "vip")
				.containsEntry("startsAt", NOW.plusSeconds(3600).toString())
				.containsEntry("endsAt", NOW.plusSeconds(7200).toString())
				.containsEntry("status", "SCHEDULED").containsEntry("imageKey", null);
		List<?> prizes = (List<?>) command.getAfterData().get("prizes");
		assertThat(prizes).hasSize(2);
		Map<?, ?> prize = (Map<?, ?>) prizes.getFirst();
		assertThat(prize.get("id")).isEqualTo(saved.getPrizes().getFirst().getId().toString());
		assertThat(prize.get("rank")).isEqualTo(1);
		assertThat(prize.get("name")).isEqualTo("경품 A");
		assertThat(prize.get("winnerCount")).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {500, 501})
	@DisplayName("상세 이미지 키는 500자까지 저장·감사에 전달하고 초과하면 저장 전에 거절한다")
	void validatesAndPreservesDetailImageKey(int length) {
		// given
		EventRegistration source = validRegistration();
		EventRegistration input = new EventRegistration(source.getActorId(), source.getTitle(), source.getDescription(),
				source.getImageKey(), "x".repeat(length), source.getEventType(), source.isWeightingEnabled(),
				source.getMaxTicketsPerUser(), source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(), source.getPrizes());
		// when / then
		if (length > 500) {
			assertThatThrownBy(() -> service.register(input)).isInstanceOfSatisfying(EventException.class,
					error -> assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.INVALID_DETAILS));
			verifyNoInteractions(events, auditLogs);
			return;
		}
		RegisteredEvent saved = service.register(input);
		assertThat(saved.getDetailImageKey()).isEqualTo(input.getDetailImageKey());
		ArgumentCaptor<AuditLogCommand> command = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(command.capture());
		assertThat(command.getValue().getAfterData()).containsEntry("detailImageKey", input.getDetailImageKey());
	}

	@Test
	void pastStartUsesOpenOrClosedAccordingToCurrentTime() {
		EventRegistration source = validRegistration();
		EventRegistration open = withPeriod(source, NOW.minusSeconds(1), NOW.plusSeconds(1));
		EventRegistration closed = withPeriod(source, NOW.minusSeconds(2), NOW);

		service.register(open);
		service.register(closed);

		verify(events).create(open, EventStatus.OPEN);
		verify(events).create(closed, EventStatus.CLOSED);
	}

	@ParameterizedTest
	@CsvSource({
			"0999-12-31T23:59:59.999999Z,1000-01-01T00:00:00Z",
			"9999-12-31T23:59:59Z,9999-12-31T23:59:59.500000Z",
			"9999-12-31T23:59:59Z,+10000-01-01T00:00:00Z",
			"+10000-01-01T00:00:00Z,+10000-01-02T00:00:00Z",
			"2099-10-01T00:00:00.000000001Z,2099-10-01T00:00:00.000000002Z",
			"2099-10-01T00:00:00.000000501Z,2099-10-01T00:00:00.000000999Z"
	})
	@DisplayName("저장 지원 범위를 벗어나거나 마이크로초 기준으로 같아지는 기간은 저장 전에 거절한다")
	void rejectsUnstorablePeriodsBeforeAnyWrite(String startsAt, String endsAt) {
		// given
		EventRegistration registration = withPeriod(validRegistration(), Instant.parse(startsAt), Instant.parse(endsAt));

		// when / then
		assertThatThrownBy(() -> service.register(registration))
				.isInstanceOfSatisfying(EventException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.INVALID_PERIOD));
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@CsvSource({
			"1000-01-01T00:00:00Z,1000-01-01T00:00:00.000001Z,CLOSED",
			"9999-12-31T23:59:59.499998Z,9999-12-31T23:59:59.499999Z,SCHEDULED"
	})
	@DisplayName("이벤트 시각 저장 지원 범위의 양 끝과 1마이크로초 기간을 허용한다")
	void acceptsSupportedPeriodBoundaries(String startsAt, String endsAt, EventStatus status) {
		// given
		EventRegistration registration = withPeriod(validRegistration(), Instant.parse(startsAt), Instant.parse(endsAt));

		// when
		service.register(registration);

		// then
		verify(events).create(registration, status);
	}

	@Test
	@DisplayName("마이크로초 미만을 버린 기간으로 저장하고 초기 상태를 판정한다")
	void usesNormalizedPeriodForStorageAndInitialStatus() {
		// given: 원본 종료는 현재보다 늦지만 저장 가능한 종료는 현재와 같다.
		EventRegistration registration = withPeriod(validRegistration(), NOW.minusSeconds(1).plusNanos(123),
				NOW.plusNanos(999));
		EventRegistration normalized = withPeriod(registration, NOW.minusSeconds(1), NOW);

		// when
		service.register(registration);

		// then
		verify(events).create(normalized, EventStatus.CLOSED);
	}

	@Test
	void noTicketEventUsesNoWeightingAndNoTicketLimit() {
		EventRegistration source = validRegistration();
		EventRegistration noTicket = new EventRegistration(source.getActorId(), source.getTitle(),
				source.getDescription(), source.getImageKey(), source.getDetailImageKey(), EventType.NO_TICKET, false,
				null, source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(), source.getPrizes());

		service.register(noTicket);

		verify(events).create(noTicket, EventStatus.SCHEDULED);
	}

	@Test
	void adminCanRegisterUnlimitedWeightedEventRegardlessOfPeriod() {
		EventRegistration source = validRegistration();
		EventRegistration unlimited = new EventRegistration(source.getActorId(), source.getTitle(),
				source.getDescription(), source.getImageKey(), source.getDetailImageKey(), EventType.TICKET, true,
				null, source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(), source.getPrizes());

		service.register(unlimited);

		verify(events).create(unlimited, EventStatus.SCHEDULED);
	}

	@Test
	@DisplayName("등록 요청에 기존 경품 ID가 있으면 Service가 저장 전에 거절한다")
	void existingPrizeIdFailsBeforeAnyWrite() {
		// given
		EventRegistration source = validRegistration();
		EventRegistration invalid = new EventRegistration(source.getActorId(), source.getTitle(),
				source.getDescription(), source.getImageKey(), source.getDetailImageKey(), source.getEventType(), source.isWeightingEnabled(),
				source.getMaxTicketsPerUser(), source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(),
				List.of(new EventRegistration.Prize(UUID.randomUUID(), 1, "기존 경품", null, null, 1)));
		// when / then
		assertThatThrownBy(() -> service.register(invalid))
				.isInstanceOfSatisfying(EventException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.INVALID_PRIZES));
		verifyNoInteractions(events);
	}

	@Test
	void duplicatePrizeRankFailsBeforeAnyWrite() {
		EventRegistration source = validRegistration();
		EventRegistration duplicate = new EventRegistration(source.getActorId(), source.getTitle(),
				source.getDescription(), source.getImageKey(), source.getDetailImageKey(), source.getEventType(), source.isWeightingEnabled(),
				source.getMaxTicketsPerUser(), source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(),
				List.of(source.getPrizes().getFirst(),
						new EventRegistration.Prize(1, "다른 경품", null, null, 2)));

		assertThatThrownBy(() -> service.register(duplicate))
				.isInstanceOf(EventException.class)
				.satisfies(error -> assertThat(((EventException) error).getErrorCode())
						.isEqualTo(EventErrorCode.INVALID_PRIZES));
		verifyNoInteractions(events);
	}

	@Test
	void incompatibleTicketRuleFailsBeforeAnyWrite() {
		EventRegistration source = validRegistration();
		EventRegistration invalid = new EventRegistration(source.getActorId(), source.getTitle(),
				source.getDescription(), source.getImageKey(), source.getDetailImageKey(), EventType.NO_TICKET, true,
				5, source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(), source.getPrizes());

		assertThatThrownBy(() -> service.register(invalid))
				.isInstanceOf(EventException.class)
				.satisfies(error -> assertThat(((EventException) error).getErrorCode())
						.isEqualTo(EventErrorCode.INVALID_CONFIGURATION));
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("null·빈 경품 목록과 null 경품은 생성 단계가 아닌 업무 검증에서 거절한다")
	void invalidPrizeListsFailBeforeAnyWrite() {
		// given
		EventRegistration source = validRegistration();
		List<List<EventRegistration.Prize>> invalidLists = Arrays.asList(
				null, List.of(), Arrays.asList((EventRegistration.Prize) null));

		// when / then
		for (List<EventRegistration.Prize> prizes : invalidLists) {
			EventRegistration invalid = new EventRegistration(source.getActorId(), source.getTitle(),
					source.getDescription(), source.getImageKey(), source.getDetailImageKey(), source.getEventType(), source.isWeightingEnabled(),
					source.getMaxTicketsPerUser(), source.getMembershipRule(), source.getStartsAt(), source.getEndsAt(),
					prizes);
			assertThatThrownBy(() -> service.register(invalid))
					.isInstanceOfSatisfying(EventException.class,
							error -> assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.INVALID_PRIZES));
		}
		verifyNoInteractions(events);
	}

	@Test
	void nonAdminCannotRegister() {
		when(actors.findById(ADMIN_ID)).thenReturn(Optional.of(new EventActorRepository.Actor(true, false)));

		assertThatThrownBy(() -> service.register(validRegistration()))
				.isInstanceOf(EventException.class)
				.satisfies(error -> assertThat(((EventException) error).getErrorCode())
						.isEqualTo(EventErrorCode.ADMIN_REQUIRED));
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@ValueSource(strings = {"x", "가", "😀", "A가😀"})
	@DisplayName("영문·한글·이모지 설명은 UTF-8 65,535바이트까지 등록할 수 있다")
	void acceptsDescriptionsAtUtf8ByteLimit(String unit) {
		// given
		String description = descriptionAtTextLimit(unit);
		EventRegistration registration = withDescriptions(description, description);

		// when
		service.register(registration);

		// then
		verify(events).create(registration, EventStatus.SCHEDULED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"x", "가", "😀", "A가😀"})
	@DisplayName("이벤트 설명이 UTF-8 상한을 1바이트 넘으면 저장 전에 거절한다")
	void rejectsOversizedEventDescriptionBeforeAnyWrite(String unit) {
		// given
		EventRegistration registration = withDescriptions(descriptionAtTextLimit(unit) + "x", null);

		// when / then
		assertThatThrownBy(() -> service.register(registration))
				.isInstanceOf(EventException.class)
				.satisfies(error -> assertThat(((EventException) error).getErrorCode())
						.isEqualTo(EventErrorCode.INVALID_DETAILS));
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@ValueSource(strings = {"x", "가", "😀", "A가😀"})
	@DisplayName("경품 설명이 UTF-8 상한을 1바이트 넘으면 저장 전에 거절한다")
	void rejectsOversizedPrizeDescriptionBeforeAnyWrite(String unit) {
		// given
		EventRegistration registration = withDescriptions("이벤트 설명", descriptionAtTextLimit(unit) + "x");

		// when / then
		assertThatThrownBy(() -> service.register(registration))
				.isInstanceOf(EventException.class)
				.satisfies(error -> assertThat(((EventException) error).getErrorCode())
						.isEqualTo(EventErrorCode.INVALID_PRIZES));
		verifyNoInteractions(events);
	}

	private String descriptionAtTextLimit(String unit) {
		int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
		return unit.repeat(65_535 / unitBytes) + "x".repeat(65_535 % unitBytes);
	}

	private EventRegistration withDescriptions(String description, String prizeDescription) {
		EventRegistration source = validRegistration();
		EventRegistration.Prize prize = source.getPrizes().getFirst();
		return new EventRegistration(source.getActorId(), source.getTitle(), description, source.getImageKey(), source.getDetailImageKey(),
				source.getEventType(), source.isWeightingEnabled(), source.getMaxTicketsPerUser(), source.getMembershipRule(),
				source.getStartsAt(), source.getEndsAt(),
				List.of(new EventRegistration.Prize(prize.getRank(), prize.getName(), prizeDescription,
						prize.getImageKey(), prize.getWinnerCount()), source.getPrizes().get(1)));
	}

	private EventRegistration validRegistration() {
		return new EventRegistration(ADMIN_ID, "가을 이벤트", "경품 응모", null, null,
				EventType.TICKET, true, 5, MembershipRule.vip,
				NOW.plusSeconds(3600), NOW.plusSeconds(7200),
				List.of(new EventRegistration.Prize(1, "경품 A", null, null, 1),
						new EventRegistration.Prize(2, "경품 B", null, null, 3)));
	}

	private EventRegistration withPeriod(EventRegistration source, Instant startsAt, Instant endsAt) {
		return new EventRegistration(source.getActorId(), source.getTitle(), source.getDescription(), source.getImageKey(), source.getDetailImageKey(),
				source.getEventType(), source.isWeightingEnabled(), source.getMaxTicketsPerUser(), source.getMembershipRule(),
				startsAt, endsAt, source.getPrizes());
	}
}
