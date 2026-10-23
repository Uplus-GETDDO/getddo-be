package com.getddo.api.ticket.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.ticket.domain.AdminTicketHistoryFilter;
import com.getddo.core.ticket.domain.AdminTicketHistoryView;
import com.getddo.core.ticket.domain.TicketConsistencyResult;
import com.getddo.core.ticket.domain.TicketHistoryView;
import com.getddo.core.ticket.domain.TicketInconsistencyReport;
import com.getddo.core.ticket.domain.TicketInconsistencyView;
import com.getddo.core.ticket.domain.TicketOperationType;
import com.getddo.core.ticket.domain.TicketRefundView;
import com.getddo.core.ticket.domain.TicketStatus;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.domain.TicketGrantFilter;
import com.getddo.core.ticket.domain.TicketGrantView;
import com.getddo.core.ticket.exception.TicketErrorCode;
import com.getddo.core.ticket.exception.TicketException;
import com.getddo.core.ticket.service.AdminTicketQueryService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTicketControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null,
			null, null, null, null);
	private final AdminTicketQueryService service = mock(AdminTicketQueryService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminTicketController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}

	@Test
	@DisplayName("AO03은 지급 이력을 계약 필드와 페이지 봉투로 반환한다")
	void returnsGrantsInContractShape() throws Exception {
		// given
		UUID historyId = UUID.randomUUID();
		UUID ticketId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID sourceId = UUID.randomUUID();
		UUID missionId = UUID.randomUUID();
		TicketGrantView view = new TicketGrantView(historyId, ticketId, userId, "홍길동", TicketGrade.GOLD,
				GrantSourceType.MISSION, sourceId, missionId, Instant.parse("2026-10-01T00:00:00Z"),
				Instant.parse("2026-12-31T14:59:59.999999Z"));
		when(service.getGrants(any(), any(), any())).thenReturn(new PageResult<>(List.of(view), 2, 5, 11));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-grants")).param("page", "2").param("size", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].historyId").value(historyId.toString()))
				.andExpect(jsonPath("$.data.items[0].ticketId").value(ticketId.toString()))
				.andExpect(jsonPath("$.data.items[0].userId").value(userId.toString()))
				.andExpect(jsonPath("$.data.items[0].userName").value("홍길동"))
				.andExpect(jsonPath("$.data.items[0].grade").value("GOLD"))
				.andExpect(jsonPath("$.data.items[0].sourceType").value("MISSION"))
				.andExpect(jsonPath("$.data.items[0].sourceId").value(sourceId.toString()))
				.andExpect(jsonPath("$.data.items[0].missionId").value(missionId.toString()))
				.andExpect(jsonPath("$.data.items[0].grantedAt").value("2026-10-01T00:00:00Z"))
				.andExpect(jsonPath("$.data.page").value(2))
				.andExpect(jsonPath("$.data.size").value(5))
				.andExpect(jsonPath("$.data.totalElements").value(11));
	}

	@Test
	@DisplayName("AO03은 요청 파라미터를 조회 조건과 페이지로 옮긴다")
	void passesFilterAndPage() throws Exception {
		// given
		UUID userId = UUID.randomUUID();
		UUID missionId = UUID.randomUUID();
		when(service.getGrants(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 3, 10, 0));
		// when
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-grants"))
				.param("page", "3").param("size", "10").param("userId", userId.toString())
				.param("keyword", "홍").param("missionId", missionId.toString())
				.param("sourceType", "MISSION").param("grade", "GOLD")
				.param("from", "2026-10-01T09:00:00+09:00").param("to", "2026-10-02T00:00:00Z"))
				.andExpect(status().isOk());
		// then
		ArgumentCaptor<TicketGrantFilter> filter = ArgumentCaptor.forClass(TicketGrantFilter.class);
		ArgumentCaptor<PageQuery> page = ArgumentCaptor.forClass(PageQuery.class);
		verify(service).getGrants(any(User.class), filter.capture(), page.capture());
		assertThat(page.getValue()).isEqualTo(new PageQuery(3, 10));
		assertThat(filter.getValue().getUserId()).isEqualTo(userId);
		assertThat(filter.getValue().getKeyword()).isEqualTo("홍");
		assertThat(filter.getValue().getMissionId()).isEqualTo(missionId);
		assertThat(filter.getValue().getSourceType()).isEqualTo(GrantSourceType.MISSION);
		assertThat(filter.getValue().getGrade()).isEqualTo(TicketGrade.GOLD);
		assertThat(filter.getValue().getFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
		assertThat(filter.getValue().getTo()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
	}

	@Test
	@DisplayName("AO03은 grade=PLATINUM 필터를 받아 서비스에 넘긴다")
	void acceptsPlatinumGradeFilter() throws Exception {
		// given
		when(service.getGrants(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-grants")).param("grade", "PLATINUM"))
				.andExpect(status().isOk());
		// then
		ArgumentCaptor<TicketGrantFilter> filter = ArgumentCaptor.forClass(TicketGrantFilter.class);
		verify(service).getGrants(any(User.class), filter.capture(), any(PageQuery.class));
		assertThat(filter.getValue().getGrade()).isEqualTo(TicketGrade.PLATINUM);
	}

	@Test
	@DisplayName("AO03은 파라미터를 생략하면 기본 1페이지 20건에 조건 없이 조회한다")
	void usesDefaults() throws Exception {
		// given
		when(service.getGrants(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		// when
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-grants"))).andExpect(status().isOk());
		// then
		ArgumentCaptor<PageQuery> page = ArgumentCaptor.forClass(PageQuery.class);
		verify(service).getGrants(any(User.class), any(TicketGrantFilter.class), page.capture());
		assertThat(page.getValue()).isEqualTo(new PageQuery(1, 20));
	}

	@ParameterizedTest(name = "{0}은 400이다")
	@ValueSource(strings = {"page=0", "size=0", "size=101", "sourceType=BOGUS", "grade=DIAMOND", "userId=not-uuid",
			"from=2026-10-01T00:00:00"})
	@DisplayName("AO03은 범위·형식이 잘못된 파라미터를 서비스 호출 없이 400으로 거절한다")
	void rejectsInvalidParameters(String pair) throws Exception {
		// given
		String[] kv = pair.split("=", 2);
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-grants")).param(kv[0], kv[1]))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AO03은 활성 관리자가 아니면 403 TICKET-010으로 응답한다")
	void forbidsNonAdmin() throws Exception {
		// given
		when(service.getGrants(any(), any(), any()))
				.thenThrow(new TicketException(TicketErrorCode.TICKET_ADMIN_REQUIRED));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-grants")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TICKET-010"));
	}

	@Test
	@DisplayName("AO05는 사용자 이력 필드에 사용자 이름과 이벤트·미션 제목을 더해 평평하게 반환한다")
	void returnsAdminHistories() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		UUID missionId = UUID.randomUUID();
		TicketHistoryView history = new TicketHistoryView(id, UUID.randomUUID(), TicketOperationType.REFUND,
				TicketGrade.SILVER, TicketStatus.RETURNED, Instant.parse("2026-11-30T00:00:00Z"), "이벤트 취소",
				Instant.parse("2026-10-01T00:00:00Z"), eventId, null, null, null, null, UUID.randomUUID(), null);
		when(service.getHistories(any(), any(), any())).thenReturn(new PageResult<>(
				List.of(new AdminTicketHistoryView(history, userId, "홍길동", "가을 이벤트", null)), 1, 20, 1));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-histories")).param("eventId", eventId.toString())
				.param("missionId", missionId.toString()).param("operationType", "REFUND")
				.param("userId", userId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(id.toString()))
				.andExpect(jsonPath("$.data.items[0].operationType").value("REFUND"))
				.andExpect(jsonPath("$.data.items[0].eventId").value(eventId.toString()))
				.andExpect(jsonPath("$.data.items[0].userId").value(userId.toString()))
				.andExpect(jsonPath("$.data.items[0].userName").value("홍길동"))
				.andExpect(jsonPath("$.data.items[0].eventTitle").value("가을 이벤트"))
				.andExpect(jsonPath("$.data.items[0].missionTitle").value(nullValue()));
		ArgumentCaptor<AdminTicketHistoryFilter> filter = ArgumentCaptor.forClass(AdminTicketHistoryFilter.class);
		verify(service).getHistories(any(User.class), filter.capture(), any(PageQuery.class));
		assertThat(filter.getValue().getEventId()).isEqualTo(eventId);
		assertThat(filter.getValue().getMissionId()).isEqualTo(missionId);
		assertThat(filter.getValue().getOperationType()).isEqualTo(TicketOperationType.REFUND);
	}

	@Test
	@DisplayName("AO08은 반환 이력을 사유 종류 EVENT_CANCELED와 함께 반환하고 처리 상태 필드는 없다")
	void returnsRefunds() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		UUID spend = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		when(service.getRefunds(any(), any(), any())).thenReturn(new PageResult<>(List.of(new TicketRefundView(id,
				spend, UUID.randomUUID(), TicketGrade.GOLD, eventId, "취소된 이벤트", UUID.randomUUID(), "홍길동",
				Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-30T00:00:00Z"))), 1, 20, 1));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/ticket-refunds")).param("eventId", eventId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(id.toString()))
				.andExpect(jsonPath("$.data.items[0].spendHistoryId").value(spend.toString()))
				.andExpect(jsonPath("$.data.items[0].grade").value("GOLD"))
				.andExpect(jsonPath("$.data.items[0].eventTitle").value("취소된 이벤트"))
				.andExpect(jsonPath("$.data.items[0].reasonType").value("EVENT_CANCELED"))
				.andExpect(jsonPath("$.data.items[0].refundedAt").value("2026-10-01T00:00:00Z"))
				.andExpect(jsonPath("$.data.items[0].targetExpiresAt").value("2026-11-30T00:00:00Z"))
				.andExpect(jsonPath("$.data.items[0].status").doesNotExist());
	}

	@Test
	@DisplayName("AO04는 일치 여부를 isConsistent로, 어긋난 응모권을 버전·상태와 함께 반환한다")
	void returnsConsistency() throws Exception {
		// given
		UUID userId = UUID.randomUUID();
		UUID ticketId = UUID.randomUUID();
		Instant checkedAt = Instant.parse("2026-10-15T00:00:00Z");
		when(service.getConsistency(any(), eq(userId))).thenReturn(
				new TicketConsistencyResult(userId, "홍길동", 4, List.of(new TicketInconsistencyView(ticketId, userId, "홍길동",
						TicketGrade.GOLD, 3, 1, TicketStatus.SPENT, TicketStatus.AVAILABLE)), checkedAt));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/users/{userId}/tickets/consistency", userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.userId").value(userId.toString()))
				.andExpect(jsonPath("$.data.userName").value("홍길동"))
				.andExpect(jsonPath("$.data.availableCount").value(4))
				.andExpect(jsonPath("$.data.isConsistent").value(false))
				.andExpect(jsonPath("$.data.inconsistencies[0].ticketId").value(ticketId.toString()))
				.andExpect(jsonPath("$.data.inconsistencies[0].ticketVersion").value(3))
				.andExpect(jsonPath("$.data.inconsistencies[0].latestHistoryVersion").value(1))
				.andExpect(jsonPath("$.data.inconsistencies[0].ticketStatus").value("SPENT"))
				.andExpect(jsonPath("$.data.inconsistencies[0].latestHistoryStatus").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.checkedAt").value("2026-10-15T00:00:00Z"));
	}

	@Test
	@DisplayName("AO04는 없는 사용자를 404 TICKET-011로, 잘못된 사용자 ID 형식을 400으로 응답한다")
	void consistencyErrors() throws Exception {
		// given
		UUID userId = UUID.randomUUID();
		when(service.getConsistency(any(), any()))
				.thenThrow(new TicketException(TicketErrorCode.TICKET_USER_NOT_FOUND));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/users/{userId}/tickets/consistency", userId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TICKET-011"));
		mvc.perform(asAdmin(get("/api/v1/admin/users/not-a-uuid/tickets/consistency")))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("AO09는 어긋난 응모권 목록의 각 행에 같은 확인 시각을 담는다")
	void returnsInconsistencies() throws Exception {
		// given
		UUID userId = UUID.randomUUID();
		Instant checkedAt = Instant.parse("2026-10-15T00:00:00Z");
		TicketInconsistencyView view = new TicketInconsistencyView(UUID.randomUUID(), userId, "홍길동",
				TicketGrade.BRONZE, 2, 0, TicketStatus.AVAILABLE, null);
		when(service.getInconsistencies(any(), eq(userId), any())).thenReturn(
				new TicketInconsistencyReport(new PageResult<>(List.of(view), 1, 20, 1), checkedAt));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/tickets/inconsistencies")).param("userId", userId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].userName").value("홍길동"))
				.andExpect(jsonPath("$.data.items[0].ticketVersion").value(2))
				.andExpect(jsonPath("$.data.items[0].latestHistoryVersion").value(0))
				.andExpect(jsonPath("$.data.items[0].latestHistoryStatus").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].checkedAt").value("2026-10-15T00:00:00Z"));
	}

	@ParameterizedTest(name = "{0}은 400이다")
	@ValueSource(strings = {"/api/v1/admin/ticket-histories?page=0", "/api/v1/admin/ticket-histories?operationType=BOGUS",
			"/api/v1/admin/ticket-histories?eventId=nope", "/api/v1/admin/ticket-refunds?size=101",
			"/api/v1/admin/ticket-refunds?to=2026-10-01", "/api/v1/admin/tickets/inconsistencies?page=0",
			"/api/v1/admin/tickets/inconsistencies?userId=nope"})
	@DisplayName("AO05·AO08·AO09는 범위·형식이 잘못된 요청을 서비스 호출 없이 400으로 거절한다")
	void rejectsInvalidRequestsOnOtherEndpoints(String uri) throws Exception {
		// given
		// when
		// then
		mvc.perform(asAdmin(get(uri))).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}
}
