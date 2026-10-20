package com.getddo.core.ticket.domain;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

/** 관리자 응모권 반환 이력 조회 조건(AO08). 모든 조건은 선택이며 기간은 반환 시각 기준 {@code [from, to)}다. */
@Getter
public final class TicketRefundFilter {

	private final UUID eventId;
	private final UUID userId;
	private final Instant from;
	private final Instant to;

	private TicketRefundFilter(UUID eventId, UUID userId, Instant from, Instant to) {
		this.eventId = eventId;
		this.userId = userId;
		this.from = from;
		this.to = to;
	}

	/** 조회 조건을 만든다. 값은 검증하지 않으며 기간은 {@link #hasValidPeriod()}로 확인한다. */
	public static TicketRefundFilter of(UUID eventId, UUID userId, Instant from, Instant to) {
		return new TicketRefundFilter(eventId, userId, from, to);
	}

	/** 시작과 끝이 모두 있으면 시작이 끝보다 앞서는지 확인한다. */
	public boolean hasValidPeriod() {
		return from == null || to == null || from.isBefore(to);
	}
}
