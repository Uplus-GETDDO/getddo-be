package com.getddo.redis.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.getddo.core.common.ratelimit.RateLimiter.Decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Redis에서 {@link RedisRateLimiter}가 서버 여러 대의 횟수를 하나로 세는지 검증한다.
 *
 * <p>서버 한 대는 자기 Redis 연결을 가진 제한기 하나로 흉내 낸다. 서로 다른 연결 두 개가 같은 키를 세면 서버 두 대와
 * 같다.</p>
 */
class RedisRateLimiterIntegrationTest {

	private static final Duration MINUTE = Duration.ofMinutes(1);
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	private final List<LettuceConnectionFactory> factories = new ArrayList<>();
	private String key;

	@BeforeAll
	static void startRedis() {
		REDIS.start();
	}

	@AfterAll
	static void stopRedis() {
		REDIS.stop();
	}

	@BeforeEach
	void uniqueKey() {
		key = "test:" + System.nanoTime();
	}

	@AfterEach
	void closeConnections() {
		factories.forEach(LettuceConnectionFactory::destroy);
	}

	/** 새 Redis 연결을 가진 서버 한 대 분의 제한기. */
	private RedisRateLimiter server() {
		return new RedisRateLimiter(template(REDIS.getHost(), REDIS.getMappedPort(6379)));
	}

	private StringRedisTemplate template(String host, int port) {
		LettuceClientConfiguration client = LettuceClientConfiguration.builder()
				.commandTimeout(Duration.ofMillis(300)).build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(host, port), client);
		factory.afterPropertiesSet();
		factories.add(factory);
		return new StringRedisTemplate(factory);
	}

	@Test
	@DisplayName("한도까지 허용하고 넘으면 거절하며 남은 초는 1 이상 창 길이 이하다")
	void allowsUpToLimitThenDenies() {
		// given
		RedisRateLimiter limiter = server();
		// when
		List<Decision> decisions = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			decisions.add(limiter.tryAcquire(key, 3, MINUTE));
		}
		// then
		assertThat(decisions.subList(0, 3)).allMatch(Decision::isAllowed);
		assertThat(decisions.get(3).isAllowed()).isFalse();
		assertThat(decisions.get(3).getRetryAfterSeconds()).isBetween(1L, 60L);
	}

	@Test
	@DisplayName("서버 두 대가 같은 키를 나눠 세도 횟수가 합쳐져 한도를 넘으면 어느 서버에서든 거절한다")
	void sharesCountAcrossServers() {
		// given
		RedisRateLimiter serverA = server();
		RedisRateLimiter serverB = server();
		// when
		Decision a1 = serverA.tryAcquire(key, 3, MINUTE);
		Decision b1 = serverB.tryAcquire(key, 3, MINUTE);
		Decision a2 = serverA.tryAcquire(key, 3, MINUTE);
		Decision b2 = serverB.tryAcquire(key, 3, MINUTE);
		Decision a3 = serverA.tryAcquire(key, 3, MINUTE);
		// then
		assertThat(List.of(a1, b1, a2)).allMatch(Decision::isAllowed);
		assertThat(b2.isAllowed()).isFalse();
		assertThat(a3.isAllowed()).isFalse();
	}

	@Test
	@DisplayName("키가 다르면 따로 센다")
	void keysAreIndependent() {
		// given
		RedisRateLimiter limiter = server();
		limiter.tryAcquire(key + ":a", 1, MINUTE);
		// when
		Decision other = limiter.tryAcquire(key + ":b", 1, MINUTE);
		Decision sameAgain = limiter.tryAcquire(key + ":a", 1, MINUTE);
		// then
		assertThat(other.isAllowed()).isTrue();
		assertThat(sameAgain.isAllowed()).isFalse();
	}

	@Test
	@DisplayName("창이 끝나면 횟수가 처음부터 다시 센다")
	void windowResets() throws InterruptedException {
		// given
		RedisRateLimiter limiter = server();
		Duration shortWindow = Duration.ofMillis(800);
		limiter.tryAcquire(key, 1, shortWindow);
		assertThat(limiter.tryAcquire(key, 1, shortWindow).isAllowed()).isFalse();
		// when
		Thread.sleep(1_200);
		// then
		assertThat(limiter.tryAcquire(key, 1, shortWindow).isAllowed()).isTrue();
	}

	@Test
	@DisplayName("동시에 50건이 와도 한도 10건만 허용한다")
	void countsConcurrentRequestsExactly() throws Exception {
		// given
		List<RedisRateLimiter> servers = List.of(server(), server());
		ExecutorService pool = Executors.newFixedThreadPool(16);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			RedisRateLimiter limiter = servers.get(i % 2);
			results.add(pool.submit(() -> {
				start.await();
				return limiter.tryAcquire(key, 10, MINUTE).isAllowed();
			}));
		}
		// when
		start.countDown();
		long allowed = 0;
		for (Future<Boolean> result : results) {
			if (result.get(10, TimeUnit.SECONDS)) {
				allowed++;
			}
		}
		pool.shutdown();
		// then
		assertThat(allowed).isEqualTo(10);
	}

	@Test
	@DisplayName("키는 창 길이 안에 만료되고, 만료가 빠진 키는 다음 호출에서 만료를 다시 건다")
	void keyAlwaysExpires() {
		// given
		StringRedisTemplate template = template(REDIS.getHost(), REDIS.getMappedPort(6379));
		RedisRateLimiter limiter = new RedisRateLimiter(template);
		String redisKey = RedisRateLimiter.KEY_PREFIX + key;
		// when
		limiter.tryAcquire(key, 5, MINUTE);
		Long ttl = template.getExpire(redisKey, TimeUnit.MILLISECONDS);
		template.opsForValue().set(redisKey + ":stuck", "7");
		limiter.tryAcquire(key + ":stuck", 5, MINUTE);
		Long repaired = template.getExpire(RedisRateLimiter.KEY_PREFIX + key + ":stuck", TimeUnit.MILLISECONDS);
		// then
		assertThat(ttl).isBetween(1L, 60_000L);
		assertThat(repaired).isBetween(1L, 60_000L);
	}

	@Test
	@DisplayName("Redis에 닿지 못하면 오래 붙잡지 않고 예외를 던진다")
	void failsFastWhenRedisIsUnreachable() {
		// given
		RedisRateLimiter limiter = new RedisRateLimiter(template("127.0.0.1", 1));
		// when
		long started = System.nanoTime();
		// then
		assertThatThrownBy(() -> limiter.tryAcquire(key, 3, MINUTE)).isInstanceOf(RuntimeException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
	}
}
