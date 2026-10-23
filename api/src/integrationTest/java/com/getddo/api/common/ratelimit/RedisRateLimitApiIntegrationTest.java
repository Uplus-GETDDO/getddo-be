package com.getddo.api.common.ratelimit;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.common.ratelimit.RateLimiter;
import com.getddo.redis.ratelimit.RedisRateLimiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code store=redis}로 앱을 띄워 접속 설정부터 429 응답까지 실제 Redis로 연결해 검증한다. */
@ApiIntegrationTest
@TestPropertySource(properties = {
		"getddo.rate-limit.enabled=true",
		"getddo.rate-limit.store=redis",
		"getddo.rate-limit.policies.mission-submission.limit=20",
		"getddo.rate-limit.policies.mission-submission.scoped-limit=2"})
class RedisRateLimitApiIntegrationTest {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private RateLimiter rateLimiter;
	@Autowired
	private StringRedisTemplate redis;

	private static MockHttpServletRequestBuilder submit(UUID user, UUID mission) {
		return post("/api/v1/missions/{missionId}/submissions", mission)
				.header("X-User-ID", user.toString())
				.header("Idempotency-Key", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}");
	}

	@Test
	@DisplayName("store=redis면 Redis 제한기를 쓰고 한도를 넘은 요청은 429와 Retry-After를 돌려주며 횟수가 Redis에 남는다")
	void redisStoreLimitsAndStoresCounts() throws Exception {
		// given
		UUID user = UUID.randomUUID();
		UUID mission = UUID.randomUUID();
		// when
		// then
		assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);
		mvc.perform(submit(user, mission)).andExpect(status().isUnauthorized());
		mvc.perform(submit(user, mission)).andExpect(status().isUnauthorized());
		mvc.perform(submit(user, mission))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("COMMON-006"))
				.andExpect(header().exists("Retry-After"));
		// 같은 사용자의 다른 미션은 범위별 한도가 따로 세어져 사용자 전체 한도(20) 안이면 허용된다
		mvc.perform(submit(user, UUID.randomUUID())).andExpect(status().isUnauthorized());
		Set<String> keys = redis.keys("getddo:rate-limit:" + "*" + user + "*");
		assertThat(keys).isNotEmpty();
	}
}
