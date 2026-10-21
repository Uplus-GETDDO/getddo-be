package com.getddo.core.attendance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.domain.StreakPolicyRecord;
import com.getddo.core.attendance.exception.AttendanceErrorCode;
import com.getddo.core.attendance.exception.AttendanceException;
import com.getddo.core.attendance.repository.AttendancePolicyAdminRepository;
import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

/**
 * 관리자 출석 정책 조회(AP01·AP03)와 등록(AP02·AP04).
 *
 * <p>모든 호출은 활성 관리자만 할 수 있다. 일일 정책은 다음 KST 자정부터, 연속 출석 정책은 다음 KST 기준월 1일부터
 * 적용하며 사용자가 적용 시점을 정할 수 없다. 이미 적용 중이거나 지난 정책과 기존 보상 기록은 바꾸지 않는다. 같은 적용
 * 시점의 예약 정책을 다시 등록하면 충돌로 거절한다.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminAttendancePolicyService {

	private static final int MIN_MILESTONE_DAYS = 1;
	private static final int MAX_MILESTONE_DAYS = 28;
	private static final int MIN_REWARD_TICKET_COUNT = 1;
	private static final String DAILY_CREATE = "ATTENDANCE_DAILY_POLICY_CREATE";
	private static final String STREAK_CREATE = "ATTENDANCE_STREAK_POLICY_CREATE";
	private static final String DAILY_UPDATE = "ATTENDANCE_DAILY_POLICY_UPDATE";
	private static final String STREAK_UPDATE = "ATTENDANCE_STREAK_POLICY_UPDATE";
	private static final String TARGET_DAILY = "ATTENDANCE_DAILY_POLICY";
	private static final String TARGET_STREAK = "ATTENDANCE_STREAK_POLICY";

	private final AttendancePolicyAdminRepository repository;
	private final TimeProvider timeProvider;
	private final AuditLogService auditLogs;

	/**
	 * @throws AttendanceException 관리자가 아닌 경우({@code ATTENDANCE-002})
	 */
	@Transactional(readOnly = true)
	public PageResult<DailyAttendancePolicy> findDailyPolicies(User admin, PageQuery page) {
		requireActiveAdmin(admin);
		return repository.findDailyPolicies(page);
	}

	/**
	 * @throws AttendanceException 관리자가 아닌 경우({@code ATTENDANCE-002})
	 */
	@Transactional(readOnly = true)
	public PageResult<StreakPolicyRecord> findStreakPolicies(User admin, PageQuery page) {
		requireActiveAdmin(admin);
		return repository.findStreakPolicies(page);
	}

	/**
	 * 다음 KST 자정부터 적용할 일일 출석 정책을 등록한다. 현재 열린 정책은 그 시각에 끝난다.
	 *
	 * @throws AttendanceException 관리자가 아니거나({@code ATTENDANCE-002}), 보상 수량이 1 미만이거나
	 *         ({@code ATTENDANCE-003}), 같은 적용 시점의 정책이 이미 있는 경우({@code ATTENDANCE-004})
	 */
	@Transactional
	public DailyAttendancePolicy createDailyPolicy(User admin, int rewardTicketCount) {
		requireActiveAdmin(admin);
		if (rewardTicketCount < MIN_REWARD_TICKET_COUNT) {
			throw new AttendanceException(AttendanceErrorCode.INVALID_POLICY);
		}
		Instant now = timeProvider.now().truncatedTo(ChronoUnit.MICROS);
		Instant effectiveFrom = timeProvider.toUtc(timeProvider.businessDate(now).plusDays(1).atStartOfDay());
		Optional<DailyAttendancePolicy> open = repository.findOpenDailyPolicyForUpdate();
		if (open.isPresent() && !open.get().getEffectiveFrom().isBefore(effectiveFrom)) {
			throw new AttendanceException(AttendanceErrorCode.DAILY_POLICY_ALREADY_SCHEDULED);
		}
		DailyAttendancePolicy created;
		try {
			created = repository.replaceDailyPolicy(open.map(DailyAttendancePolicy::getId).orElse(null),
					rewardTicketCount, effectiveFrom, admin.getId());
		} catch (DataIntegrityViolationException concurrentRegistration) {
			throw new AttendanceException(AttendanceErrorCode.DAILY_POLICY_ALREADY_SCHEDULED);
		}
		Map<String, Object> before = new HashMap<>();
		open.ifPresent(previous -> before.put("rewardTicketCount", previous.getRewardTicketCount()));
		auditLogs.record(new AuditLogCommand(admin.getId(), DAILY_CREATE, TARGET_DAILY, created.getId(), null,
				before.isEmpty() ? null : before,
				Map.of("rewardTicketCount", rewardTicketCount, "effectiveFrom", effectiveFrom.toString()), null));
		return created;
	}

	/**
	 * 다음 KST 기준월 1일부터 적용할 연속 출석 정책 묶음을 등록한다.
	 *
	 * @param milestones 단계 일수 오름차순이고 중복이 없는 단계. 일수는 1~28, 보상 수량은 1 이상이다
	 * @throws AttendanceException 관리자가 아니거나({@code ATTENDANCE-002}), 단계 규칙을 어겼거나
	 *         ({@code ATTENDANCE-003}), 같은 적용월의 정책이 이미 있는 경우({@code ATTENDANCE-005})
	 */
	@Transactional
	public StreakPolicyRecord createStreakPolicy(User admin, List<MilestoneDraft> milestones) {
		requireActiveAdmin(admin);
		requireValidMilestones(milestones);
		LocalDate effectiveMonth = timeProvider.toKst(timeProvider.now()).toLocalDate().withDayOfMonth(1)
				.plusMonths(1);
		if (repository.existsStreakPolicy(effectiveMonth)) {
			throw new AttendanceException(AttendanceErrorCode.STREAK_POLICY_ALREADY_SCHEDULED);
		}
		StreakPolicyRecord created;
		try {
			created = repository.saveStreakPolicy(effectiveMonth, milestones, admin.getId());
		} catch (DataIntegrityViolationException concurrentRegistration) {
			throw new AttendanceException(AttendanceErrorCode.STREAK_POLICY_ALREADY_SCHEDULED);
		}
		auditLogs.record(new AuditLogCommand(admin.getId(), STREAK_CREATE, TARGET_STREAK, created.getId(), null, null,
				Map.of("effectiveMonth", effectiveMonth.toString(), "milestoneCount", milestones.size()), null));
		return created;
	}

	/**
	 * 아직 시작하지 않은 예약 일일 정책의 보상 수량을 바꾼다. 적용 시점은 바뀌지 않는다. 정책 행을 잠근 뒤 시작 여부를
	 * 다시 확인해 수정과 적용 시작이 겹쳐도 시작한 정책은 바뀌지 않는다.
	 *
	 * @throws AttendanceException 관리자가 아니거나({@code ATTENDANCE-002}), 보상 수량이 1 미만이거나
	 *         ({@code ATTENDANCE-003}), 정책이 없거나({@code ATTENDANCE-006}), 이미 시작한 정책인 경우
	 *         ({@code ATTENDANCE-007})
	 */
	@Transactional
	public DailyAttendancePolicy updateDailyPolicy(User admin, UUID policyId, int rewardTicketCount) {
		requireActiveAdmin(admin);
		if (rewardTicketCount < MIN_REWARD_TICKET_COUNT) {
			throw new AttendanceException(AttendanceErrorCode.INVALID_POLICY);
		}
		DailyAttendancePolicy current = repository.findDailyPolicyForUpdate(policyId)
				.orElseThrow(() -> new AttendanceException(AttendanceErrorCode.POLICY_NOT_FOUND));
		if (current.hasStartedAt(timeProvider.now())) {
			throw new AttendanceException(AttendanceErrorCode.POLICY_ALREADY_STARTED);
		}
		DailyAttendancePolicy updated = repository.updateDailyRewardTicketCount(policyId, rewardTicketCount);
		auditLogs.record(new AuditLogCommand(admin.getId(), DAILY_UPDATE, TARGET_DAILY, policyId, null,
				Map.of("rewardTicketCount", current.getRewardTicketCount()),
				Map.of("rewardTicketCount", rewardTicketCount, "effectiveFrom", current.getEffectiveFrom().toString()),
				null));
		return updated;
	}

	/**
	 * 아직 시작하지 않은 예약 연속 출석 정책 묶음의 단계를 모두 바꾼다. 적용월은 바뀌지 않는다. 묶음 행을 잠근 뒤 시작
	 * 여부를 다시 확인한다.
	 *
	 * @param milestones 단계 일수 오름차순이고 중복이 없는 단계. 일수는 1~28, 보상 수량은 1 이상이다
	 * @throws AttendanceException 관리자가 아니거나({@code ATTENDANCE-002}), 단계 규칙을 어겼거나
	 *         ({@code ATTENDANCE-003}), 정책이 없거나({@code ATTENDANCE-006}), 이미 시작한 묶음인 경우
	 *         ({@code ATTENDANCE-007})
	 */
	@Transactional
	public StreakPolicyRecord updateStreakPolicy(User admin, UUID policySetId, List<MilestoneDraft> milestones) {
		requireActiveAdmin(admin);
		requireValidMilestones(milestones);
		StreakPolicyRecord current = repository.findStreakPolicyForUpdate(policySetId)
				.orElseThrow(() -> new AttendanceException(AttendanceErrorCode.POLICY_NOT_FOUND));
		if (current.hasStartedOn(timeProvider.toKst(timeProvider.now()).toLocalDate())) {
			throw new AttendanceException(AttendanceErrorCode.POLICY_ALREADY_STARTED);
		}
		StreakPolicyRecord updated = repository.replaceStreakMilestones(policySetId, milestones);
		auditLogs.record(new AuditLogCommand(admin.getId(), STREAK_UPDATE, TARGET_STREAK, policySetId, null,
				Map.of("milestones", current.getMilestones().stream()
						.map(m -> Map.of("milestoneDays", m.getMilestoneDays(), "rewardTicketCount",
								m.getRewardTicketCount()))
						.toList()),
				Map.of("effectiveMonth", current.getEffectiveMonth().toString(), "milestones", milestones.stream()
						.map(m -> Map.of("milestoneDays", m.getMilestoneDays(), "rewardTicketCount",
								m.getRewardTicketCount()))
						.toList()),
				null));
		return updated;
	}

	private static void requireValidMilestones(List<MilestoneDraft> milestones) {
		if (milestones == null || milestones.isEmpty()) {
			throw new AttendanceException(AttendanceErrorCode.INVALID_POLICY);
		}
		int previousDays = 0;
		for (MilestoneDraft milestone : milestones) {
			if (milestone == null) {
				throw new AttendanceException(AttendanceErrorCode.INVALID_POLICY);
			}
			boolean inRange = milestone.getMilestoneDays() >= MIN_MILESTONE_DAYS
					&& milestone.getMilestoneDays() <= MAX_MILESTONE_DAYS;
			boolean ascending = milestone.getMilestoneDays() > previousDays;
			if (!inRange || !ascending || milestone.getRewardTicketCount() < MIN_REWARD_TICKET_COUNT) {
				throw new AttendanceException(AttendanceErrorCode.INVALID_POLICY);
			}
			previousDays = milestone.getMilestoneDays();
		}
	}

	private static void requireActiveAdmin(User user) {
		if (user == null || user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
			throw new AttendanceException(AttendanceErrorCode.ADMIN_REQUIRED);
		}
	}
}
