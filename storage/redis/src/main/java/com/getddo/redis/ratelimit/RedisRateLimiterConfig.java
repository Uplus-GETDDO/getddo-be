package com.getddo.redis.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.getddo.core.common.ratelimit.RateLimiter;

/** Redis 저장소의 제한기만 구성한다. HTTP 처리와 설정 값 바인딩은 API에 둔다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "getddo.rate-limit.store", havingValue = "redis")
public class RedisRateLimiterConfig {
	/** 사용자 정의 제한기가 있으면 기존 교체 계약을 유지한다. */
	@Bean
	@ConditionalOnMissingBean(RateLimiter.class)
	RateLimiter redisRateLimiter(StringRedisTemplate redis) {
		return new RedisRateLimiter(redis);
	}
}
