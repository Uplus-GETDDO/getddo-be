package com.getddo.core.abuse.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AbuseHistoryRetryPolicyTest {
	private final AbuseHistoryRetryPolicy policy = new AbuseHistoryRetryPolicy(Duration.ofMinutes(10),
			List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2)));
	private final Instant first = Instant.parse("2026-10-22T20:00:00Z");

	@Test
	void increasesDelayAndCapsAtTwoMinutesWithoutResettingFirstFailure() {
		Instant now = first;
		for (int count = 0; count < 5; count++) {
			AbuseHistoryRetryState next = policy.afterFailure(claim(now, count == 0 ? null : first, count,
					AbuseHistoryQueue.NORMAL), now);
			assertThat(next.getFirstFailureAt()).isEqualTo(first);
			assertThat(next.getFailureCount()).isEqualTo(count + 1);
			assertThat(Duration.between(now, next.getNextAttemptAt()).toSeconds()).isEqualTo(new long[]{10, 30, 60, 120, 120}[count]);
			assertThat(next.getQueue()).isEqualTo(AbuseHistoryQueue.NORMAL);
			now = next.getNextAttemptAt();
		}
	}

	@Test
	void exactBudgetBoundaryMovesToBatchInsteadOfSchedulingNormalRetry() {
		Instant now = first.plusSeconds(480);
		AbuseHistoryRetryState next = policy.afterFailure(claim(now, first, 4, AbuseHistoryQueue.NORMAL), now);
		assertThat(next.getQueue()).isEqualTo(AbuseHistoryQueue.BATCH);
		assertThat(next.getNextAttemptAt()).isEqualTo(Instant.parse("2026-10-22T21:00:00Z"));
		assertThat(policy.exhausted(first, first.plusSeconds(599))).isFalse();
		assertThat(policy.exhausted(first, first.plusSeconds(600))).isTrue();
	}

	@Test
	void failedBatchWaitsForNextDayAndPreservesFailureOrigin() {
		Instant batch = Instant.parse("2026-10-22T21:00:00Z");
		AbuseHistoryRetryState next = policy.afterFailure(claim(batch, first, 8, AbuseHistoryQueue.BATCH), batch);
		assertThat(next.getQueue()).isEqualTo(AbuseHistoryQueue.BATCH);
		assertThat(next.getFirstFailureAt()).isEqualTo(first);
		assertThat(next.getNextAttemptAt()).isEqualTo(batch.plus(Duration.ofDays(1)));
	}

	private AbuseHistoryClaim claim(Instant now, Instant failureAt, int count, AbuseHistoryQueue queue) {
		return new AbuseHistoryClaim(UUID.randomUUID(), UUID.randomUUID(), null, now, failureAt, count, queue);
	}
}
