package com.getddo.api.event;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.api.event.controller.AdminEventController;
import com.getddo.api.event.controller.EventController;
import com.getddo.api.event.config.EventImageUrls;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.AdminEventQuery;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;
import com.getddo.core.event.service.EventQueryService;
import com.getddo.core.event.service.EventRegistrationService;
import com.getddo.core.event.service.EventModificationService;
import com.getddo.core.event.service.EventDeletionService;
import com.getddo.core.event.service.EventCancellationService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventResponseContractTest {
	private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000591");
	private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000592");
	private static final UUID PRIZE_ID = UUID.fromString("00000000-0000-0000-0000-000000000593");
	private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
	private static final Instant STARTS_AT = Instant.parse("2026-10-02T09:00:00Z");
	private static final Instant ENDS_AT = Instant.parse("2026-10-03T09:00:00Z");
	private EventQueryService queries;
	private EventRegistrationService registrations;
	private EventDeletionService deletions;
	private MockMvc mvc;
	private User actor;

	@BeforeEach
	void setUp() {
		queries = mock(EventQueryService.class);
		registrations = mock(EventRegistrationService.class);
		deletions = mock(EventDeletionService.class);
		UserService users = mock(UserService.class);
		actor = new User(ACTOR_ID, "관리자", UserRole.ADMIN,
				UserStatus.ACTIVE, null, null, null, null, null, NOW, NOW);
		when(users.findById(ACTOR_ID)).thenReturn(actor);
		TimeProvider time = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
		mvc = MockMvcBuilders.standaloneSetup(new EventController(queries, time, new EventImageUrls("")),
				new AdminEventController(registrations, mock(EventModificationService.class), deletions,
						mock(EventCancellationService.class), queries, time, new EventImageUrls("")))
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(users))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@Test
	@DisplayName("사용자 목록·상세는 공개 상태를 반환하고 발표 예정 시각·관리 필드를 숨긴다")
	void publicResponsesPreserveTheirContract() throws Exception {
		// given
		EventView view = new EventView(event(EventStatus.REDRAWING), EventStatus.PUBLISHED, null);
		when(queries.findUserEvents(any(), anyInt(), anyInt(), any()))
				.thenReturn(new PageResult<>(List.of(view), 1, 20, 1));
		when(queries.findUserEvent(actor, EVENT_ID)).thenReturn(view);
		// when / then
		mvc.perform(get("/api/v1/events").header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"))
				.andExpect(jsonPath("$.data.items[0].winnerCount").value(3))
				.andExpect(jsonPath("$.data.items[0].publicationScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.items[0].drawScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.items[0].serverTime").value(NOW.toString()))
				.andExpect(jsonPath("$.data.items[0].imageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].imageKey").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].createdBy").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.items[0].suspendedFromStatus").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.items[0].suspendedAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.items[0].prizes").doesNotExist());
		mvc.perform(get("/api/v1/events/" + EVENT_ID).header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.data.winnerCount").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.startsAt").value(STARTS_AT.toString()))
				.andExpect(jsonPath("$.data.endsAt").value(ENDS_AT.toString()))
				.andExpect(jsonPath("$.data.publicationScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.drawScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.serverTime").value(NOW.toString()))
				.andExpect(jsonPath("$.data.description").value("이벤트 설명"))
				.andExpect(jsonPath("$.data.maxTicketsPerUser").value(nullValue()))
				.andExpect(jsonPath("$.data.prizes[0].id").value(PRIZE_ID.toString()))
				.andExpect(jsonPath("$.data.prizes[0].rank").value(2))
				.andExpect(jsonPath("$.data.prizes[0].winnerCount").value(3))
				.andExpect(jsonPath("$.data.prizes[0].description").value(nullValue()))
				.andExpect(jsonPath("$.data.prizes[0].imageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data.prizes[0].imageKey").doesNotExist())
				.andExpect(jsonPath("$.data.createdBy").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedFromStatus").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedAt").doesNotHaveJsonPath());
	}

	@Test
	@DisplayName("관리자 목록·상세는 실제 상태와 이미지 키를 유지하고 작성자·중단 필드를 제공하지 않는다")
	void adminResponsesPreserveTheirContract() throws Exception {
		// given
		EventView view = new EventView(event(EventStatus.REDRAWING), EventStatus.PUBLISHED, null);
		when(queries.findAdminEvents(any(), anyInt(), anyInt(), any()))
				.thenReturn(new PageResult<>(List.of(view), 1, 20, 1));
		when(queries.findAdminEvent(actor, EVENT_ID)).thenReturn(view);
		// when / then
		for (String path : new String[]{"/api/v1/admin/events", "/api/v1/admin/events/" + EVENT_ID}) {
			String root = path.endsWith(EVENT_ID.toString()) ? "$.data" : "$.data.items[0]";
			mvc.perform(get(path).header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
					.andExpect(status().isOk())
					.andExpect(jsonPath(root + ".status").value("REDRAWING"))
					.andExpect(jsonPath(root + ".imageKey").value("events/image.png"))
					.andExpect(jsonPath(root + ".createdBy").doesNotHaveJsonPath())
					.andExpect(jsonPath(root + ".createdAt").value(NOW.toString()))
					.andExpect(jsonPath(root + ".updatedAt").value(NOW.toString()))
					.andExpect(jsonPath(root + ".suspendedFromStatus").doesNotHaveJsonPath())
					.andExpect(jsonPath(root + ".suspendedAt").doesNotHaveJsonPath())
					.andExpect(jsonPath(root + ".canceledAt").value(nullValue()))
					.andExpect(jsonPath(root + ".publicationScheduledAt").doesNotHaveJsonPath())
					.andExpect(jsonPath(root + ".drawScheduledAt").value("2026-10-03T09:05:00Z"))
					.andExpect(jsonPath(root + ".prizes[0].winnerCount").value(3))
					.andExpect(jsonPath(root + ".prizeImages[0].prizeId").value(PRIZE_ID.toString()))
					.andExpect(jsonPath(root + ".prizeImages[0].imageKey").value("prizes/image.png"));
		}
	}

	@Test
	@DisplayName("관리자 API는 날짜 형식만 해석하고 LocalDate 검색 입력을 서비스에 전달한다")
	void adminApiPassesCalendarDatesToService() throws Exception {
		// given
		when(queries.findAdminEvents(any(), anyInt(), anyInt(), any()))
				.thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		mvc.perform(get("/api/v1/admin/events").header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN")
				.param("from", "2026-10-10").param("to", "2026-10-23")
				.param("keyword", "아이폰").param("status", "OPEN").param("eventType", "TICKET"))
				.andExpect(status().isOk());
		// then
		ArgumentCaptor<AdminEventQuery> query = ArgumentCaptor.forClass(AdminEventQuery.class);
		verify(queries).findAdminEvents(eq(actor), eq(1), eq(20), query.capture());
		assertThat(query.getValue().getFromDate()).isEqualTo(LocalDate.of(2026, 10, 10));
		assertThat(query.getValue().getToDate()).isEqualTo(LocalDate.of(2026, 10, 23));
		assertThat(query.getValue().getKeyword()).isEqualTo("아이폰");
		assertThat(query.getValue().getStatus()).isEqualTo(EventStatus.OPEN);
		assertThat(query.getValue().getEventType()).isEqualTo(EventType.TICKET);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "invalid", "2026-02-30", "2026/10/10", "2026-1-1",
			"2026-10-10T00:00:00Z", "+10000-10-01"})
	@DisplayName("날짜 DTO 바인딩은 잘못된 날짜를 COMMON-005로 거절하고 서비스를 호출하지 않는다")
	void invalidCalendarDateIsRejectedBeforeService(String value) throws Exception {
		// given / when / then
		for (String parameter : new String[]{"from", "to"}) {
			mvc.perform(get("/api/v1/admin/events").header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN")
					.param(parameter, value))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		}
		verifyNoInteractions(queries);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/events/invalid", "/api/v1/admin/events/invalid"})
	@DisplayName("Spring의 UUID 변환 실패는 COMMON-005로 응답하고 조회 서비스를 호출하지 않는다")
	void invalidUuidIsRejectedBeforeService(String path) throws Exception {
		// given / when / then
		mvc.perform(get(path).header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(queries);
	}

	@Test
	@DisplayName("등록 응답은 이벤트·경품과 취소 시각을 유지하고 작성자·중단 필드를 제공하지 않는다")
	void registrationResponsePreservesItsContract() throws Exception {
		// given
		when(registrations.register(any())).thenReturn(event(EventStatus.SCHEDULED));
		String body = """
				{"title":"이벤트","description":"이벤트 설명","eventType":"TICKET",
				"weightingEnabled":true,"maxTicketsPerUser":null,"membershipRule":"vip",
				"startsAt":"2026-10-02T09:00:00Z","endsAt":"2026-10-03T09:00:00Z",
				"prizes":[{"rank":2,"name":"경품","winnerCount":3}]}
				""";
		// when / then
		mvc.perform(post("/api/v1/admin/events").header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").value(EVENT_ID.toString()))
				.andExpect(jsonPath("$.data.status").value("SCHEDULED"))
				.andExpect(jsonPath("$.data.title").value("이벤트"))
				.andExpect(jsonPath("$.data.eventType").value("TICKET"))
				.andExpect(jsonPath("$.data.weightingEnabled").value(true))
				.andExpect(jsonPath("$.data.membershipRule").value("vip"))
				.andExpect(jsonPath("$.data.maxTicketsPerUser").value(nullValue()))
				.andExpect(jsonPath("$.data.createdBy").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedFromStatus").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.canceledAt").value(nullValue()))
				.andExpect(jsonPath("$.data.prizes[0].name").value("경품"))
				.andExpect(jsonPath("$.data.prizes[0].winnerCount").value(3))
				.andExpect(jsonPath("$.data.imageUrl").value(nullValue()));
	}

	@Test
	@DisplayName("이벤트 전용 예외도 공통 처리기를 통해 기존 404 오류 응답으로 변환된다")
	void eventExceptionPreservesErrorResponse() throws Exception {
		// given
		when(queries.findAdminEvent(actor, EVENT_ID))
				.thenThrow(new EventException(EventErrorCode.EVENT_NOT_FOUND));
		// when / then
		mvc.perform(get("/api/v1/admin/events/" + EVENT_ID).header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("EVENT-007"))
				.andExpect(jsonPath("$.message").value("이벤트를 찾을 수 없습니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("사용자 헤더가 누락되면 공통 사용자 문맥 검증의 401 오류를 반환한다")
	void missingContextPreservesErrorResponse() throws Exception {
		// given / when / then
		mvc.perform(get("/api/v1/events"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("USER-003"))
				.andExpect(jsonPath("$.message").value("사용자 정보 헤더가 필요합니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("관리자 삭제는 변환된 이벤트 UUID·사용자 ID를 전달하고 200·data null을 반환한다")
	void deletionResponsePreservesItsContract() throws Exception {
		// given / when / then
		mvc.perform(delete("/api/v1/admin/events/" + EVENT_ID)
				.header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value(nullValue()));
		verify(deletions).delete(EVENT_ID, ACTOR_ID);
	}

	@Test
	@DisplayName("삭제 요청의 UUID 변환 실패는 서비스를 호출하지 않고 COMMON-005로 거절한다")
	void invalidDeletionUuidDoesNotCallService() throws Exception {
		// given / when / then
		mvc.perform(delete("/api/v1/admin/events/invalid")
				.header("X-User-ID", ACTOR_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(deletions);
	}

	private static RegisteredEvent event(EventStatus status) {
		return new RegisteredEvent(EVENT_ID, "이벤트", "이벤트 설명", "events/image.png",
				EventType.TICKET, true, null, MembershipRule.vip, STARTS_AT, ENDS_AT, status, NOW, NOW,
				List.of(new RegisteredEvent.Prize(PRIZE_ID, 2, "경품", null, "prizes/image.png", 3)));
	}
}
