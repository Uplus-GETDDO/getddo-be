package com.getddo.redis.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.abuse.domain.AbuseHistoryQueue;

class RedisAbuseHistoryJournalRepositoryTest {
	private final EntryRedisScriptExecutor executor = mock(EntryRedisScriptExecutor.class);
	private final RedisAbuseHistoryJournalRepository repository =
			new RedisAbuseHistoryJournalRepository(mock(StringRedisTemplate.class), executor);

	@Test
	void skipsCleanedIndexPageAndContinuesToRealJournal() {
		when(executor.execute(any(), anyList(), anyList())).thenReturn("{\"retry\":true}", claim(EntryBlockPayloadMapperTest.PAYLOAD));
		var result = repository.claimNext(AbuseHistoryQueue.NORMAL, null, Duration.ofSeconds(30), 100).orElseThrow();
		assertThat(result.isReadable()).isTrue();
		assertThat(result.getBlock().getId()).isEqualTo(result.getId());
	}

	@Test
	void retainsUnreadableOriginalWithoutSynthesizingBlock() {
		when(executor.execute(any(), anyList(), anyList())).thenReturn(claim("{broken"));
		assertThat(repository.claimNext(AbuseHistoryQueue.NORMAL, null, Duration.ofSeconds(30), 100)
				.orElseThrow().isReadable()).isFalse();
	}

	private String claim(String payload) {
		return JsonMapper.builder().build().writeValueAsString(Map.of("id", "00000000-0000-0000-0000-000000000001",
				"claimedAt", 1792699200123L, "state", "ACTIVE", "context", "DB_CONTEXT_CHECKED",
				"dbPersisted", "false", "payload", payload, "failureCount", "0"))
				.replace("\"failureCount\":\"0\"", "\"firstFailureAt\":null,\"failureCount\":\"0\"");
	}
}
