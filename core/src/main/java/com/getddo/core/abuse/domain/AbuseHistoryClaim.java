package com.getddo.core.abuse.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.getddo.core.common.ratelimit.EntryBlock;
import lombok.Getter;

/** 저장 작업 한 건의 임시 소유권이다. 차단 자체의 종료 시각과 처리 소유권 만료는 별개다. */
@Getter
public final class AbuseHistoryClaim {
	private final UUID id;
	private final UUID token;
	private final EntryBlock block;
	private final Instant claimedAt;
	private final Instant firstFailureAt;
	private final int failureCount;
	private final AbuseHistoryQueue queue;

	/** 최초 차단과 재시도 상태를 변경 없이 전달한다. block이 없으면 손상된 원본을 보존하며 건너뛴다. */
	public AbuseHistoryClaim(UUID id, UUID token, EntryBlock block, Instant claimedAt,
			Instant firstFailureAt, int failureCount, AbuseHistoryQueue queue) {
		this.id = Objects.requireNonNull(id, "id");
		this.token = Objects.requireNonNull(token, "token");
		this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
		this.queue = Objects.requireNonNull(queue, "queue");
		if (failureCount < 0 || (block != null && !id.equals(block.getId()))
				|| (firstFailureAt == null) != (failureCount == 0)) {
			throw new IllegalArgumentException("차단 이력의 소유권·재시도 상태가 올바르지 않다.");
		}
		this.block = block;
		this.firstFailureAt = firstFailureAt;
		this.failureCount = failureCount;
	}

	/** 손상된 payload는 DB 이력으로 합성하지 않는다. */
	public boolean isReadable() {
		return block != null;
	}
}
