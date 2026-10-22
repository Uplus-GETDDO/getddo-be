package com.getddo.core.abuse.domain;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

/** 차단 시작 시각 기준 [from, to)와 선택적인 사용자·이벤트·요청 기능 조건이다. */
@Getter
public final class AbuseCaseFilter {

	private final UUID userId;
	private final UUID eventId;
	private final AbuseSourceType sourceType;
	private final Instant from;
	private final Instant to;

	/** 기간을 생략하면 해당 방향의 시간 제한을 두지 않는다. */
	public AbuseCaseFilter(UUID userId, UUID eventId, AbuseSourceType sourceType, Instant from, Instant to) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("from must be before to");
		}
		this.userId = userId;
		this.eventId = eventId;
		this.sourceType = sourceType;
		this.from = from;
		this.to = to;
	}
}
