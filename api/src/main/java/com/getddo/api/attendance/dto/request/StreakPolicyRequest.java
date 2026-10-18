package com.getddo.api.attendance.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.getddo.core.attendance.domain.MilestoneDraft;

/**
 * 연속 출석 정책 등록(AP04) 요청. 적용 시점은 서버가 다음 KST 기준월 1일로 정한다.
 *
 * @param milestones 단계 일수 오름차순이고 중복이 없는 단계
 */
public record StreakPolicyRequest(@NotEmpty List<@NotNull @Valid MilestoneWrite> milestones) {

	/**
	 * @param milestoneDays 연속 출석 기준 일수(1~28)
	 * @param rewardTicketCount 단계 달성 보상 수량. 1 이상
	 */
	public record MilestoneWrite(@NotNull @Min(1) @Max(28) Integer milestoneDays,
			@NotNull @Min(1) Integer rewardTicketCount) {
	}

	public List<MilestoneDraft> toDrafts() {
		return milestones.stream().map(m -> new MilestoneDraft(m.milestoneDays(), m.rewardTicketCount())).toList();
	}
}
