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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.Entry;
import com.getddo.core.entry.domain.EntryParticipant;
import com.getddo.core.entry.domain.EntryStatus;
import com.getddo.core.entry.domain.EntrySummary;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.exception.EntryException;
import com.getddo.core.entry.service.AdminEntryQueryService;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.user.domain.NamedValue;
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

class AdminEntryControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null,
			null, null, null, null);
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID EVENT_ID = UUID.randomUUID();
	private static final Instant AT = Instant.parse("2026-10-14T03:00:00Z");

	private final AdminEntryQueryService service = mock(AdminEntryQueryService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminEntryController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}

	private static EntrySummary accepted() {
		EntryParticipant participant = new EntryParticipant(UUID.randomUUID(), EVENT_ID, USER_ID, 3, AT);
		return new EntrySummary(Entry.accepted(UUID.randomUUID(), participant, Map.of(TicketGrade.GOLD, 3L), AT),
				"가을 이벤트");
	}

	private static EntrySummary rejected() {
		return new EntrySummary(Entry.rejected(UUID.randomUUID(), EVENT_ID, USER_ID, Map.of(TicketGrade.SILVER, 4L),
				EntryErrorCode.TICKET_LIMIT_EXCEEDED, AT.plusSeconds(60)), "가을 이벤트");
	}

	@Test
	@DisplayName("AO06은 응모 결과 필드에 사용자와 응모자 ID를 더해 돌려주고 거절 건의 응모자 ID는 없다")
	void returnsEntriesWithUserAndParticipant() throws Exception {
		// given
		when(service.findEntries(any(), any(), any()))
				.thenReturn(new PageResult<>(List.of(new NamedValue<>(rejected(), "홍길동"),
						new NamedValue<>(accepted(), null)), 1, 20, 2));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/entries")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.items[0].status").value("REJECTED"))
				.andExpect(jsonPath("$.data.items[0].userId").value(USER_ID.toString()))
				.andExpect(jsonPath("$.data.items[0].userName").value("홍길동"))
				.andExpect(jsonPath("$.data.items[1].userName").hasJsonPath())
				.andExpect(jsonPath("$.data.items[1].userName").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].participantId").hasJsonPath())
				.andExpect(jsonPath("$.data.items[0].participantId").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].rejectionCode").value("ENTRY-007"))
				.andExpect(jsonPath("$.data.items[0].acceptedAt").hasJsonPath())
				.andExpect(jsonPath("$.data.items[0].acceptedAt").value(nullValue()))
				.andExpect(jsonPath("$.data.items[1].status").value("ACCEPTED"))
				.andExpect(jsonPath("$.data.items[1].userId").value(USER_ID.toString()))
				.andExpect(jsonPath("$.data.items[1].participantId").isNotEmpty())
				.andExpect(jsonPath("$.data.items[1].deductedTicketsByGrade.GOLD").value(3))
				.andExpect(jsonPath("$.data.items[1].acceptedAt").value("2026-10-14T03:00:00Z"));
	}

	@Test
	@DisplayName("AO06은 필터와 페이지를 서비스에 넘긴다")
	void passesFilterAndPage() throws Exception {
		// given
		when(service.findEntries(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 2, 5, 0));
		// when
		mvc.perform(asAdmin(get("/api/v1/admin/entries")
				.param("page", "2").param("size", "5").param("userId", USER_ID.toString())
				.param("eventId", EVENT_ID.toString()).param("status", "REJECTED")
				.param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-02T00:00:00Z")))
				.andExpect(status().isOk());
		// then
		ArgumentCaptor<AdminEntryFilter> filter = ArgumentCaptor.forClass(AdminEntryFilter.class);
		verify(service).findEntries(any(), filter.capture(), eq(new PageQuery(2, 5)));
		assertThat(filter.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(filter.getValue().getEventId()).isEqualTo(EVENT_ID);
		assertThat(filter.getValue().getStatus()).isEqualTo(EntryStatus.REJECTED);
		assertThat(filter.getValue().getFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
		assertThat(filter.getValue().getTo()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
	}

	@Test
	@DisplayName("AO06은 잘못된 기간·페이지 크기·상태를 400으로 거절하고 서비스를 부르지 않는다")
	void rejectsInvalidParameters() throws Exception {
		mvc.perform(asAdmin(get("/api/v1/admin/entries")
				.param("from", "2026-10-02T00:00:00Z").param("to", "2026-10-01T00:00:00Z")))
				.andExpect(status().isBadRequest());
		mvc.perform(asAdmin(get("/api/v1/admin/entries").param("size", "101"))).andExpect(status().isBadRequest());
		mvc.perform(asAdmin(get("/api/v1/admin/entries").param("status", "UNKNOWN")))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("관리자가 아니라는 서비스의 거절은 403 ENTRY-010으로 내려간다")
	void mapsAdminRequired() throws Exception {
		when(service.findEntries(any(), any(), any())).thenThrow(new EntryException(EntryErrorCode.ADMIN_REQUIRED));
		mvc.perform(asAdmin(get("/api/v1/admin/entries")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ENTRY-010"));
	}

	@Test
	@DisplayName("AO07은 응모자를 계약 필드로 돌려준다")
	void returnsParticipants() throws Exception {
		// given
		UUID participantId = UUID.randomUUID();
		when(service.findParticipants(any(), eq(EVENT_ID), eq(new PageQuery(1, 20))))
				.thenReturn(new PageResult<>(List.of(new NamedValue<>(
						new EntryParticipant(participantId, EVENT_ID, USER_ID, 7, AT), "홍길동")), 1, 20, 1));
		// when
		// then
		mvc.perform(asAdmin(get("/api/v1/admin/events/{eventId}/participants", EVENT_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(participantId.toString()))
				.andExpect(jsonPath("$.data.items[0].eventId").value(EVENT_ID.toString()))
				.andExpect(jsonPath("$.data.items[0].userId").value(USER_ID.toString()))
				.andExpect(jsonPath("$.data.items[0].userName").value("홍길동"))
				.andExpect(jsonPath("$.data.items[0].usedTicketCount").value(7))
				.andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-14T03:00:00Z"));
	}

	@Test
	@DisplayName("AO07의 없는 이벤트는 404 ENTRY-004로 내려간다")
	void mapsEventNotFound() throws Exception {
		when(service.findParticipants(any(), any(), any()))
				.thenThrow(new EntryException(EntryErrorCode.EVENT_NOT_FOUND));
		mvc.perform(asAdmin(get("/api/v1/admin/events/{eventId}/participants", EVENT_ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ENTRY-004"));
	}
}
