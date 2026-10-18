package com.getddo.api.stats.controller;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.stats.domain.DailyStats;
import com.getddo.core.stats.domain.DailyStats.Day;
import com.getddo.core.stats.exception.StatsErrorCode;
import com.getddo.core.stats.exception.StatsException;
import com.getddo.core.stats.service.DailyStatsService;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminStatsControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null,
			null, null, null, null);
	private static final String URL = "/api/v1/admin/stats/daily";

	private final DailyStatsService service = mock(DailyStatsService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminStatsController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}

	@Test
	@DisplayName("날짜별 집계와 활성 일반 사용자 수를 돌려주고 지급 근거 키를 모두 담는다")
	void returnsDailyStats() throws Exception {
		// given
		Map<GrantSourceType, Long> granted = new EnumMap<>(GrantSourceType.class);
		granted.put(GrantSourceType.ATTENDANCE, 10L);
		granted.put(GrantSourceType.MISSION, 3L);
		granted.put(GrantSourceType.GAME, 0L);
		when(service.daily(any(), eq(LocalDate.of(2026, 10, 11)), eq(LocalDate.of(2026, 10, 11))))
				.thenReturn(new DailyStats(
						List.of(new Day(LocalDate.of(2026, 10, 11), 4, 6, 9, granted, 8, 2)), 120));
		// when
		// then
		mvc.perform(asAdmin(get(URL).param("from", "2026-10-11").param("to", "2026-10-11")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalUserCount").value(120))
				.andExpect(jsonPath("$.data.days[0].date").value("2026-10-11"))
				.andExpect(jsonPath("$.data.days[0].entrantCount").value(4))
				.andExpect(jsonPath("$.data.days[0].entryCount").value(6))
				.andExpect(jsonPath("$.data.days[0].activeUserCount").value(9))
				.andExpect(jsonPath("$.data.days[0].grantedTickets.ATTENDANCE").value(10))
				.andExpect(jsonPath("$.data.days[0].grantedTickets.MISSION").value(3))
				.andExpect(jsonPath("$.data.days[0].grantedTickets.GAME").value(0))
				.andExpect(jsonPath("$.data.days[0].usedTickets").value(8))
				.andExpect(jsonPath("$.data.days[0].blockedCount").value(2));
	}

	@Test
	@DisplayName("기간 파라미터가 없거나 날짜 형식이 틀리면 400이고 서비스를 부르지 않는다")
	void rejectsMissingOrMalformedPeriod() throws Exception {
		mvc.perform(asAdmin(get(URL)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(URL).param("from", "2026-10-11")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(URL).param("from", "2026/10/11").param("to", "2026-10-12")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("서비스의 거절은 403 STATS-002, 400 STATS-001로 내려간다")
	void mapsServiceErrors() throws Exception {
		when(service.daily(any(), any(), any()))
				.thenThrow(new StatsException(StatsErrorCode.ADMIN_REQUIRED))
				.thenThrow(new StatsException(StatsErrorCode.INVALID_PERIOD));
		mvc.perform(asAdmin(get(URL).param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STATS-002"));
		mvc.perform(asAdmin(get(URL).param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STATS-001"));
	}
}
