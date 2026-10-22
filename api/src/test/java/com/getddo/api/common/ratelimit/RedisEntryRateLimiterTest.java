package com.getddo.api.common.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisEntryRateLimiterTest {
	private final EntryRedisScriptExecutor executor = mock(EntryRedisScriptExecutor.class);
	private final RedisEntryRateLimiter limiter = new RedisEntryRateLimiter(executor);
	private final UUID user = UUID.randomUUID();

	@Test
	@DisplayName("확인된 일반 429의 기록 실패 태그와 최초 재시도 시간을 복원한다")
	void recordingFailure() {
		// given
		when(executor.execute(any(), anyList(), anyList())).thenReturn("""
			{"result":"RATE_LIMITED","recordingStatus":"DENY_RECORDING_FAILED","retryAfterSeconds":17,
			 "userWindow":{"startedAt":1000,"endsAt":61000,"rejectedCount":2},"eventWindow":null}
			""");
		// when
		EntryLimitDecision result = limiter.evaluate(user, null, null, Instant.parse("2026-10-22T00:00:00Z"),
				new RateLimitProperties.Policy(20, Duration.ofMinutes(1), 10),
				new EntryBlockProperties(20, 10, Duration.ofMinutes(10), "v1"));
		// then
		assertThat(result.getResult()).isEqualTo(EntryLimitResult.RATE_LIMITED);
		assertThat(result.getRecordingStatus()).isEqualTo(EntryRecordingStatus.DENY_RECORDING_FAILED);
		assertThat(result.getRetryAfterSeconds()).isEqualTo(17);
		assertThat(result.getUserWindow().getStartedAt()).isEqualTo(Instant.ofEpochMilli(1000));
		verify(executor, times(1)).execute(any(), anyList(), anyList());
	}

	@Test
	@DisplayName("응답 유실·형식 오류를 일반 거절로 합성하거나 재전송하지 않는다")
	void lostResponse() {
		// given
		when(executor.execute(any(), anyList(), anyList())).thenThrow(new IllegalStateException());
		// when / then
		assertThatThrownBy(() -> limiter.active(user)).isInstanceOf(IllegalStateException.class);
		verify(executor, times(1)).execute(any(), anyList(), anyList());
	}

	@Test
	@DisplayName("ACTIVE 부재는 Optional.empty이며 키·인덱스를 쓰지 않는다")
	void absent() {
		// given
		when(executor.execute(any(), anyList(), anyList())).thenReturn("");
		// when / then
		assertThat(limiter.active(user)).isEmpty();
		verify(executor).execute(any(), eq(java.util.List.of("getddo:entry-block:user:"+user)), eq(java.util.List.of(user.toString())));
	}
}
