package com.getddo.core.common.ratelimit;

import java.time.Instant;

import lombok.Getter;

/** 한 원자 평가에서 확정한 구간·거절 횟수다. Retry-After로 구간을 복원하지 않는다. */
@Getter
public final class EntryWindowSnapshot {
	private final Instant startedAt;
	private final Instant endsAt;
	private final int rejectedCount;

	public EntryWindowSnapshot(Instant startedAt, Instant endsAt, int rejectedCount) {
		if (startedAt == null || endsAt == null || !startedAt.isBefore(endsAt) || rejectedCount < 0) {
			throw new IllegalArgumentException("응모 집계 구간·거절 횟수가 올바르지 않다.");
		}
		this.startedAt = startedAt;
		this.endsAt = endsAt;
		this.rejectedCount = rejectedCount;
	}
}
