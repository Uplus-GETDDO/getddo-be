package com.getddo.core.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/** 관리자가 등록한 연속 출석 정책 묶음 한 건. 적용월의 1일부터 다음 묶음이 시작되기 전까지 쓴다. */
@Getter
public final class StreakPolicyRecord {

	private final UUID id;
	/** 적용 시작월의 1일(KST). */
	private final LocalDate effectiveMonth;
	/** 단계 일수 오름차순. */
	private final List<StreakMilestone> milestones;
	private final UUID createdBy;
	private final Instant createdAt;

	public StreakPolicyRecord(UUID id, LocalDate effectiveMonth, List<StreakMilestone> milestones, UUID createdBy,
			Instant createdAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.effectiveMonth = Objects.requireNonNull(effectiveMonth, "effectiveMonth");
		this.milestones = milestones.stream()
				.sorted(java.util.Comparator.comparingInt(StreakMilestone::getMilestoneDays)).toList();
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	/** 적용월 1일(KST)부터 시작한 것으로 본다. 시작한 묶음은 수정할 수 없다. */
	public boolean hasStartedOn(LocalDate kstToday) {
		return !kstToday.isBefore(effectiveMonth);
	}
}
