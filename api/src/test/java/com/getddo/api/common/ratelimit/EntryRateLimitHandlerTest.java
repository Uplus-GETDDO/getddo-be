package com.getddo.api.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.core.abuse.service.EntryAbuseEventService;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.user.domain.User;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EntryRateLimitHandlerTest {
	private static final UUID USER = UUID.randomUUID();
	private static final UUID EVENT = UUID.randomUUID();
	private final CurrentUserArgumentResolver users = mock(CurrentUserArgumentResolver.class);
	private final EntryAbuseEventService events = mock(EntryAbuseEventService.class);
	private final EntryRateLimiter limiter = mock(EntryRateLimiter.class);
	private final RateLimitProperties.Policy policy = new RateLimitProperties.Policy(20, Duration.ofMinutes(1), 10);
	private final EntryBlockProperties settings = new EntryBlockProperties(20, 10, Duration.ofMinutes(10), "v1");
	private final EntryRateLimitHandler handler = new EntryRateLimitHandler(users, events, settings,
			new TimeProvider(Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneOffset.UTC)));

	static class Sample {
		public void entry(@CurrentUser User user) { }
	}

	private boolean run() throws Exception {
		when(users.supportsParameter(any())).thenReturn(true);
		when(users.validateHeaders(any(), any())).thenReturn(USER);
		return handler.preHandle(new MockHttpServletRequest(),
				new HandlerMethod(new Sample(), Sample.class.getMethod("entry", User.class)), limiter, policy, EVENT);
	}

	private EntryLimitDecision decision(EntryLimitResult result, EntryRecordingStatus status) {
		return new EntryLimitDecision(result, status, result == EntryLimitResult.ALLOWED ? 0 : 37, null, null, null);
	}

	@Test
	@DisplayName("기존 ACTIVE의 빠른 거절은 사용자 DB·평가·이벤트 조회를 호출하지 않는다")
	void fastDeny() {
		// given
		when(limiter.findActiveEntry(USER)).thenReturn(Optional.of(decision(EntryLimitResult.BLOCKED, EntryRecordingStatus.NOT_APPLICABLE)));
		// when / then
		assertThatThrownBy(this::run).isInstanceOfSatisfying(RateLimitExceededException.class,
				e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(37));
		verify(users, never()).resolveArgument(any(), any(), any(), any());
		verify(limiter, never()).evaluateEntry(any(), any(), any(), any(), any(), any());
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("ACTIVE 조회 장애면 문맥 검증 후 통과하고 이번 요청에서 재평가하지 않는다")
	void activeFailure() throws Exception {
		// given
		when(limiter.findActiveEntry(USER)).thenThrow(new IllegalStateException());
		// when / then
		assertThat(run()).isTrue();
		verify(users).resolveArgument(any(), isNull(), any(), isNull());
		verify(limiter, never()).evaluateEntry(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("사용자 문맥 오류는 fail-open하지 않고 Redis 쓰기 전에 종료한다")
	void userFailure() {
		// given
		when(limiter.findActiveEntry(USER)).thenReturn(Optional.empty());
		when(users.resolveArgument(any(), any(), any(), any())).thenThrow(new BusinessException(CommonErrorCode.USER_ROLE_MISMATCH));
		// when / then
		assertThatThrownBy(this::run).isInstanceOf(BusinessException.class);
		verify(limiter, never()).evaluateEntry(any(), any(), any(), any(), any(), any());
		verifyNoInteractions(events);
	}

	@Test
	@DisplayName("일반 429 이후 기록 실패는 최초 Retry-After를 유지하고 같은 요청을 재집계하지 않는다")
	void recordingFailureStill429() {
		// given
		when(limiter.findActiveEntry(USER)).thenReturn(Optional.empty());
		when(events.findExistingId(EVENT)).thenReturn(EVENT);
		when(limiter.evaluateEntry(eq(USER), eq(EVENT), eq(EVENT), any(), eq(policy), eq(settings)))
				.thenReturn(decision(EntryLimitResult.RATE_LIMITED, EntryRecordingStatus.DENY_RECORDING_FAILED));
		// when / then
		assertThatThrownBy(this::run).isInstanceOfSatisfying(RateLimitExceededException.class,
				e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(37));
		verify(limiter, times(1)).evaluateEntry(eq(USER), eq(EVENT), eq(EVENT), any(), eq(policy), eq(settings));
	}

	@Test
	@DisplayName("평가 응답 유실은 fail-open하고 재전송하지 않는다")
	void evaluationFailure() throws Exception {
		// given
		when(limiter.findActiveEntry(USER)).thenReturn(Optional.empty());
		when(limiter.evaluateEntry(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException());
		// when / then
		assertThat(run()).isTrue();
		verify(limiter, times(1)).evaluateEntry(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("이벤트 확인 장애는 NULL로 기록하고 확인된 차단 429를 유지한다")
	void eventFailureKeepsResult() {
		// given
		when(limiter.findActiveEntry(USER)).thenReturn(Optional.empty());
		when(events.findExistingId(EVENT)).thenThrow(new IllegalStateException());
		when(limiter.evaluateEntry(eq(USER), eq(EVENT), isNull(), any(), any(), any()))
				.thenReturn(decision(EntryLimitResult.BLOCKED, EntryRecordingStatus.RECORDED));
		// when / then
		assertThatThrownBy(this::run).isInstanceOf(RateLimitExceededException.class);
	}
}
