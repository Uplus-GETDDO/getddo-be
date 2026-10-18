package com.getddo.api.attendance.dto.response;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.getddo.core.attendance.domain.StreakPolicyRecord;

/** 연속 출석 정책 묶음(AP03·AP04). 적용월부터 다음 묶음이 시작되기 전까지 쓴다. */
public record StreakPolicySetResponse(
		UUID id,
		YearMonth effectiveMonth,
		List<Milestone> milestones,
		UUID createdBy,
		Instant createdAt) {

	public record Milestone(UUID id, int milestoneDays, int rewardTicketCount) {
	}

	public static StreakPolicySetResponse from(StreakPolicyRecord record) {
		return new StreakPolicySetResponse(record.getId(), YearMonth.from(record.getEffectiveMonth()),
				record.getMilestones().stream()
						.map(m -> new Milestone(m.getId(), m.getMilestoneDays(), m.getRewardTicketCount()))
						.toList(),
				record.getCreatedBy(), record.getCreatedAt());
	}
}
