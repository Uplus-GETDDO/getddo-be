package com.getddo.core.ticket.domain;

import java.time.Instant;

import lombok.Getter;

/** 응모권 이력 조회 조건(T02). 모든 조건은 선택이며 기간은 {@code [from, to)}로 적용한다. */
@Getter
public final class TicketHistoryFilter {

	private static final TicketHistoryFilter NONE = new TicketHistoryFilter(null, null, null);

	/** 전체면 null. */
	private final TicketOperationType operationType;
	/** 시작 시각(포함). 제한 없으면 null. */
	private final Instant from;
	/** 끝 시각(제외). 제한 없으면 null. */
	private final Instant to;

	private TicketHistoryFilter(TicketOperationType operationType, Instant from, Instant to) {
		this.operationType = operationType;
		this.from = from;
		this.to = to;
	}

	/** 조회 조건을 만든다. 조건 사이의 모순은 조회 서비스가 검증한다. */
	public static TicketHistoryFilter of(TicketOperationType operationType, Instant from, Instant to) {
		return new TicketHistoryFilter(operationType, from, to);
	}

	/** 조건이 없는 조회 조건. 전체를 조회한다. */
	public static TicketHistoryFilter none() {
		return NONE;
	}

	/** 시작과 끝이 모두 있으면 시작이 끝보다 앞서는지 확인한다. */
	public boolean hasValidPeriod() {
		return from == null || to == null || from.isBefore(to);
	}
}
