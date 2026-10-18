package com.getddo.core.attendance.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/** 관리자가 등록한 일일 출석 보상 정책 한 건. 적용 기간은 {@code [effectiveFrom, effectiveUntil)}이다. */
@Getter
public final class DailyAttendancePolicy {

	private final UUID id;
	private final int rewardTicketCount;
	private final Instant effectiveFrom;
	/** 적용 종료 시각. 현재 적용 중이거나 예약된 마지막 정책은 {@code null}. */
	private final Instant effectiveUntil;
	private final UUID createdBy;
	private final Instant createdAt;

	/**
	 * @throws IllegalArgumentException 보상 수량이 1 미만이거나, 종료 시각이 있는데 시작 시각보다 늦지 않은 경우.
	 *         {@code chk_reward_policy_quantity}·{@code chk_reward_policy_period}와 같은 범위다
	 */
	public DailyAttendancePolicy(UUID id, int rewardTicketCount, Instant effectiveFrom, Instant effectiveUntil,
			UUID createdBy, Instant createdAt) {
		this.id = Objects.requireNonNull(id, "id");
		if (rewardTicketCount < 1) {
			throw new IllegalArgumentException("일일 출석 보상 수량은 1 이상이어야 한다.");
		}
		this.rewardTicketCount = rewardTicketCount;
		this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
		if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
			throw new IllegalArgumentException("적용 종료 시각은 시작 시각보다 늦어야 한다.");
		}
		this.effectiveUntil = effectiveUntil;
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}
}
