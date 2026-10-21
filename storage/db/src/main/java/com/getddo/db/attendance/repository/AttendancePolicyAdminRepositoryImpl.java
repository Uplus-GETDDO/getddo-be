package com.getddo.db.attendance.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.domain.StreakMilestone;
import com.getddo.core.attendance.domain.StreakPolicyRecord;
import com.getddo.core.attendance.repository.AttendancePolicyAdminRepository;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.db.attendance.entity.RewardPolicyEntity;
import com.getddo.db.attendance.entity.StreakPolicyEntity;
import com.getddo.db.attendance.entity.StreakPolicySetEntity;
import com.getddo.db.common.pagination.PageOffsets;

/** 관리자 출석 정책 저장소 구현. 일일 정책은 {@code reward_policies}의 ATTENDANCE 정책이다. */
@Repository
@RequiredArgsConstructor
public class AttendancePolicyAdminRepositoryImpl implements AttendancePolicyAdminRepository {

	private static final Sort DAILY_NEWEST_FIRST = Sort.by(Sort.Order.desc("effectiveFrom"), Sort.Order.desc("id"));
	private static final Sort STREAK_NEWEST_FIRST = Sort.by(Sort.Order.desc("effectiveMonth"), Sort.Order.desc("id"));

	private final RewardPolicyJpaRepository rewardPolicies;
	private final StreakPolicySetJpaRepository streakSets;
	private final StreakPolicyJpaRepository streakPolicies;

	@Override
	public PageResult<DailyAttendancePolicy> findDailyPolicies(PageQuery page) {
		// JPA는 오프셋을 int로 바꾸므로 범위를 넘는 페이지는 조회하지 않고 전체 건수만 확인한다.
		boolean beyondIntOffset = PageOffsets.exceedsIntRange(page);
		Page<RewardPolicyEntity> found = rewardPolicies.findByRewardType(RewardPolicyEntity.ATTENDANCE,
				PageOffsets.toPageRequest(page, DAILY_NEWEST_FIRST));
		List<DailyAttendancePolicy> items = beyondIntOffset ? List.of()
				: found.getContent().stream().map(AttendancePolicyAdminRepositoryImpl::toDomain).toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), found.getTotalElements());
	}

	@Override
	public Optional<DailyAttendancePolicy> findOpenDailyPolicyForUpdate() {
		return rewardPolicies.findOpenAttendanceForUpdate().map(AttendancePolicyAdminRepositoryImpl::toDomain);
	}

	@Override
	public DailyAttendancePolicy replaceDailyPolicy(UUID previousOpenId, int rewardTicketCount, Instant effectiveFrom,
			UUID createdBy) {
		if (previousOpenId != null) {
			rewardPolicies.closeAt(previousOpenId, effectiveFrom);
		}
		RewardPolicyEntity saved = rewardPolicies.saveAndFlush(
				RewardPolicyEntity.dailyAttendance(createdBy, rewardTicketCount, effectiveFrom));
		return toDomain(saved);
	}

	@Override
	public PageResult<StreakPolicyRecord> findStreakPolicies(PageQuery page) {
		boolean beyondIntOffset = PageOffsets.exceedsIntRange(page);
		Page<StreakPolicySetEntity> found = streakSets.findAllBy(PageOffsets.toPageRequest(page, STREAK_NEWEST_FIRST));
		List<StreakPolicySetEntity> sets = beyondIntOffset ? List.of() : found.getContent();
		Map<UUID, List<StreakPolicyEntity>> milestonesBySet = milestonesOf(
				sets.stream().map(StreakPolicySetEntity::getId).toList());
		List<StreakPolicyRecord> items = sets.stream()
				.map(set -> toDomain(set, milestonesBySet.getOrDefault(set.getId(), List.of())))
				.toList();
		return new PageResult<>(items, page.getPage(), page.getSize(), found.getTotalElements());
	}

	@Override
	public boolean existsStreakPolicy(LocalDate effectiveMonth) {
		return streakSets.existsByEffectiveMonth(effectiveMonth);
	}

	@Override
	public StreakPolicyRecord saveStreakPolicy(LocalDate effectiveMonth, List<MilestoneDraft> milestones,
			UUID createdBy) {
		StreakPolicySetEntity set = streakSets.saveAndFlush(new StreakPolicySetEntity(createdBy, effectiveMonth));
		List<StreakPolicyEntity> saved = streakPolicies.saveAllAndFlush(milestones.stream()
				.map(m -> new StreakPolicyEntity(set.getId(), m.getMilestoneDays(), m.getRewardTicketCount()))
				.toList());
		return toDomain(set, saved);
	}

	@Override
	public Optional<DailyAttendancePolicy> findDailyPolicyForUpdate(UUID policyId) {
		return rewardPolicies.findAttendanceByIdForUpdate(policyId)
				.map(AttendancePolicyAdminRepositoryImpl::toDomain);
	}

	@Override
	public DailyAttendancePolicy updateDailyRewardTicketCount(UUID policyId, int rewardTicketCount) {
		rewardPolicies.updateRewardTicketCount(policyId, rewardTicketCount);
		return toDomain(rewardPolicies.findById(policyId).orElseThrow());
	}

	@Override
	public Optional<StreakPolicyRecord> findStreakPolicyForUpdate(UUID policySetId) {
		return streakSets.findByIdForUpdate(policySetId)
				.map(set -> toDomain(set, streakPolicies.findByPolicySetIdIn(List.of(set.getId()))));
	}

	@Override
	public StreakPolicyRecord replaceStreakMilestones(UUID policySetId, List<MilestoneDraft> milestones) {
		streakPolicies.deleteByPolicySetId(policySetId);
		List<StreakPolicyEntity> saved = streakPolicies.saveAllAndFlush(milestones.stream()
				.map(m -> new StreakPolicyEntity(policySetId, m.getMilestoneDays(), m.getRewardTicketCount()))
				.toList());
		return toDomain(streakSets.findById(policySetId).orElseThrow(), saved);
	}

	private Map<UUID, List<StreakPolicyEntity>> milestonesOf(Collection<UUID> setIds) {
		if (setIds.isEmpty()) {
			return Map.of();
		}
		return streakPolicies.findByPolicySetIdIn(setIds).stream()
				.collect(Collectors.groupingBy(StreakPolicyEntity::getPolicySetId, Collectors.toList()));
	}

	private static DailyAttendancePolicy toDomain(RewardPolicyEntity entity) {
		return new DailyAttendancePolicy(entity.getId(), entity.getRewardTicketCount(), entity.getEffectiveFrom(),
				entity.getEffectiveUntil(), entity.getCreatedBy(), entity.getCreatedAt());
	}

	private static StreakPolicyRecord toDomain(StreakPolicySetEntity set, List<StreakPolicyEntity> milestones) {
		return new StreakPolicyRecord(set.getId(), set.getEffectiveMonth(),
				milestones.stream()
						.map(m -> new StreakMilestone(m.getId(), m.getMilestoneDays(), m.getRewardTicketCount()))
						.toList(),
				set.getCreatedBy(), set.getCreatedAt());
	}
}
