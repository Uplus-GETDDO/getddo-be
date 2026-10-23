package com.getddo.api.common.ratelimit;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * {@link RateLimited}가 붙은 Controller 메서드의 요청 빈도를 제한한다.
 *
 * <p>비응모 정책은 사용자 문맥 검증보다 먼저 실행해 한도를 넘은 요청이 DB에 닿기 전에 거절한다. 사용자는 {@code X-User-ID} 헤더로
 * 식별한다. 응모는 기존 문맥 검증 후 전용 원자 평가를 실행하고 기존 ACTIVE만 DB 없이 거절한다. 헤더가 없거나 UUID 형식이 아니면 제한하지 않고 보내 기존 사용자 문맥 검증이 401·400으로 처리하게 한다. 형식이
 * 틀린 값으로 키가 무한히 만들어지는 것도 막는다.</p>
 *
 * <p>제한기 자체가 실패하면(예: 공유 저장소 장애) 요청을 막지 않고 통과시킨다. 제한 때문에 응모·출석이 모두 멈추는 것보다
 * 낫기 때문이며 실패는 로그로 남긴다.</p>
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

	private static final String USER_ID_HEADER = "X-User-ID";
	private static final Pattern UUID_FORMAT = Pattern
			.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private final RateLimiter rateLimiter;
	private final RateLimitProperties properties;
	private final EntryRateLimitHandler entries;

	public RateLimitInterceptor(RateLimiter rateLimiter, RateLimitProperties properties) {
		this(rateLimiter, properties, null);
	}

	/** 응모의 검증·원자 평가는 전용 연결에 위임하고 다른 정책은 기존 경로를 유지한다. */
	public RateLimitInterceptor(RateLimiter rateLimiter, RateLimitProperties properties, EntryRateLimitHandler entries) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.entries = entries;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!properties.enabled() || !(handler instanceof HandlerMethod method)) {
			return true;
		}
		RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
		RateLimitProperties.Policy policy = annotation == null ? null : properties.policies().get(annotation.policy());
		String userId = request.getHeader(USER_ID_HEADER);
		if (policy == null || userId == null || !UUID_FORMAT.matcher(userId).matches()) {
			return true;
		}
		if ("entry".equals(annotation.policy()) && rateLimiter instanceof EntryRateLimiter entryLimiter && entries != null) {
			String scope = scopeOf(request, annotation);
			return entries.preHandle(request, method, entryLimiter, policy,
					scope == null ? null : UUID.fromString(scope));
		}
		String base = "rl:" + annotation.policy() + ":" + userId.toLowerCase();
		try {
			long retryAfter = 0;
			RateLimiter.Decision overall = rateLimiter.tryAcquire(base, policy.limit(), policy.window());
			if (!overall.allowed()) {
				retryAfter = overall.retryAfterSeconds();
			}
			String scope = scopeOf(request, annotation);
			if (retryAfter == 0 && scope != null && policy.scopedLimit() != null) {
				RateLimiter.Decision scoped = rateLimiter.tryAcquire(base + ":" + scope, policy.scopedLimit(),
						policy.window());
				if (!scoped.allowed()) {
					retryAfter = scoped.retryAfterSeconds();
				}
			}
			if (retryAfter > 0) {
				throw new RateLimitExceededException(retryAfter);
			}
		} catch (RateLimitExceededException exceeded) {
			throw exceeded;
		} catch (RuntimeException failure) {
			log.warn("요청 빈도 제한기를 쓰지 못해 제한하지 않고 통과시킨다. policy={}", annotation.policy(), failure);
		}
		return true;
	}

	/** 범위로 나눌 경로 변수의 값. 변수가 없거나 UUID 형식이 아니면 범위별 제한을 하지 않는다. */
	@SuppressWarnings("unchecked")
	private static String scopeOf(HttpServletRequest request, RateLimited annotation) {
		if (annotation.scopeVariable().isEmpty()) {
			return null;
		}
		Map<String, String> variables = (Map<String, String>) request
				.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		String value = variables == null ? null : variables.get(annotation.scopeVariable());
		return value != null && UUID_FORMAT.matcher(value).matches() ? value.toLowerCase() : null;
	}
}
