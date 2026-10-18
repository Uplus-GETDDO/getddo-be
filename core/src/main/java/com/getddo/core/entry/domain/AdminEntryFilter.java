package com.getddo.core.entry.domain;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

/**
 * 관리자 응모 내역 조회 조건(AO06). 값이 없는 조건은 적용하지 않는다.
 *
 * <p>기간은 결과를 기록한 시각 기준의 {@code [from, to)}다.</p>
 */
@Getter
public final class AdminEntryFilter {

	private final UUID userId;
	private final UUID eventId;
	private final EntryStatus status;
	private final Instant from;
	private final Instant to;

	/**
	 * @throws IllegalArgumentException 기간의 시작이 끝보다 늦거나 같은 경우
	 */
	public AdminEntryFilter(UUID userId, UUID eventId, EntryStatus status, Instant from, Instant to) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new IllegalArgumentException("from must be before to");
		}
		this.userId = userId;
		this.eventId = eventId;
		this.status = status;
		this.from = from;
		this.to = to;
	}
}
