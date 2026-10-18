package com.getddo.db.attendance.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.getddo.db.common.entity.BaseEntity;

/** 연속 출석 정책 묶음의 단계 Entity. 한 번 저장하면 바꾸지 않는다. */
@Getter
@Entity
@Table(name = "attendance_streak_policies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreakPolicyEntity extends BaseEntity {

	@Column(name = "policy_set_id", nullable = false, updatable = false, length = 16)
	private UUID policySetId;

	@Column(name = "milestone_days", nullable = false, updatable = false)
	private int milestoneDays;

	@Column(name = "reward_ticket_count", nullable = false, updatable = false)
	private int rewardTicketCount;

	public StreakPolicyEntity(UUID policySetId, int milestoneDays, int rewardTicketCount) {
		this.policySetId = policySetId;
		this.milestoneDays = milestoneDays;
		this.rewardTicketCount = rewardTicketCount;
	}
}
