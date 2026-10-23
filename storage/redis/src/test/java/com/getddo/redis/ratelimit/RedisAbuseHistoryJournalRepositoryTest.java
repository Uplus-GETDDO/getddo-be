package com.getddo.redis.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.abuse.domain.AbuseHistoryClaim;
import com.getddo.core.abuse.domain.AbuseHistoryQueue;

class RedisAbuseHistoryJournalRepositoryTest {
	private final EntryRedisScriptExecutor executor = mock(EntryRedisScriptExecutor.class);
	private final RedisAbuseHistoryJournalRepository repository =
			new RedisAbuseHistoryJournalRepository(mock(StringRedisTemplate.class), executor);

	@Test
	@DisplayName("정리한 인덱스 묶음 뒤의 실제 저널까지 조회한다")
	void skipsCleanedIndexPageAndContinuesToRealJournal() {
		// given
		when(executor.execute(any(), anyList(), anyList())).thenReturn("{\"retry\":true}", claim(EntryBlockPayloadMapperTest.PAYLOAD));
		// when
		AbuseHistoryClaim result = repository.claimNext(AbuseHistoryQueue.NORMAL, null, Duration.ofSeconds(30), 100).orElseThrow();
		// then
		assertThat(result.isReadable()).isTrue();
		assertThat(result.getBlock().getId()).isEqualTo(result.getId());
	}

	@Test
	@DisplayName("손상된 원본을 합성하지 않고 보존한다")
	void retainsUnreadableOriginalWithoutSynthesizingBlock() {
		// given
		when(executor.execute(any(), anyList(), anyList())).thenReturn(claim("{broken"));
		// when / then
		assertThat(repository.claimNext(AbuseHistoryQueue.NORMAL, null, Duration.ofSeconds(30), 100)
				.orElseThrow().isReadable()).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("복구 중 중복 ID를 건너뛰되 인덱스 없는 원본도 처리한다")
	void recoveryDeduplicatesIndexesButStillReadsOrphanJournal() {
		// given
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		SetOperations<String, String> sets = mock(SetOperations.class);
		HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
		ZSetOperations<String, String> sorted = mock(ZSetOperations.class);
		when(redis.opsForSet()).thenReturn(sets);
		when(redis.opsForHash()).thenReturn(hashes);
		when(redis.opsForZSet()).thenReturn(sorted);
		String prefix = "getddo:entry-block:";
		String indexed = "00000000-0000-0000-0000-000000000001";
		String orphan = "00000000-0000-0000-0000-000000000003";
		Cursor<String> globalIds = cursor(indexed, indexed);
		Cursor<String> journals = cursor(prefix + "journal:" + indexed, prefix + "journal:" + orphan, prefix + "journal:" + orphan);
		Cursor<String> users = cursor();
		when(sets.scan(eq(prefix + "journal-ids"), any(ScanOptions.class))).thenReturn(globalIds);
		when(redis.scan(any(ScanOptions.class))).thenReturn(journals, users);
		when(hashes.get(prefix + "journal:" + indexed, "payload")).thenReturn(EntryBlockPayloadMapperTest.PAYLOAD);
		when(hashes.get(prefix + "journal:" + orphan, "payload"))
				.thenReturn(EntryBlockPayloadMapperTest.PAYLOAD.replace(indexed, orphan));
		when(executor.execute(any(), anyList(), anyList())).thenReturn("RECOVERED");
		// when
		new RedisAbuseHistoryJournalRepository(redis, executor).recover(100);
		// then
		verify(hashes, times(1)).get(prefix + "journal:" + indexed, "payload");
		verify(hashes, times(1)).get(prefix + "journal:" + orphan, "payload");
		verify(executor, times(2)).execute(any(), anyList(), anyList());
	}

	@SuppressWarnings("unchecked")
	private Cursor<String> cursor(String... values) {
		Cursor<String> cursor = mock(Cursor.class);
		doAnswer(invocation -> {
			Consumer<String> action = invocation.getArgument(0);
			List.of(values).forEach(action);
			return null;
		}).when(cursor).forEachRemaining(any());
		return cursor;
	}

	private String claim(String payload) {
		return JsonMapper.builder().build().writeValueAsString(Map.of("id", "00000000-0000-0000-0000-000000000001",
				"claimedAt", 1792699200123L, "state", "ACTIVE", "context", "DB_CONTEXT_CHECKED",
				"dbPersisted", "false", "payload", payload, "failureCount", "0"))
				.replace("\"failureCount\":\"0\"", "\"firstFailureAt\":null,\"failureCount\":\"0\"");
	}
}
