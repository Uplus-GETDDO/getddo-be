package com.getddo.core.abuse.domain;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/** 한 저장 실패 뒤에 보존할 최초 실패·횟수·다음 실행 시각이다. */
@Getter
public final class AbuseHistoryRetryState {
	private final Instant firstFailureAt;
	private final int failureCount;
	private final Instant nextAttemptAt;
	private final AbuseHistoryQueue queue;

	/** 배치 전환도 기존 실패 이력을 보존하며 차단 payload를 변경하지 않는다. */
	public AbuseHistoryRetryState(Instant firstFailureAt, int failureCount, Instant nextAttemptAt,
			AbuseHistoryQueue queue) {
		this.firstFailureAt = Objects.requireNonNull(firstFailureAt, "firstFailureAt");
		this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
		this.queue = Objects.requireNonNull(queue, "queue");
		if (failureCount < 1 || nextAttemptAt.isBefore(firstFailureAt)) {
			throw new IllegalArgumentException("저장 재시도 상태가 올바르지 않다.");
		}
		this.failureCount = failureCount;
	}
}
