package com.getddo.db.mission.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.getddo.db.common.entity.BaseEntity;

/**
 * 보상 정책 중 미션 정책(MISSION)만 저장한다. 미션 완료 보상은 등급 응모권 1장으로 고정이라 지급 수량은 항상 1이고, 등급은
 * 지급 때 무작위로 정하므로 보상 등급 컬럼은 비운다. 저장 후 바꾸지 않는다.
 */
@Getter
@Entity
@Table(name = "reward_policies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionRewardPolicyEntity extends BaseEntity {

	private static final String MISSION = "MISSION";
	private static final int FIXED_TICKET_COUNT = 1;

	@Column(name = "created_by", nullable = false, updatable = false, length = 16)
	private UUID createdBy;

	@Column(name = "reward_type", nullable = false, updatable = false)
	private String rewardType;

	@Column(name = "reward_ticket_count", nullable = false, updatable = false)
	private int rewardTicketCount;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	public MissionRewardPolicyEntity(UUID createdBy, Instant effectiveFrom) {
		this.createdBy = createdBy;
		this.rewardType = MISSION;
		this.rewardTicketCount = FIXED_TICKET_COUNT;
		this.effectiveFrom = effectiveFrom;
	}
}
