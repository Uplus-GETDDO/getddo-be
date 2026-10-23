package com.getddo.redis.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.getddo.core.common.ratelimit.EntryBlockPolicy;
import com.getddo.core.common.ratelimit.EntryLimitDecision;
import com.getddo.core.common.ratelimit.EntryRateLimiter;
import com.getddo.core.common.ratelimit.RateLimitPolicy;
import com.getddo.core.common.ratelimit.RateLimiter;

/**
 * Redis에서 세는 고정 창 방식의 {@link RateLimiter}. 서버가 여러 대여도 같은 키의 횟수가 하나로 합쳐진다.
 *
 * <p>횟수 증가와 창 만료 설정을 Lua 스크립트 하나로 실행해 동시 요청에서도 정확히 세고, 키가 만료 시각 없이 남는 일이
 * 없게 한다. 창은 첫 요청 시점에 Redis 서버 시계로 시작해 서버 사이의 시계 차이와 무관하다. 한도를 넘은 요청도 횟수는
 * 올라가지만 키는 창이 끝나면 사라지므로 값이 무한히 쌓이지 않는다.</p>
 *
 * <p>Redis에 닿지 못하면 {@link org.springframework.dao.DataAccessException} 등 런타임 예외를 던진다. 호출하는
 * 인터셉터가 이를 받아 요청을 제한하지 않고 통과시킨다.</p>
 */
public class RedisRateLimiter implements EntryRateLimiter, DisposableBean {

	static final String KEY_PREFIX = "getddo:rate-limit:";

	/** 반환: {현재 횟수, 창이 끝날 때까지 남은 밀리초}. 만료가 빠진 키는 창 길이로 다시 건다. */
	private static final String SCRIPT = """
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			local ttl = redis.call('PTTL', KEYS[1])
			if ttl < 0 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[1])
			  ttl = tonumber(ARGV[1])
			end
			return {count, ttl}
			""";

	@SuppressWarnings("rawtypes")
	private final DefaultRedisScript<List> script = new DefaultRedisScript<>(SCRIPT, List.class);
	private final StringRedisTemplate redis;
	private final RedisEntryRateLimiter entries;

	public RedisRateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
		this.entries = new RedisEntryRateLimiter(new EntryRedisScriptExecutor(redis));
	}

	@Override
	public Decision tryAcquire(String key, int limit, Duration window) {
		long windowMillis = window.toMillis();
		@SuppressWarnings("unchecked")
		List<Long> result = (List<Long>) redis.execute(script, List.of(KEY_PREFIX + key),
				String.valueOf(windowMillis));
		if (result == null || result.size() != 2) {
			throw new IllegalStateException("Redis 빈도 제한 스크립트가 예상하지 못한 값을 돌려줬다.");
		}
		long count = result.get(0);
		if (count <= limit) {
			return Decision.allow();
		}
		long ttlMillis = Math.min(Math.max(result.get(1), 0), windowMillis);
		return Decision.deny((long) Math.ceil(ttlMillis / 1000.0));
	}
	@Override
	public Optional<EntryLimitDecision> findActiveEntry(UUID userId) {
		return entries.active(userId);
	}

	@Override
	public EntryLimitDecision evaluateEntry(UUID userId, UUID eventScope,
			UUID verifiedEventId, Instant occurredAt, RateLimitPolicy policy,
			EntryBlockPolicy blockProperties) {
		return entries.evaluate(userId, eventScope, verifiedEventId, occurredAt, policy, blockProperties);
	}

	@Override
	public void destroy() {
		entries.destroy();
	}

}
