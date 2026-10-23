package com.getddo.redis.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.abuse.domain.AbuseHistoryBacklog;
import com.getddo.core.abuse.domain.AbuseHistoryClaim;
import com.getddo.core.abuse.domain.AbuseHistoryQueue;
import com.getddo.core.abuse.domain.AbuseHistoryRetryState;
import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;
import com.getddo.core.common.ratelimit.EntryBlock;

/** 저널 원본과 원자적 소유권을 보관한다. 대기 인덱스 유실은 실제 저널 SCAN으로 복원한다. */
@Repository
@Slf4j
@ConditionalOnProperty(name = "getddo.rate-limit.store", havingValue = "redis")
public class RedisAbuseHistoryJournalRepository implements AbuseHistoryJournalRepository, DisposableBean {
	private static final String PREFIX = RedisEntryRateLimiter.PREFIX;
	private static final String GLOBAL = PREFIX + "journal-ids";
	private static final String PENDING = PREFIX + "pending";
	private static final String BATCH = PREFIX + "batch";
	private static final byte[] CLAIM = EntryRedisScriptExecutor.script("abuse-history-claim");
	private static final byte[] ACK = EntryRedisScriptExecutor.script("abuse-history-ack");
	private static final byte[] RESCHEDULE = EntryRedisScriptExecutor.script("abuse-history-reschedule");
	private static final byte[] RECOVER = EntryRedisScriptExecutor.script("abuse-history-recover");
	private static final byte[] ORPHAN = EntryRedisScriptExecutor.script("abuse-history-orphan-user");
	private static final byte[] TIME = EntryRedisScriptExecutor.script("abuse-history-time");
	private final StringRedisTemplate redis;
	private final EntryRedisScriptExecutor executor;
	private final EntryBlockPayloadMapper payloads = new EntryBlockPayloadMapper();
	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	public RedisAbuseHistoryJournalRepository(StringRedisTemplate redis) {
		this(redis, new EntryRedisScriptExecutor(redis));
	}

	RedisAbuseHistoryJournalRepository(StringRedisTemplate redis, EntryRedisScriptExecutor executor) {
		this.redis = redis;
		this.executor = executor;
	}

	@Override
	public Optional<AbuseHistoryClaim> claimNext(AbuseHistoryQueue queue, Instant cutoff, Duration lease, int scanSize) {
		UUID token = UUID.randomUUID();
		while (true) {
			String result = executor.execute(CLAIM, List.of(PENDING, BATCH, GLOBAL),
					List.of(queue.name(), token.toString(), Long.toString(lease.toMillis()), Integer.toString(scanSize),
							cutoff == null ? "" : Long.toString(cutoff.toEpochMilli())));
			if (result.isEmpty()) {
				return Optional.empty();
			}
			JsonNode row = json.readTree(result);
			if (row.path("retry").asBoolean()) {
				continue;
			}
			UUID id = UUID.fromString(row.get("id").asText());
			Instant claimedAt = Instant.ofEpochMilli(row.get("claimedAt").asLong());
			try {
				if (!"ACTIVE".equals(row.path("state").asText())
						|| !"DB_CONTEXT_CHECKED".equals(row.path("context").asText())
						|| !"false".equals(row.path("dbPersisted").asText())) {
					throw new IllegalArgumentException("검증되지 않은 차단 저널이다.");
				}
				EntryBlock block = payloads.read(row.get("payload").asText());
				Instant first = row.path("firstFailureAt").isNull() ? null
						: Instant.ofEpochMilli(Long.parseLong(row.get("firstFailureAt").asText()));
				return Optional.of(new AbuseHistoryClaim(id, token, block, claimedAt, first,
						Integer.parseInt(row.get("failureCount").asText()), queue));
			} catch (RuntimeException unreadable) {
				log.warn("Abuse history journal unreadable: blockId={}", id);
				return Optional.of(new AbuseHistoryClaim(id, token, null, claimedAt, null, 0, queue));
			}
		}
	}

	@Override
	public boolean acknowledge(AbuseHistoryClaim claim) {
		EntryBlock block = claim.getBlock();
		return "1".equals(executor.execute(ACK,
				List.of(journal(claim.getId().toString()), user(block.getUserId().toString()), GLOBAL, PENDING, BATCH),
				List.of(claim.getId().toString(), claim.getToken().toString(), Long.toString(block.getBlockedUntil().toEpochMilli()))));
	}

	@Override
	public boolean reschedule(AbuseHistoryClaim claim, AbuseHistoryRetryState next) {
		return "1".equals(executor.execute(RESCHEDULE, List.of(journal(claim.getId().toString()), PENDING, BATCH),
				List.of(claim.getId().toString(), claim.getToken().toString(), Long.toString(next.getFirstFailureAt().toEpochMilli()),
						Integer.toString(next.getFailureCount()), Long.toString(next.getNextAttemptAt().toEpochMilli()), next.getQueue().name())));
	}

	@Override
	public Instant now() {
		return Instant.ofEpochMilli(Long.parseLong(executor.execute(TIME, List.of(), List.of())));
	}

	@Override
	public AbuseHistoryBacklog recover(int scanSize) {
		ScanOptions options = ScanOptions.scanOptions().count(scanSize).build();
		try (var ids = redis.opsForSet().scan(GLOBAL, options)) {
			ids.forEachRemaining(this::recoverOne);
		}
		// 전역 SET 자체가 사라져도 저널은 살아 있다. KEYS나 전역 인덱스만으로 복구하지 않는다.
		try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + "journal:*").count(scanSize).build())) {
			keys.forEachRemaining(key -> recoverOne(key.substring((PREFIX + "journal:").length())));
		}
		try (var keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + "user:*").count(scanSize).build())) {
			keys.forEachRemaining(key -> {
				String userId = key.substring((PREFIX + "user:").length());
				try (var ids = redis.opsForSet().scan(key, options)) {
					ids.forEachRemaining(id -> executor.execute(ORPHAN, List.of(journal(id), key), List.of(id, userId)));
				}
			});
		}
		return new AbuseHistoryBacklog(size(redis.opsForZSet().zCard(PENDING)), size(redis.opsForZSet().zCard(BATCH)),
				size(redis.opsForSet().size(GLOBAL)));
	}

	private void recoverOne(String id) {
		try {
			UUID expected = UUID.fromString(id);
			Object raw = redis.opsForHash().get(journal(id), "payload");
			String payload = raw == null ? "" : raw.toString();
			EntryBlock block = null;
			try {
				block = payloads.read(payload);
				if (!block.getId().equals(expected)) {
					block = null;
				}
			} catch (RuntimeException unreadable) {
				// PREPARED만 취소할 수 있다. 읽을 수 없는 ACTIVE 원본은 보존한다.
			}
			String userId = block == null ? "" : block.getUserId().toString();
			String outcome = executor.execute(RECOVER,
					List.of(journal(id), userId.isEmpty() ? GLOBAL : user(userId), GLOBAL, PENDING, BATCH),
					List.of(id, userId, payload, block == null ? "0" : Long.toString(block.getBlockedUntil().toEpochMilli()),
							block == null ? "0" : Long.toString(block.getBlockedAt().toEpochMilli())));
			if ("UNREADABLE".equals(outcome)) {
				log.warn("Abuse history recovery retained unreadable journal: blockId={}", expected);
			}
		} catch (RuntimeException failure) {
			log.warn("Abuse history journal recovery failed: exceptionType={}", failure.getClass().getName());
		}
	}

	private static long size(Long value) {
		return value == null ? 0 : value;
	}

	private static String journal(String id) {
		return PREFIX + "journal:" + id;
	}

	private static String user(String id) {
		return PREFIX + "user:" + id;
	}

	@Override
	public void destroy() {
		executor.destroy();
	}
}
