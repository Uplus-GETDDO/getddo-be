package com.getddo.core.ticket.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/** 한 사용자의 응모권 현재 상태와 처리 이력의 정합성 확인 결과(AO04). 같은 기준 시점에서 구한 값이다. */
@Getter
public final class TicketConsistencyResult {

	private final UUID userId;
	/** 확인한 사용자의 현재 이름. 표시용이며 동일인 판단은 {@code userId}로 한다. */
	private final String userName;
	/** 확인 시각에 사용할 수 있는 응모권 수. */
	private final long availableCount;
	/** 어긋난 응모권. 없으면 빈 목록이다. */
	private final List<TicketInconsistencyView> inconsistencies;
	private final Instant checkedAt;

	public TicketConsistencyResult(UUID userId, String userName, long availableCount,
			List<TicketInconsistencyView> inconsistencies, Instant checkedAt) {
		this.userId = Objects.requireNonNull(userId, "userId");
		this.userName = userName;
		this.availableCount = availableCount;
		this.inconsistencies = List.copyOf(inconsistencies);
		this.checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
	}

	/** 어긋난 응모권이 없으면 true다. */
	public boolean isConsistent() {
		return inconsistencies.isEmpty();
	}
}
