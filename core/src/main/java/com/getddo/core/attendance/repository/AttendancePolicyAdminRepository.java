package com.getddo.core.attendance.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.domain.StreakPolicyRecord;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;

/** 관리자 출석 정책 저장소. 변경 메서드는 호출자 트랜잭션 안에서 호출한다. */
public interface AttendancePolicyAdminRepository {

	/** 일일 출석 정책을 적용 시작 시각 내림차순(같으면 ID 내림차순)으로 조회한다. */
	PageResult<DailyAttendancePolicy> findDailyPolicies(PageQuery page);

	/** 종료 시각이 없는 일일 출석 정책을 잠가({@code FOR UPDATE}) 읽는다. 없으면 비어 있다. */
	Optional<DailyAttendancePolicy> findOpenDailyPolicyForUpdate();

	/**
	 * 새 일일 정책을 저장하고, 이전 열린 정책이 있으면 새 정책의 시작 시각에 종료한다. 종료를 먼저 DB에 반영해 열린 정책이
	 * 둘이 되지 않게 한다.
	 *
	 * @param previousOpenId 종료할 이전 열린 정책 ID. 없으면 null
	 */
	DailyAttendancePolicy replaceDailyPolicy(UUID previousOpenId, int rewardTicketCount, Instant effectiveFrom,
			UUID createdBy);

	/** 연속 출석 정책 묶음을 적용월 내림차순으로 단계와 함께 조회한다. */
	PageResult<StreakPolicyRecord> findStreakPolicies(PageQuery page);

	/** 같은 적용월의 연속 출석 정책 묶음이 이미 있는지 확인한다. */
	boolean existsStreakPolicy(LocalDate effectiveMonth);

	/**
	 * 정책 묶음과 단계를 저장하고 즉시 DB에 반영한다. 같은 적용월의 묶음이 동시에 만들어지면 UNIQUE 위반 예외가 난다.
	 */
	StreakPolicyRecord saveStreakPolicy(LocalDate effectiveMonth, List<MilestoneDraft> milestones, UUID createdBy);

	/** 일일 출석 정책 한 건을 잠가({@code FOR UPDATE}) 읽는다. 없거나 출석 정책이 아니면 비어 있다. */
	Optional<DailyAttendancePolicy> findDailyPolicyForUpdate(UUID policyId);

	/** 일일 정책의 보상 수량만 바꾸고 즉시 DB에 반영한다. 적용 시점과 종료 시각은 바꾸지 않는다. 호출 전에 잠가야 한다. */
	DailyAttendancePolicy updateDailyRewardTicketCount(UUID policyId, int rewardTicketCount);

	/** 연속 출석 정책 묶음 한 건을 잠가({@code FOR UPDATE}) 단계와 함께 읽는다. 없으면 비어 있다. */
	Optional<StreakPolicyRecord> findStreakPolicyForUpdate(UUID policySetId);

	/** 묶음의 단계를 모두 지우고 새 단계로 바꿔 즉시 DB에 반영한다. 적용월은 바꾸지 않는다. 호출 전에 잠가야 한다. */
	StreakPolicyRecord replaceStreakMilestones(UUID policySetId, List<MilestoneDraft> milestones);
}
