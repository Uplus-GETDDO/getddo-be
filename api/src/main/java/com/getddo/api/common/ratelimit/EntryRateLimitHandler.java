package com.getddo.api.common.ratelimit;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.core.abuse.service.EntryAbuseEventService;
import com.getddo.core.common.time.TimeProvider;

/** 기존 문맥 검증을 집계 앞으로 연결하고 Redis 판정 오류와 사용자 검증 오류를 분리한다. */
@Component
public class EntryRateLimitHandler {
	private static final Logger log = LoggerFactory.getLogger(EntryRateLimitHandler.class);
	private final CurrentUserArgumentResolver users;
	private final EntryAbuseEventService events;
	private final EntryBlockProperties settings;
	private final TimeProvider time;

	public EntryRateLimitHandler(CurrentUserArgumentResolver users, EntryAbuseEventService events,
			EntryBlockProperties settings, TimeProvider time) {
		this.users = users;
		this.events = events;
		this.settings = settings;
		this.time = time;
	}

	/** CurrentUser handler에서만 호출한다. 평가 결과의 HTTP 변환은 Redis 예외 catch 바깥에서 수행한다. */
	public boolean preHandle(HttpServletRequest request, HandlerMethod method, EntryRateLimiter limiter,
			RateLimitProperties.Policy policy, UUID scope) {
		Instant received = time.now();
		MethodParameter parameter = java.util.Arrays.stream(method.getMethodParameters())
				.filter(users::supportsParameter).findFirst()
				.orElseThrow(() -> new IllegalStateException("응모 제한 handler에 CurrentUser가 필요하다."));
		ServletWebRequest web = new ServletWebRequest(request);
		UUID userId = users.validateHeaders(parameter, web);
		Optional<EntryLimitDecision> active;
		try {
			active = limiter.findActiveEntry(userId);
		} catch (RuntimeException failure) {
			warn("ACTIVE_READ", failure);
			// Redis 장애라도 업무 진입 전 기존 문맥 검증은 유지한다.
			users.resolveArgument(parameter, null, web, null);
			return true;
		}
		if (active.isPresent()) {
			throw new RateLimitExceededException(active.get().getRetryAfterSeconds());
		}
		// 미등록·불일치·사용자 DB 장애는 fail-open catch에 포함하지 않는다.
		users.resolveArgument(parameter, null, web, null);
		UUID verifiedEvent = null;
		if (scope != null) {
			try {
				verifiedEvent = events.findExistingId(scope);
			} catch (RuntimeException failure) {
				warn("EVENT_LOOKUP", failure);
			}
		}
		EntryLimitDecision decision;
		try {
			decision = limiter.evaluateEntry(userId, scope, verifiedEvent, received, policy, settings);
		} catch (RuntimeException failure) {
			warn("EVALUATION", failure);
			return true;
		}
		if (decision.getRecordingStatus() == EntryRecordingStatus.DENY_RECORDING_FAILED) {
			log.warn("응모 일반 429 유지: 차단 근거 기록에 실패했다.");
		}
		if (decision.getResult() != EntryLimitResult.ALLOWED) {
			throw new RateLimitExceededException(decision.getRetryAfterSeconds());
		}
		return true;
	}

	private static void warn(String stage, RuntimeException failure) {
		// 예외 원문·사용자·요청·근거를 남기지 않는다.
		log.warn("응모 자동 차단 처리 실패. stage={}, exception={}", stage, failure.getClass().getSimpleName());
	}
}
