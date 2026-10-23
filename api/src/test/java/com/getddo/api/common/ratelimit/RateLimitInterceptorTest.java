package com.getddo.api.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import com.getddo.core.common.ratelimit.RateLimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

	private static final String USER = UUID.randomUUID().toString();
	private static final String SCOPE = UUID.randomUUID().toString();
	private static final Duration MINUTE = Duration.ofMinutes(1);

	/** 어노테이션이 붙은 메서드와 붙지 않은 메서드를 가진 시험용 컨트롤러. */
	static class Sample {
		@RateLimited(policy = "sample", scopeVariable = "scopeId")
		public void scoped() {
		}

		@RateLimited(policy = "plain")
		public void plain() {
		}

		@RateLimited(policy = "unknown")
		public void unknown() {
		}

		public void unlimited() {
		}
	}

	private final RateLimiter limiter = mock(RateLimiter.class);

	private static RateLimitProperties properties(boolean enabled) {
		return new RateLimitProperties(enabled, Map.of(
				"sample", new RateLimitProperties.Policy(5, MINUTE, 2),
				"plain", new RateLimitProperties.Policy(3, MINUTE, null)));
	}

	private static HandlerMethod handler(String name) throws Exception {
		return new HandlerMethod(new Sample(), Sample.class.getMethod(name));
	}

	private static MockHttpServletRequest request(String userId) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (userId != null) {
			request.addHeader("X-User-ID", userId);
		}
		request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("scopeId", SCOPE));
		return request;
	}

	private boolean pass(RateLimitInterceptor interceptor, MockHttpServletRequest request, String method)
			throws Exception {
		return interceptor.preHandle(request, new MockHttpServletResponse(), handler(method));
	}

	@Test
	@DisplayName("한도 안이면 사용자 전체와 범위별 한도를 모두 확인하고 통과시킨다")
	void passesWithinLimits() throws Exception {
		// given
		when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allow());
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		// when
		boolean proceed = pass(interceptor, request(USER), "scoped");
		// then
		assertThat(proceed).isTrue();
		verify(limiter).tryAcquire("rl:sample:" + USER, 5, MINUTE);
		verify(limiter).tryAcquire("rl:sample:" + USER + ":" + SCOPE, 2, MINUTE);
	}

	@Test
	@DisplayName("사용자 전체 한도를 넘으면 범위별 횟수는 세지 않고 429 예외에 남은 초를 담는다")
	void overallLimitDeniesWithoutCountingScope() throws Exception {
		// given
		when(limiter.tryAcquire("rl:sample:" + USER, 5, MINUTE)).thenReturn(RateLimiter.Decision.deny(42));
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		// when
		// then
		assertThatThrownBy(() -> pass(interceptor, request(USER), "scoped"))
				.isInstanceOfSatisfying(RateLimitExceededException.class,
						e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(42));
		verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.eq("rl:sample:" + USER + ":" + SCOPE),
				anyInt(), any());
	}

	@Test
	@DisplayName("범위별 한도를 넘으면 그 범위의 남은 초로 429다")
	void scopedLimitDenies() throws Exception {
		// given
		when(limiter.tryAcquire("rl:sample:" + USER, 5, MINUTE)).thenReturn(RateLimiter.Decision.allow());
		when(limiter.tryAcquire("rl:sample:" + USER + ":" + SCOPE, 2, MINUTE)).thenReturn(RateLimiter.Decision.deny(7));
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		// when
		// then
		assertThatThrownBy(() -> pass(interceptor, request(USER), "scoped"))
				.isInstanceOfSatisfying(RateLimitExceededException.class,
						e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(7));
	}

	@Test
	@DisplayName("범위별 한도가 없는 정책은 사용자 전체 한도만 확인한다")
	void policyWithoutScopedLimitChecksOnlyOverall() throws Exception {
		// given
		when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allow());
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		// when
		boolean proceed = pass(interceptor, request(USER), "plain");
		// then
		assertThat(proceed).isTrue();
		verify(limiter).tryAcquire("rl:plain:" + USER, 3, MINUTE);
		verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.contains(SCOPE), anyInt(), any());
	}

	@Test
	@DisplayName("제한 대상이 아니거나 비활성이거나 설정에 없는 정책이거나 사용자 헤더가 올바르지 않으면 제한기를 쓰지 않고 통과시킨다")
	void skipsWhenNotApplicable() throws Exception {
		// given
		RateLimitInterceptor enabled = new RateLimitInterceptor(limiter, properties(true));
		RateLimitInterceptor disabled = new RateLimitInterceptor(limiter, properties(false));
		// when
		// then
		assertThat(pass(enabled, request(USER), "unlimited")).isTrue();
		assertThat(pass(enabled, request(USER), "unknown")).isTrue();
		assertThat(pass(enabled, request(null), "plain")).isTrue();
		assertThat(pass(enabled, request("not-a-uuid"), "plain")).isTrue();
		assertThat(pass(enabled, request("x".repeat(500)), "plain")).isTrue();
		assertThat(pass(disabled, request(USER), "plain")).isTrue();
		assertThat(enabled.preHandle(request(USER), new MockHttpServletResponse(), "not a handler method")).isTrue();
		verify(limiter, never()).tryAcquire(anyString(), anyInt(), any());
	}

	@Test
	@DisplayName("범위 경로 변수가 UUID가 아니면 범위별 한도는 건너뛰고 사용자 전체 한도만 적용한다")
	void ignoresMalformedScope() throws Exception {
		// given
		when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allow());
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		MockHttpServletRequest request = request(USER);
		request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("scopeId", "../etc"));
		// when
		boolean proceed = pass(interceptor, request, "scoped");
		// then
		assertThat(proceed).isTrue();
		verify(limiter).tryAcquire("rl:sample:" + USER, 5, MINUTE);
		verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.contains("etc"), anyInt(), any());
	}

	@Test
	@DisplayName("경로 변수 속성이나 해당 키가 없으면 범위별 한도는 건너뛰고 사용자 전체 한도만 적용한다")
	void ignoresMissingScopeAttributeOrKey() throws Exception {
		// given
		when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allow());
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		MockHttpServletRequest withoutAttribute = new MockHttpServletRequest();
		withoutAttribute.addHeader("X-User-ID", USER);
		MockHttpServletRequest withoutKey = request(USER);
		withoutKey.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("other", SCOPE));
		// when
		boolean first = pass(interceptor, withoutAttribute, "scoped");
		boolean second = pass(interceptor, withoutKey, "scoped");
		// then
		assertThat(first).isTrue();
		assertThat(second).isTrue();
		verify(limiter, org.mockito.Mockito.times(2)).tryAcquire("rl:sample:" + USER, 5, MINUTE);
		verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.contains(SCOPE), anyInt(), any());
	}

	@Test
	@DisplayName("제한기가 실패하면 요청을 막지 않고 통과시킨다")
	void failsOpenWhenLimiterBreaks() throws Exception {
		// given
		when(limiter.tryAcquire(anyString(), anyInt(), any())).thenThrow(new IllegalStateException("redis down"));
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		// when
		boolean proceed = pass(interceptor, request(USER), "scoped");
		// then
		assertThat(proceed).isTrue();
	}

	@Test
	@DisplayName("사용자 ID는 대소문자를 구분하지 않고 같은 키로 센다")
	void userIdIsNormalized() throws Exception {
		// given
		when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(RateLimiter.Decision.allow());
		RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, properties(true));
		// when
		pass(interceptor, request(USER.toUpperCase()), "plain");
		// then
		verify(limiter).tryAcquire("rl:plain:" + USER, 3, MINUTE);
	}
}
