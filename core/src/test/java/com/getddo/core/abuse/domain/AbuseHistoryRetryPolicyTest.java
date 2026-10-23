package com.getddo.core.abuse.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AbuseHistoryRetryPolicyTest {
	private final AbuseHistoryRetryPolicy policy = new AbuseHistoryRetryPolicy(Duration.ofMinutes(10),
			List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2)));
	private final Instant first = Instant.parse("2026-10-22T20:00:00Z");

	@Test
	@DisplayName("최초 실패를 유지하며 재시도 간격을 늘리고 2분으로 제한한다")
	void increasesDelayAndCapsAtTwoMinutesWithoutResettingFirstFailure() {
		// given
		Instant now = first;
		for (int count = 0; count < 5; count++) {
			// when
			AbuseHistoryRetryState next = policy.afterFailure(claim(now, count == 0 ? null : first, count,
					AbuseHistoryQueue.NORMAL), now);
			// then
			assertThat(next.getFirstFailureAt()).isEqualTo(first);
			assertThat(next.getFailureCount()).isEqualTo(count + 1);
			assertThat(Duration.between(now, next.getNextAttemptAt()).toSeconds()).isEqualTo(new long[]{10, 30, 60, 120, 120}[count]);
			assertThat(next.getQueue()).isEqualTo(AbuseHistoryQueue.NORMAL);
			now = next.getNextAttemptAt();
		}
	}

	@Test
	@DisplayName("10분 예산 경계에 도달하는 재시도는 배치로 넘긴다")
	void exactBudgetBoundaryMovesToBatchInsteadOfSchedulingNormalRetry() {
		// given
		Instant now = first.plusSeconds(480);
		// when
		AbuseHistoryRetryState next = policy.afterFailure(claim(now, first, 4, AbuseHistoryQueue.NORMAL), now);
		// then
		assertThat(next.getQueue()).isEqualTo(AbuseHistoryQueue.BATCH);
		assertThat(next.getNextAttemptAt()).isEqualTo(Instant.parse("2026-10-22T21:00:00Z"));
		assertThat(policy.exhausted(first, first.plusSeconds(599))).isFalse();
		assertThat(policy.exhausted(first, first.plusSeconds(600))).isTrue();
	}

	@Test
	@DisplayName("배치 실패는 최초 실패 상태를 유지하고 다음 날 재처리한다")
	void failedBatchWaitsForNextDayAndPreservesFailureOrigin() {
		// given
		Instant batch = Instant.parse("2026-10-22T21:00:00Z");
		// when
		AbuseHistoryRetryState next = policy.afterFailure(claim(batch, first, 8, AbuseHistoryQueue.BATCH), batch);
		// then
		assertThat(next.getQueue()).isEqualTo(AbuseHistoryQueue.BATCH);
		assertThat(next.getFirstFailureAt()).isEqualTo(first);
		assertThat(next.getNextAttemptAt()).isEqualTo(batch.plus(Duration.ofDays(1)));
	}

	private AbuseHistoryClaim claim(Instant now, Instant failureAt, int count, AbuseHistoryQueue queue) {
		return new AbuseHistoryClaim(UUID.randomUUID(), UUID.randomUUID(), null, now, failureAt, count, queue);
	}
}
