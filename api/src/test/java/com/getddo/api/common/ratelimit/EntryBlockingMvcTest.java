package com.getddo.api.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.abuse.service.EntryAbuseEventService;
import com.getddo.core.common.ratelimit.InMemoryRateLimiter;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.user.domain.*;
import com.getddo.core.user.repository.UserRepository;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB·Redis 없이 기존 resolver·인터셉터·오류 변환을 함께 검증하는 MVC 단위 테스트다. */
class EntryBlockingMvcTest {
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID EVENT = UUID.randomUUID();
	private final UserRepository users = mock(UserRepository.class);
	private final EntryAbuseEventService events = mock(EntryAbuseEventService.class);
	private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(
			new TimeProvider(Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC)));
	private MockMvc mvc;

	@RestController
	static class Sample {
		@RateLimited(policy="entry", scopeVariable="eventId")
		@PostMapping("/api/events/{eventId}/entries")
		public Map<String, String> entry(@CurrentUser User user, @PathVariable UUID eventId) {
			return Map.of("id", user.getId().toString());
		}
		@RateLimited(policy="attendance")
		@PostMapping("/api/attendance")
		public Map<String, String> attendance(@CurrentUser User user) { return Map.of("id", user.getId().toString()); }
	}

	@BeforeEach
	void setup() {
		User user = new User(USER_ID, "테스트", UserRole.USER, UserStatus.ACTIVE, Membership.VIP,
				null, null, null, null, null, null);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		when(events.findExistingId(EVENT)).thenReturn(EVENT);
		CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver(new UserService(users));
		EntryRateLimitHandler handler = new EntryRateLimitHandler(resolver, events,
				new EntryBlockProperties(2, 2, Duration.ofMinutes(10), "v1"),
				new TimeProvider(Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC)));
		RateLimitProperties properties = new RateLimitProperties(true, Map.of(
				"entry", new RateLimitProperties.Policy(1, Duration.ofMinutes(1), 1),
				"attendance", new RateLimitProperties.Policy(1, Duration.ofMinutes(1), null)));
		mvc = MockMvcBuilders.standaloneSetup(new Sample()).setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(resolver).addInterceptors(new RateLimitInterceptor(limiter, properties, handler)).build();
	}

	private MockHttpServletRequestBuilder entry(String event) {
		return post("/api/events/"+event+"/entries").header("X-User-ID", USER_ID)
				.header("X-User-Role", "USER").header("X-User-Membership", "vip");
	}

	@Test
	@DisplayName("인터셉터 검증 결과를 resolver가 재사용해 요청당 사용자 DB 조회가 한 번이다")
	void sharesUser() throws Exception {
		// given / when
		mvc.perform(entry(EVENT.toString())).andExpect(status().isOk());
		// then
		verify(users, times(1)).findById(USER_ID);
	}

	@Test
	@DisplayName("일반 429와 차단 429는 같은 코드이며 차단 중에는 DB 조회 없이 남은 차단 시간을 반환한다")
	void blocksAndSkipsDb() throws Exception {
		// given
		mvc.perform(entry(EVENT.toString())).andExpect(status().isOk());
		mvc.perform(entry(EVENT.toString())).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "60")).andExpect(jsonPath("code").value("COMMON-006"));
		mvc.perform(entry(EVENT.toString())).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "600"));
		clearInvocations(users, events);
		// when / then
		mvc.perform(entry(UUID.randomUUID().toString())).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "600")).andExpect(jsonPath("code").value("COMMON-006"));
		verifyNoInteractions(users, events);
		mvc.perform(post("/api/attendance").header("X-User-ID", USER_ID).header("X-User-Role", "USER")
				.header("X-User-Membership", "vip")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("미등록 사용자는 기존 USER-002·401을 유지하고 차단 상태를 생성하지 않는다")
	void invalidContextDoesNotCount() throws Exception {
		// given
		when(users.findById(USER_ID)).thenReturn(Optional.empty());
		// when / then
		for (int i = 0; i < 4; i++) {
			mvc.perform(entry(EVENT.toString())).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("USER-002"));
		}
		assertThat(limiter.findActiveEntry(USER_ID)).isEmpty();
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("DB 역할·멤버십 불일치는 각각 기존 403·409로 종료하고 Redis 집계를 시작하지 않는다")
	void mismatchedContext() throws Exception {
		// given / when / then
		mvc.perform(post("/api/events/"+EVENT+"/entries").header("X-User-ID", USER_ID)
				.header("X-User-Role", "ADMIN")).andExpect(status().isForbidden())
				.andExpect(jsonPath("code").value("USER-004"));
		mvc.perform(post("/api/events/"+EVENT+"/entries").header("X-User-ID", USER_ID)
				.header("X-User-Role", "USER").header("X-User-Membership", "vvip"))
				.andExpect(status().isConflict()).andExpect(jsonPath("code").value("USER-006"));
		assertThat(limiter.findActiveEntry(USER_ID)).isEmpty();
		verifyNoInteractions(events);
		mvc.perform(entry(EVENT.toString())).andExpect(status().isOk());
	}

	@Test
	@DisplayName("형식 오류는 DB 조회 전에 거절하며 중복 사용자 헤더도 집계하지 않는다")
	void malformedHeaders() throws Exception {
		// given / when / then
		mvc.perform(entry(EVENT.toString()).header("X-User-ID", USER_ID)).andExpect(status().isBadRequest());
		verifyNoInteractions(users, events);
		assertThat(limiter.findActiveEntry(USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("이벤트 UUID 형식 오류 요청도 USER 일반 제한을 평가하되 허용이면 기존 400을 유지한다")
	void invalidEventOnlyUser() throws Exception {
		// given / when / then
		mvc.perform(entry("invalid")).andExpect(status().isBadRequest());
		mvc.perform(entry("invalid")).andExpect(status().isTooManyRequests());
		mvc.perform(entry("invalid")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "600"));
		assertThat(limiter.findActiveEntry(USER_ID).orElseThrow().getBlock().getEventId()).isNull();
		verifyNoInteractions(events);
	}
}
