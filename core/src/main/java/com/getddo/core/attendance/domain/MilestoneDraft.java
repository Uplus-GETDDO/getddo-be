package com.getddo.core.attendance.domain;

import lombok.Getter;

/** 등록할 연속 출석 단계. 범위 검증은 정책 등록 서비스가 한다. */
@Getter
public final class MilestoneDraft {

	private final int milestoneDays;
	private final int rewardTicketCount;

	public MilestoneDraft(int milestoneDays, int rewardTicketCount) {
		this.milestoneDays = milestoneDays;
		this.rewardTicketCount = rewardTicketCount;
	}
}
