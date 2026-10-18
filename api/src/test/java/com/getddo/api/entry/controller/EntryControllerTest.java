package com.getddo.api.entry.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.entry.domain.EntryCommand;
import com.getddo.core.entry.domain.EntryEligibility;
import com.getddo.core.entry.domain.EntryReceipt;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.exception.EntryException;
import com.getddo.core.entry.service.EntryEligibilityService;
import com.getddo.core.entry.service.EntryService;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.exception.TicketErrorCode;
import com.getddo.core.ticket.exception.TicketException;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntryControllerTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final User USER = new User(USER_ID, "사용자", UserRole.USER, UserStatus.ACTIVE,
			Membership.VIP, null, null, null, null, null, null);
	private static final UUID EVENT_ID = UUID.randomUUID();
	private static final UUID ENTRY_ID = UUID.randomUUID();
	private static final Instant ACCEPTED_AT = Instant.parse("2026-09-15T03:00:00Z");

	private final EntryService entryService = mock(EntryService.class);
	private final EntryEligibilityService eligibilityService = mock(EntryEligibilityService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new EntryController(entryService, eligibilityService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(USER).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", USER_ID).header("X-User-Role", "USER").header("X-User-Membership", "vip");
	}

	private MockHttpServletRequestBuilder enter(String body) {
		return asUser(post("/api/v1/events/{eventId}/entries", EVENT_ID))
				.header("Idempotency-Key", ENTRY_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private static EntryReceipt receipt(boolean created) {
		return new EntryReceipt(ENTRY_ID, EVENT_ID, "가을 이벤트",
				Map.of(TicketGrade.GOLD, 2L, TicketGrade.SILVER, 1L), ACCEPTED_AT, created);
	}

	@Test
	@DisplayName("새 응모면 201과 등급별 요청·차감 장수를 담은 영수증을 반환하고 요청을 서비스 입력으로 옮긴다")
	void returnsCreatedWithReceiptAndPassesRequestToService() throws Exception {
		// given
		when(entryService.enter(any())).thenReturn(receipt(true));
		// when
		// then
		mvc.perform(enter("{\"tickets\":{\"GOLD\":2,\"SILVER\":1}}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(ENTRY_ID.toString()))
				.andExpect(jsonPath("$.data.eventId").value(EVENT_ID.toString()))
				.andExpect(jsonPath("$.data.eventTitle").value("가을 이벤트"))
				.andExpect(jsonPath("$.data.requestedTicketCount").value(3))
				.andExpect(jsonPath("$.data.requestedTicketsByGrade.GOLD").value(2))
				.andExpect(jsonPath("$.data.requestedTicketsByGrade.BRONZE").value(0))
				.andExpect(jsonPath("$.data.deductedTicketCount").value(3))
				.andExpect(jsonPath("$.data.deductedTicketsByGrade.SILVER").value(1))
				.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.data.requestedAt").value("2026-09-15T03:00:00Z"))
				.andExpect(jsonPath("$.data.acceptedAt").value("2026-09-15T03:00:00Z"))
				.andExpect(jsonPath("$.data.rejectionCode").value(org.hamcrest.Matchers.nullValue()));
		ArgumentCaptor<EntryCommand> command = ArgumentCaptor.forClass(EntryCommand.class);
		verify(entryService).enter(command.capture());
		assertThat(command.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(command.getValue().isAdmin()).isFalse();
		assertThat(command.getValue().getMembership()).isEqualTo(Membership.VIP);
		assertThat(command.getValue().getEventId()).isEqualTo(EVENT_ID);
		assertThat(command.getValue().getEntryId()).isEqualTo(ENTRY_ID);
		assertThat(command.getValue().getTickets()).containsEntry(TicketGrade.GOLD, 2L)
				.containsEntry(TicketGrade.SILVER, 1L);
	}

	@Test
	@DisplayName("같은 키의 재요청이면 200과 이미 접수한 결과를 반환한다")
	void returnsOkForReplay() throws Exception {
		// given
		when(entryService.enter(any())).thenReturn(receipt(false));
		// when
		// then
		mvc.perform(enter("{\"tickets\":{\"GOLD\":2,\"SILVER\":1}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(ENTRY_ID.toString()));
	}

	@Test
	@DisplayName("tickets를 생략하거나 빈 객체로 보내면 응모권 없는 요청으로 처리한다")
	void treatsMissingTicketsAsEmpty() throws Exception {
		// given
		when(entryService.enter(any())).thenReturn(new EntryReceipt(ENTRY_ID, EVENT_ID, "이벤트", Map.of(),
				ACCEPTED_AT, true));
		// when
		// then
		mvc.perform(enter("{}")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.requestedTicketCount").value(0));
		mvc.perform(enter("{\"tickets\":{}}")).andExpect(status().isCreated());
		ArgumentCaptor<EntryCommand> command = ArgumentCaptor.forClass(EntryCommand.class);
		verify(entryService, times(2)).enter(command.capture());
		assertThat(command.getAllValues()).allSatisfy(captured -> assertThat(captured.getTickets()).isEmpty());
	}

	@Test
	@DisplayName("Idempotency-Key가 없거나 UUID가 아니면 400이고 서비스를 호출하지 않는다")
	void rejectsMissingOrMalformedIdempotencyKey() throws Exception {
		// given
		// when
		// then
		mvc.perform(asUser(post("/api/v1/events/{eventId}/entries", EVENT_ID))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(asUser(post("/api/v1/events/{eventId}/entries", EVENT_ID))
						.header("Idempotency-Key", "not-a-uuid")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(entryService);
	}

	@Test
	@DisplayName("플래티넘 장수를 받아 서비스에 넘기고, 영수증의 등급별 맵에 플래티넘 키를 담는다")
	void acceptsPlatinumTickets() throws Exception {
		// given
		when(entryService.enter(any())).thenReturn(new EntryReceipt(ENTRY_ID, EVENT_ID, "가을 이벤트",
				Map.of(TicketGrade.PLATINUM, 1L, TicketGrade.BRONZE, 1L), ACCEPTED_AT, true));
		// when
		mvc.perform(enter("{\"tickets\":{\"PLATINUM\":1,\"BRONZE\":1}}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.requestedTicketCount").value(2))
				.andExpect(jsonPath("$.data.requestedTicketsByGrade.PLATINUM").value(1))
				.andExpect(jsonPath("$.data.requestedTicketsByGrade.GOLD").value(0))
				.andExpect(jsonPath("$.data.deductedTicketsByGrade.PLATINUM").value(1));
		// then
		ArgumentCaptor<EntryCommand> command = ArgumentCaptor.forClass(EntryCommand.class);
		verify(entryService).enter(command.capture());
		assertThat(command.getValue().getTickets()).containsEntry(TicketGrade.PLATINUM, 1L)
				.containsEntry(TicketGrade.BRONZE, 1L);
	}

	@Test
	@DisplayName("알 수 없는 등급이나 음수·null 장수, 본문 없음은 400이고 서비스를 호출하지 않는다")
	void rejectsInvalidBody() throws Exception {
		// given
		// when
		// then
		mvc.perform(enter("{\"tickets\":{\"DIAMOND\":1}}")).andExpect(status().isBadRequest());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":-1}}")).andExpect(status().isBadRequest());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":null}}")).andExpect(status().isBadRequest());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":\"many\"}}")).andExpect(status().isBadRequest());
		mvc.perform(asUser(post("/api/v1/events/{eventId}/entries", EVENT_ID)).header("Idempotency-Key", ENTRY_ID))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(entryService);
	}

	@Test
	@DisplayName("관리자 헤더로 요청하면 관리자 여부를 서비스에 넘기고 서비스의 거절 코드를 그대로 응답한다")
	void passesAdminFlagAndMapsDomainErrors() throws Exception {
		// given
		User admin = new User(USER_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null,
				null, null);
		MockMvc adminMvc = MockMvcBuilders.standaloneSetup(new EntryController(entryService, eligibilityService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(new UserService(id -> Optional.of(admin))))
				.build();
		doThrow(new EntryException(EntryErrorCode.ADMIN_ENTRY_FORBIDDEN)).when(entryService).enter(any());
		// when
		// then
		adminMvc.perform(post("/api/v1/events/{eventId}/entries", EVENT_ID)
						.header("X-User-ID", USER_ID).header("X-User-Role", "ADMIN")
						.header("Idempotency-Key", ENTRY_ID)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ENTRY-002"));
		ArgumentCaptor<EntryCommand> command = ArgumentCaptor.forClass(EntryCommand.class);
		verify(entryService).enter(command.capture());
		assertThat(command.getValue().isAdmin()).isTrue();
	}

	@Test
	@DisplayName("응모 규칙 위반은 오류 코드별 상태로, 응모권 부족은 TICKET-006 409로 응답한다")
	void mapsBusinessErrors() throws Exception {
		// given
		// when
		// then
		doThrow(new EntryException(EntryErrorCode.EVENT_NOT_OPEN)).when(entryService).enter(any());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":1}}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ENTRY-005"));
		doThrow(new EntryException(EntryErrorCode.EVENT_NOT_FOUND)).when(entryService).enter(any());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":1}}")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ENTRY-004"));
		doThrow(new EntryException(EntryErrorCode.IDEMPOTENCY_CONFLICT)).when(entryService).enter(any());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":1}}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ENTRY-009"));
		doThrow(new TicketException(TicketErrorCode.TICKET_INSUFFICIENT)).when(entryService).enter(any());
		mvc.perform(enter("{\"tickets\":{\"GOLD\":1}}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKET-006"));
	}

	@Test
	@DisplayName("자격 조회는 가능 여부·사유·사용량·잔여 상한·보유 장수·서버 시각을 반환한다")
	void returnsEligibility() throws Exception {
		// given
		when(eligibilityService.check(USER_ID, false, Membership.VIP, EVENT_ID)).thenReturn(new EntryEligibility(
				EVENT_ID, List.of("ALREADY_ENTERED"), 1, 0L, 2, ACCEPTED_AT));
		// when
		// then
		mvc.perform(asUser(get("/api/v1/events/{eventId}/eligibility", EVENT_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.eventId").value(EVENT_ID.toString()))
				.andExpect(jsonPath("$.data.canEnter").value(false))
				.andExpect(jsonPath("$.data.reasons[0]").value("ALREADY_ENTERED"))
				.andExpect(jsonPath("$.data.usedTicketCount").value(1))
				.andExpect(jsonPath("$.data.remainingTicketLimit").value(0))
				.andExpect(jsonPath("$.data.availableTicketBalance").value(2))
				.andExpect(jsonPath("$.data.serverTime").value("2026-09-15T03:00:00Z"));
	}

	@Test
	@DisplayName("수량 상한이 없는 이벤트의 자격 조회는 remainingTicketLimit이 null이다")
	void returnsNullRemainingLimitForUnlimitedEvent() throws Exception {
		// given
		when(eligibilityService.check(USER_ID, false, Membership.VIP, EVENT_ID))
				.thenReturn(new EntryEligibility(EVENT_ID, List.of(), 0, null, 5, ACCEPTED_AT));
		// when
		// then
		mvc.perform(asUser(get("/api/v1/events/{eventId}/eligibility", EVENT_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.canEnter").value(true))
				.andExpect(jsonPath("$.data.remainingTicketLimit").value(org.hamcrest.Matchers.nullValue()));
	}
}
