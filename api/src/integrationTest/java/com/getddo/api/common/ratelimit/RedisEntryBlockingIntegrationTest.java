package com.getddo.api.common.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import static org.assertj.core.api.Assertions.*;

/** 실제 Redis Lua·여러 연결·명령 실패를 검증한다. 로컬 실행은 사용자 요청에 따라 수행하지 않는다. */
class RedisEntryBlockingIntegrationTest {
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);
	private final List<LettuceConnectionFactory> factories = new ArrayList<>();
	private final List<ControlledExecutor> executors = new ArrayList<>();
	private final UUID user = UUID.randomUUID();
	private final UUID event = UUID.randomUUID();
	private final RateLimitProperties.Policy policy = new RateLimitProperties.Policy(1, Duration.ofMinutes(1), 1);
	private final EntryBlockProperties settings = new EntryBlockProperties(2, 2, Duration.ofMinutes(10), "entry-v1");
	private StringRedisTemplate redis;
	private long now;

	@BeforeAll
	static void start() {
		REDIS.start();
	}

	@AfterAll
	static void stop() {
		REDIS.stop();
	}

	@BeforeEach
	void setup() {
		redis = template();
		now = System.currentTimeMillis();
	}
	@AfterEach
	void close() {
		executors.forEach(ControlledExecutor::destroy);
		factories.forEach(LettuceConnectionFactory::destroy);
	}

	private StringRedisTemplate template() {
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
				LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build());
		factory.afterPropertiesSet();
		factories.add(factory);
		return new StringRedisTemplate(factory);
	}

	private RedisEntryRateLimiter server() {
		ControlledExecutor executor = new ControlledExecutor(template());
		executors.add(executor);
		return new RedisEntryRateLimiter(executor);
	}

	private EntryLimitDecision request(RedisEntryRateLimiter limiter) {
		return limiter.evaluate(user, event, event, Instant.ofEpochMilli(now), policy, settings);
	}

	@Test
	@DisplayName("여러 Redis 연결의 동시 임계값 도달은 동일한 ACTIVE 한 건과 최초 근거만 만든다")
	void concurrentActivation() throws Exception {
		// given
		RedisEntryRateLimiter first = server();
		RedisEntryRateLimiter second = server();
		request(first);
		request(first);
		try (java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(8)) {
			List<Callable<EntryLimitDecision>> jobs = java.util.stream.IntStream.range(0, 20)
					.mapToObj(i -> (Callable<EntryLimitDecision>) () -> request(i % 2 == 0 ? first : second)).toList();
			// when
			List<EntryLimitDecision> results = new ArrayList<>();
			for (java.util.concurrent.Future<EntryLimitDecision> future : pool.invokeAll(jobs)) {
				results.add(future.get());
			}
			// then
			assertThat(results).allMatch(d -> d.getResult() == EntryLimitResult.BLOCKED);
			assertThat(results).extracting(d -> d.getBlock().getId()).containsOnly(results.getFirst().getBlock().getId());
			assertThat(redis.opsForSet().members("getddo:entry-block:user:"+user)).hasSize(1);
			assertThat(results.getFirst().getBlock().getEvidence().getTriggerScope()).isEqualTo(AbuseTriggerScope.USER);
		}
	}

	@Test
	@DisplayName("차단 중 빠른 조회와 재평가는 카운터·저널·TTL·시각을 바꾸지 않는다")
	void activeIsReadOnly() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		request(limiter);
		EntryBlock block = request(limiter).getBlock();
		String journal = "getddo:entry-block:journal:"+block.getId();
		Object payload = redis.opsForHash().get(journal, "payload");
		java.util.Map<Object,Object> counters = redis.opsForHash().entries("getddo:entry-block:counters:"+user);
		now += 30000;
		// when
		EntryLimitDecision fast = limiter.active(user).orElseThrow();
		EntryLimitDecision evaluated = request(limiter);
		// then
		assertThat(fast.getRetryAfterSeconds()).isEqualTo(570);
		assertThat(evaluated.getBlock().getId()).isEqualTo(block.getId());
		assertThat(redis.opsForHash().get(journal, "payload")).isEqualTo(payload);
		assertThat(redis.opsForHash().entries("getddo:entry-block:counters:"+user)).isEqualTo(counters);
		assertThat(redis.getExpire(journal)).isEqualTo(-1);
	}

	@Test
	@DisplayName("미저장 차단이 만료되어도 과거 payload를 보존하고 새 차단을 별도 ID로 만든다")
	void retainsExpiredUnsavedBlock() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		request(limiter);
		EntryBlock old = request(limiter).getBlock();
		now += 600000;
		// when
		assertThat(limiter.active(user)).isEmpty();
		assertThat(request(limiter).getResult()).isEqualTo(EntryLimitResult.ALLOWED);
		request(limiter);
		EntryBlock fresh = request(limiter).getBlock();
		// then
		assertThat(fresh.getId()).isNotEqualTo(old.getId());
		assertThat(redis.opsForSet().members("getddo:entry-block:user:"+user)).hasSize(2);
		assertThat(redis.opsForHash().get("getddo:entry-block:journal:"+old.getId(), "state")).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("전체 제한이 먼저 거절해도 이벤트 구간과 거절 횟수를 함께 기록한다")
	void overallFirstStillCountsEvent() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		now += 1000;
		UUID another = UUID.randomUUID();
		// when
		EntryLimitDecision denied = limiter.evaluate(user, another, null, Instant.ofEpochMilli(now), policy, settings);
		// then
		assertThat(denied.getUserWindow().getRejectedCount()).isEqualTo(1);
		assertThat(denied.getEventWindow().getRejectedCount()).isEqualTo(1);
		assertThat(denied.getEventWindow().getStartedAt()).isEqualTo(Instant.ofEpochMilli(now));
	}

	@Test
	@DisplayName("저장 대기 타입 오류는 확인된 일반 429를 유지하고 ACTIVE를 만들지 않는다")
	void pendingFailure() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		request(limiter);
		// 이 테스트 전용 Redis의 파생 키에 타입 오류를 주입한다.
		redis.delete("getddo:entry-block:pending");
		redis.opsForValue().set("getddo:entry-block:pending", "wrong-type");
		try {
			// when
			EntryLimitDecision denied = request(limiter);
			// then
			assertThat(denied.getResult()).isEqualTo(EntryLimitResult.RATE_LIMITED);
			assertThat(denied.getRecordingStatus()).isEqualTo(EntryRecordingStatus.DENY_RECORDING_FAILED);
			assertThat(limiter.active(user)).isEmpty();
		} finally {
			redis.delete("getddo:entry-block:pending");
		}
	}

	@Test
	@DisplayName("PREPARED 이후 각 명령 실패는 미활성 후보를 취소하고 다음 별도 요청만 재평가한다")
	void preparedFailureRecovery() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		request(limiter);
		ControlledExecutor executor = executors.getLast();
		executor.failBefore = "if not safe('SADD', KEYS[4], ARGV[5]) then return cancel() end";
		// when
		EntryLimitDecision failed = request(limiter);
		// then
		assertThat(failed.getRecordingStatus()).isEqualTo(EntryRecordingStatus.DENY_RECORDING_FAILED);
		assertThat(limiter.active(user)).isEmpty();
		assertThat(redis.opsForSet().members("getddo:entry-block:user:"+user)).isEmpty();
		executor.failBefore = null;
		EntryLimitDecision next = request(limiter);
		assertThat(next.getResult()).isEqualTo(EntryLimitResult.BLOCKED);
		assertThat(next.getBlock().getEvidence().getRejectedCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("EVENT 단독 임계값은 이벤트 근거만 보존한다")
	void eventOnlyThreshold() {
		// given
		RedisEntryRateLimiter limiter = server();
		RateLimitProperties.Policy limits = new RateLimitProperties.Policy(20, Duration.ofSeconds(7), 1);
		EntryBlockProperties block = new EntryBlockProperties(20, 1, Duration.ofMinutes(10), "v2");
		limiter.evaluate(user, event, event, Instant.ofEpochMilli(now), limits, block);
		// when
		EntryLimitDecision result = limiter.evaluate(user, event, event, Instant.ofEpochMilli(now), limits, block);
		// then
		assertThat(result.getBlock().getEvidence().getTriggerScope()).isEqualTo(AbuseTriggerScope.EVENT);
		assertThat(result.getBlock().getEvidence().getWindowEndsAt()).isEqualTo(Instant.ofEpochMilli(now+7000));
		assertThat(result.getBlock().getEvidence().getRequestLimit()).isEqualTo(1);
	}

	@Test
	@DisplayName("거절 상태 HSET 전 실패는 양쪽 상태를 그대로 두고 일반 429를 유지한다")
	void counterWriteFailure() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		java.util.Map<Object,Object> before = redis.opsForHash().entries("getddo:entry-block:counters:"+user);
		ControlledExecutor executor = executors.getLast();
		executor.counterFailure = true;
		// when
		EntryLimitDecision denied = request(limiter);
		// then
		assertThat(denied.getRecordingStatus()).isEqualTo(EntryRecordingStatus.DENY_RECORDING_FAILED);
		assertThat(redis.opsForHash().entries("getddo:entry-block:counters:"+user)).isEqualTo(before);
		executor.counterFailure = false;
		assertThat(request(limiter).getUserWindow().getRejectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("양쪽 거절 상태 교체 후 실패는 같은 요청을 재생하지 않고 다음 요청에서 임계값 이상을 재판정한다")
	void afterCounterFailure() {
		// given
		RedisEntryRateLimiter limiter = server();
		request(limiter);
		request(limiter);
		ControlledExecutor executor = executors.getLast();
		executor.afterCounterFailure = true;
		// when
		EntryLimitDecision denied = request(limiter);
		// then
		assertThat(denied.getUserWindow().getRejectedCount()).isEqualTo(2);
		assertThat(denied.getEventWindow().getRejectedCount()).isEqualTo(2);
		assertThat(denied.getRecordingStatus()).isEqualTo(EntryRecordingStatus.DENY_RECORDING_FAILED);
		executor.afterCounterFailure = false;
		assertThat(request(limiter).getBlock().getEvidence().getRejectedCount()).isEqualTo(3);
	}

	/** 테스트에서만 Redis now와 명령 실패 위치를 주입하며 운영 리소스는 변경하지 않는다. */
	private final class ControlledExecutor extends EntryRedisScriptExecutor {
		private String failBefore;
		private boolean counterFailure;
		private boolean afterCounterFailure;
		private ControlledExecutor(StringRedisTemplate source) { super(source); }
		@Override
		String execute(byte[] script, List<String> keys, List<String> args) {
			String lua = new String(script, StandardCharsets.UTF_8).replace(
					"local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)", "local now = "+now);
			if (counterFailure) {
				lua = lua.replace("if not safe('HSET', KEYS[2], unpack(fields)) then return failed() end",
						"if true then return failed() end");
			}
			if (afterCounterFailure) {
				lua = lua.replace("if not safe('PEXPIREAT', KEYS[2], expiresAt) then return failed() end",
						"if true then return failed() end");
			}
			if (failBefore != null) { lua = lua.replace(failBefore, "if true then return cancel() end"); }
			return super.execute(lua.getBytes(StandardCharsets.UTF_8), keys, args);
		}
	}
}
