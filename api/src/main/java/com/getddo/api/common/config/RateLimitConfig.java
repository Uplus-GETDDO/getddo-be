package com.getddo.api.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.getddo.api.common.ratelimit.InMemoryRateLimiter;
import com.getddo.api.common.ratelimit.EntryBlockProperties;
import com.getddo.api.common.ratelimit.EntryRateLimitHandler;
import com.getddo.api.common.ratelimit.RateLimitInterceptor;
import com.getddo.api.common.ratelimit.RateLimitProperties;
import com.getddo.api.common.ratelimit.RateLimiter;
import com.getddo.api.common.ratelimit.RedisRateLimiter;
import com.getddo.core.common.time.TimeProvider;

/**
 * 요청 빈도 제한을 MVC에 등록한다.
 *
 * <p>제한기는 {@code getddo.rate-limit.store}로 고른다. {@code memory}(기본)는 서버 메모리, {@code redis}는 서버 사이에
 * 횟수를 합치는 Redis 구현이다. 다른 {@link RateLimiter} 빈을 직접 등록하면 기본 구현은 만들지 않는다.</p>
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, EntryBlockProperties.class})
public class RateLimitConfig implements WebMvcConfigurer {

	private final RateLimiter rateLimiter;
	private final RateLimitProperties properties;
	private final EntryRateLimitHandler entries;

	public RateLimitConfig(RateLimiter rateLimiter, RateLimitProperties properties, EntryRateLimitHandler entries,
			EntryBlockProperties blockProperties) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.entries = entries;
		RateLimitProperties.Policy entry = properties.policies().get("entry");
		if (entry != null) {
			blockProperties.validateWindow(entry.window());
		}
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(rateLimiter, properties, entries)).addPathPatterns("/api/**");
	}

	/** 기본으로 쓰는 서버 메모리 제한기. 서버가 한 대일 때만 한도가 정확하다. */
	@Configuration
	@ConditionalOnProperty(name = "getddo.rate-limit.store", havingValue = "memory", matchIfMissing = true)
	static class MemoryRateLimiterConfig {

		@Bean
		@ConditionalOnMissingBean(RateLimiter.class)
		RateLimiter inMemoryRateLimiter(TimeProvider timeProvider) {
			return new InMemoryRateLimiter(timeProvider);
		}
	}

	/** 서버 여러 대의 횟수를 합치는 Redis 제한기. 접속 정보는 {@code spring.data.redis.*}를 따른다. */
	@Configuration
	@ConditionalOnProperty(name = "getddo.rate-limit.store", havingValue = "redis")
	static class RedisRateLimiterConfig {

		@Bean
		@ConditionalOnMissingBean(RateLimiter.class)
		RateLimiter redisRateLimiter(StringRedisTemplate redis) {
			return new RedisRateLimiter(redis);
		}
	}
}
