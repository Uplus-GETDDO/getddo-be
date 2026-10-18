package com.getddo.db.attendance.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.getddo.db.common.entity.BaseEntity;

/** 연속 출석 정책 묶음 Entity. 한 번 저장하면 바꾸지 않는다. */
@Getter
@Entity
@Table(name = "attendance_streak_policy_sets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreakPolicySetEntity extends BaseEntity {

	@Column(name = "created_by", nullable = false, updatable = false, length = 16)
	private UUID createdBy;

	/** 적용 시작월의 1일(KST). */
	@Column(name = "effective_month", nullable = false, updatable = false)
	private LocalDate effectiveMonth;

	public StreakPolicySetEntity(UUID createdBy, LocalDate effectiveMonth) {
		this.createdBy = createdBy;
		this.effectiveMonth = effectiveMonth;
	}
}
