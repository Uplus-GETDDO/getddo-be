package com.getddo.api.common.ratelimit;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.core.abuse.service.EntryAbuseEventService;
import com.getddo.core.common.time.TimeProvider;

/** 기존 문맥 검증을 집계 앞으로 연결하고 Redis 판정 오류와 사용자 검증 오류를 분리한다. */
@Component
@Slf4j
public class EntryRateLimitHandler {
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
		MethodParameter parameter = Arrays.stream(method.getMethodParameters())
				.filter(users::supportsParameter).findFirst()
				.orElseThrow(() -> new IllegalStateException("응모 제한 handler에 CurrentUser가 필요하다."));
		ServletWebRequest web = new ServletWebRequest(request);
		// 기존 차단 조회도 헤더 형식 검증 뒤에 수행한다. 여기서는 사용자 DB를 조회하지 않는다.
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
		// 최초 DB 문맥 검증을 거쳐 활성화된 차단만 반환되므로, 차단 중 반복 요청은 DB 없이 거절한다.
		if (active.isPresent()) {
			throw new RateLimitExceededException(active.get().getRetryAfterSeconds());
		}
		// 미등록·불일치·사용자 DB 장애는 fail-open catch에 포함하지 않는다.
		// 검증 결과는 기존 resolver가 요청에 보관하므로 Controller 인자 해석에서 같은 사용자를 다시 조회하지 않는다.
		users.resolveArgument(parameter, null, web, null);
		// scope는 집계 키일 뿐 실재 이벤트를 보장하지 않는다. 이력의 FK 연결용 ID만 별도로 확인한다.
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
			// 응답 유실 시 Redis에서 이미 반영됐을 수도 있다. 같은 요청을 재평가하면 중복 집계되므로 통과시킨다.
			warn("EVALUATION", failure);
			return true;
		}
		// 일반 한도 초과가 확인된 뒤 기록만 실패한 경우에는, 판정 자체를 모르는 장애와 달리 원래 429를 유지한다.
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
