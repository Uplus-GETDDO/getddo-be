package com.getddo.api.attendance.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.user.domain.NamedValue;

/** 일일 출석 정책(AP01·AP02). 종료 시각이 없으면 다음 정책이 예약되기 전까지 계속 적용된다. */
public record DailyAttendancePolicyResponse(
		UUID id,
		int rewardTicketCount,
		Instant effectiveFrom,
		Instant effectiveUntil,
		UUID createdBy,
		String createdByName,
		Instant createdAt) {

	public static DailyAttendancePolicyResponse from(NamedValue<DailyAttendancePolicy> named) {
		DailyAttendancePolicy policy = named.getValue();
		return new DailyAttendancePolicyResponse(policy.getId(), policy.getRewardTicketCount(),
				policy.getEffectiveFrom(), policy.getEffectiveUntil(), policy.getCreatedBy(), named.getName(),
				policy.getCreatedAt());
	}
}
