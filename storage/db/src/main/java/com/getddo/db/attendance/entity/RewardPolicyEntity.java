package com.getddo.db.attendance.entity;

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
 * 보상 정책 Entity 중 출석 정책(ATTENDANCE)만 다룬다. 종료 시각은 새 정책을 저장하기 전에 별도 UPDATE로 먼저 반영하므로
 * 이 Entity에서 바꾸지 않는다. 미션·게임 정책은 이 Entity로 읽거나 쓰지 않는다.
 */
@Getter
@Entity
@Table(name = "reward_policies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardPolicyEntity extends BaseEntity {

	/** 출석 정책을 가리키는 reward_type 값. */
	public static final String ATTENDANCE = "ATTENDANCE";

	@Column(name = "created_by", nullable = false, updatable = false, length = 16)
	private UUID createdBy;

	@Column(name = "reward_type", nullable = false, updatable = false)
	private String rewardType;

	@Column(name = "reward_ticket_count", nullable = false, updatable = false)
	private int rewardTicketCount;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	/** 별도 UPDATE로만 바뀐다. */
	@Column(name = "effective_until", insertable = false, updatable = false)
	private Instant effectiveUntil;

	/** 출석 보상 등급. 출석은 브론즈로 고정한다. */
	@Column(name = "reward_ticket_type", updatable = false)
	private String rewardTicketType;

	/** 일일 출석 정책을 만든다. 등급은 브론즈다. */
	public static RewardPolicyEntity dailyAttendance(UUID createdBy, int rewardTicketCount, Instant effectiveFrom) {
		RewardPolicyEntity entity = new RewardPolicyEntity();
		entity.createdBy = createdBy;
		entity.rewardType = ATTENDANCE;
		entity.rewardTicketCount = rewardTicketCount;
		entity.effectiveFrom = effectiveFrom;
		entity.rewardTicketType = "BRONZE";
		return entity;
	}
}
