package com.getddo.redis.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.getddo.core.abuse.domain.AbuseHistoryClaim;
import com.getddo.core.abuse.domain.AbuseHistoryQueue;
import com.getddo.core.abuse.domain.AbuseHistoryRetryState;
import com.getddo.core.common.ratelimit.EntryBlock;
import com.getddo.core.common.ratelimit.EntryBlockPolicy;
import com.getddo.core.common.ratelimit.RateLimitPolicy;

/** 실제 Lua 소유권·인덱스 복구를 검증한다. 로컬에서는 실행하지 않고 소스만 컴파일한다. */
class RedisAbuseHistoryJournalIntegrationTest {
	private static final String PREFIX = "getddo:entry-block:";
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);
	private LettuceConnectionFactory factory;
	private StringRedisTemplate redis;
	private EntryRedisScriptExecutor producerExecutor;
	private RedisAbuseHistoryJournalRepository first;
	private RedisAbuseHistoryJournalRepository second;

	@BeforeAll
	static void start() {
		REDIS.start();
	}

	@AfterAll
	static void stop() {
		REDIS.stop();
	}

	@BeforeEach
	void setUp() {
		factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		factory.afterPropertiesSet();
		redis = new StringRedisTemplate(factory);
		try (RedisConnection connection = factory.getConnection()) {
			connection.serverCommands().flushDb();
		}
		producerExecutor = new EntryRedisScriptExecutor(redis);
		first = new RedisAbuseHistoryJournalRepository(redis);
		second = new RedisAbuseHistoryJournalRepository(redis);
	}

	@AfterEach
	void close() {
		first.destroy();
		second.destroy();
		producerExecutor.destroy();
		factory.destroy();
	}

	@Test
	@DisplayName("동시 소비자는 한 소유권만 얻으며 오래된 소유자는 완료하지 못한다")
	void simultaneousConsumersOnlyGrantOneLiveOwnerAndStaleOwnerCannotAcknowledge() throws Exception {
		// given
		EntryBlock block = produce();
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			// when
			Future<Optional<AbuseHistoryClaim>> a = pool.submit(() -> { start.await(); return claim(first); });
			Future<Optional<AbuseHistoryClaim>> b = pool.submit(() -> { start.await(); return claim(second); });
			start.countDown();
			List<Optional<AbuseHistoryClaim>> claims = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
			// then
			assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
			AbuseHistoryClaim old = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
			redis.opsForHash().put(journal(block), "leaseUntil", "0");
			redis.opsForZSet().add(PREFIX + "pending", block.getId().toString(), 0);
			AbuseHistoryClaim replacement = claim(second).orElseThrow();
			assertThat(first.acknowledge(old)).isFalse();
			assertThat(second.acknowledge(replacement)).isTrue();
			assertThat(redis.opsForHash().get(journal(block), "dbPersisted")).isEqualTo("true");
			assertThat(redis.opsForSet().isMember(PREFIX + "user:" + block.getUserId(), block.getId().toString())).isTrue();
		}
	}

	@Test
	@DisplayName("실제 저널 스캔으로 누락된 전역 대기 사용자 인덱스를 복구한다")
	void actualJournalScanRestoresMissingGlobalPendingAndUserIndexes() {
		// given
		EntryBlock block = produce();
		redis.delete(List.of(PREFIX + "journal-ids", PREFIX + "pending", PREFIX + "user:" + block.getUserId()));
		// when / then
		assertThat(first.recover(10).getPending()).isEqualTo(1);
		AbuseHistoryClaim claimed = claim(first).orElseThrow();
		assertThat(claimed.getId()).isEqualTo(block.getId());
		assertThat(claimed.getBlock().getBlockedAt()).isEqualTo(block.getBlockedAt());
	}

	@Test
	@DisplayName("배치 인덱스가 유실돼도 기존 배치 상태를 유지한다")
	void batchMetadataSurvivesIndexLossWithoutBecomingNormalRetry() {
		// given
		EntryBlock block = produce();
		AbuseHistoryClaim claimed = claim(first).orElseThrow();
		Instant now = first.now();
		// when / then
		assertThat(first.reschedule(claimed, new AbuseHistoryRetryState(now, 8, now.plusSeconds(3600), AbuseHistoryQueue.BATCH))).isTrue();
		redis.delete(List.of(PREFIX + "batch", PREFIX + "journal-ids"));
		assertThat(first.recover(10).getBatch()).isEqualTo(1);
		assertThat(claim(first)).isEmpty();
		assertThat(redis.opsForHash().get(journal(block), "failureCount")).isEqualTo("8");
	}

	@Test
	@DisplayName("인덱스와 payload가 없는 미활성 후보도 정리한다")
	void abandonedPreparedJournalIsRemovedEvenWithoutIndexesOrPayload() {
		// given
		String id = UUID.randomUUID().toString();
		redis.opsForHash().put(PREFIX + "journal:" + id, "state", "PREPARED");
		// when
		first.recover(10);
		// then
		assertThat(redis.hasKey(PREFIX + "journal:" + id)).isFalse();
	}

	@Test
	@DisplayName("만료된 미저장 원본은 보존하고 저장 확인 후 정리한다")
	void expiredUnsavedJournalSurvivesAndCompletedExpiredJournalIsCleaned() {
		// given
		EntryBlock block = produce();
		String original = redis.opsForHash().get(journal(block), "payload").toString();
		long until = block.getBlockedUntil().toEpochMilli();
		long at = block.getBlockedAt().toEpochMilli();
		// 실제 대기 없이 과거에 끝난 차단 원본을 구성한다. ID·근거는 그대로다.
		String expired = original.replace(Long.toString(until), Long.toString(at - 1000))
				.replace("\"blockedAt\":" + at, "\"blockedAt\":" + (at - 601000))
				.replace("\"detectedAt\":" + at, "\"detectedAt\":" + (at - 601000));
		redis.opsForHash().put(journal(block), "payload", expired);
		// when
		first.recover(10);
		// then
		assertThat(redis.hasKey(journal(block))).isTrue();
		assertThat(first.acknowledge(claim(first).orElseThrow())).isTrue();
		assertThat(redis.hasKey(journal(block))).isFalse();
	}

	private Optional<AbuseHistoryClaim> claim(RedisAbuseHistoryJournalRepository repository) {
		return repository.claimNext(AbuseHistoryQueue.NORMAL, null, Duration.ofSeconds(30), 10);
	}

	private EntryBlock produce() {
		RedisEntryRateLimiter limiter = new RedisEntryRateLimiter(producerExecutor);
		UUID user = UUID.randomUUID();
		RateLimitPolicy policy = new RateLimitPolicy(1, Duration.ofMinutes(1), null);
		EntryBlockPolicy settings = new EntryBlockPolicy(1, 1, Duration.ofMinutes(10), "entry-v1");
		limiter.evaluate(user, null, null, Instant.now(), policy, settings);
		return limiter.evaluate(user, null, null, Instant.now(), policy, settings).getBlock();
	}

	private String journal(EntryBlock block) {
		return PREFIX + "journal:" + block.getId();
	}
}
