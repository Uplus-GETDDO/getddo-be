package com.getddo.core.attendance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.attendance.domain.Attendance;
import com.getddo.core.attendance.domain.AttendanceReceipt;
import com.getddo.core.attendance.domain.AttendanceRewardClaim;
import com.getddo.core.attendance.domain.AttendanceRewardReceipt;
import com.getddo.core.attendance.domain.AttendanceRewardType;
import com.getddo.core.attendance.domain.AttendanceStreak;
import com.getddo.core.attendance.domain.DailyRewardPolicy;
import com.getddo.core.attendance.domain.ReachedMilestone;
import com.getddo.core.attendance.domain.StreakPolicySet;
import com.getddo.core.attendance.exception.AttendanceErrorCode;
import com.getddo.core.attendance.exception.AttendanceException;
import com.getddo.core.attendance.repository.AttendancePolicyRepository;
import com.getddo.core.attendance.repository.AttendanceRepository;
import com.getddo.core.attendance.repository.AttendanceRewardClaimRepository;
import com.getddo.core.attendance.repository.AttendanceStreakRepository;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.ticket.domain.GrantCommand;
import com.getddo.core.ticket.domain.GrantResult;
import com.getddo.core.ticket.domain.GrantSource;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.service.TicketGrantService;

/**
 * 출석 한 건을 한 트랜잭션에서 처리한다. {@link AttendanceService}만 호출한다.
 *
 * <p>응모권 지급 호출 규약(기존 청구 조회 → 청구 저장 → {@code grant})을 따른다. 오늘 출석이 이미 있으면 새로 기록하지 않고
 * 저장된 청구의 지급 결과를 돌려준다. 출석 기록·연속 현황·보상 청구·응모권 지급은 이 트랜잭션에서 함께 확정되거나 함께
 * 취소된다.</p>
 */
@Service
@RequiredArgsConstructor
public class AttendanceRecorder {

	private static final String DAILY_REASON = "출석 보상";

	private final AttendanceRepository attendanceRepository;
	private final AttendanceStreakRepository streakRepository;
	private final AttendanceRewardClaimRepository claimRepository;
	private final AttendancePolicyRepository policyRepository;
	private final TicketGrantService grantService;
	private final TimeProvider timeProvider;

	/**
	 * 요청 시각의 KST 업무일로 사용자를 출석 처리한다.
	 *
	 * <p>같은 날 동시 요청이 겹치면 늦은 쪽은 출석 저장에서 UNIQUE 위반으로 실패하고 이 트랜잭션 전체가 롤백된다.
	 * 재처리는 호출자({@link AttendanceService})가 새 트랜잭션에서 한다.</p>
	 *
	 * <p>연속 일수와 단계 도달은 처리된 순서가 아니라 그 달 출석 기록으로 다시 계산한다. 자정 직전 요청이 잠금 대기에서
	 * 자정 직후 요청보다 늦게 커밋되어도 순서대로 처리했을 때와 같은 결과가 된다. 이를 위해 연속 현황을 잠근 뒤 읽는
	 * 출석 날짜가 먼저 커밋된 다른 날의 출석을 포함해야 하므로 {@code READ COMMITTED}로 실행한다. 기본인
	 * {@code REPEATABLE READ}에서는 첫 조회가 스냅샷을 정해 잠금 대기 뒤에도 그 이후 커밋이 보이지 않는다. 호출자가 바깥
	 * 트랜잭션을 열고 부르면 바깥 트랜잭션의 격리 수준을 따른다. 운영 경로({@link AttendanceService})는 트랜잭션 없이
	 * 호출하므로 항상 이 설정이 적용된다.</p>
	 *
	 * @param userId      요청 사용자 ID
	 * @param requestedAt 출석 요청 시각. 업무일과 일일 정책 판정 기준이며 재처리에서도 같은 값을 쓴다
	 * @return 새 출석이면 {@code created=true}, 이미 출석한 날이면 저장된 결과({@code created=false})
	 * @throws AttendanceException 적용할 일일 정책이나 그 달의 연속 출석 정책 묶음이 없는 경우
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AttendanceReceipt record(UUID userId, Instant requestedAt) {
		LocalDate today = timeProvider.businessDate(requestedAt);

		Optional<Attendance> existing = attendanceRepository.findByUserIdAndDate(userId, today);
		if (existing.isPresent()) {
			return existingReceipt(existing.get());
		}

		DailyRewardPolicy dailyPolicy = policyRepository.findDailyPolicyForShare(requestedAt)
				.orElseThrow(AttendanceRecorder::noPolicy);
		Attendance attendance = attendanceRepository.insert(Attendance.create(userId, today));
		StreakUpdate update = updateStreak(userId, today);

		List<AttendanceRewardReceipt> rewards = new ArrayList<>();
		rewards.add(claimAndGrant(AttendanceRewardClaim.daily(attendance, dailyPolicy), DAILY_REASON));
		for (ReachedMilestone reached : update.getNewlyReached()) {
			Attendance reachedAttendance = reached.getReachedDate().equals(today) ? attendance
					: attendanceRepository.findByUserIdAndDate(userId, reached.getReachedDate())
							.orElseThrow(() -> new IllegalStateException("단계에 도달한 날의 출석 기록이 없다."));
			AttendanceRewardClaim claim = AttendanceRewardClaim.streak(reachedAttendance, reached.getMilestone());
			if (!claimRepository.exists(userId, AttendanceRewardType.STREAK, claim.getSourceKey())) {
				rewards.add(claimAndGrant(claim, streakReason(claim.getMilestoneDays())));
			}
		}

		return new AttendanceReceipt(attendance.getId(), today, update.getStreak().getConsecutiveDays(), rewards,
				attendance.getCreatedAt(), true);
	}

	/**
	 * 그 달 현황을 잠그고 출석 기록에서 연속 일수와 새로 도달한 단계를 다시 계산해 저장한다.
	 *
	 * <p>새로 도달한 단계는 오늘 출석을 더하기 전후의 출석 날짜로 각각 계산한 도달 단계의 차이다. 이전에 이미 도달한 단계나
	 * 이번 수정 전에 놓친 단계를 거슬러 올라가 청구하지 않는다. 그 달 첫 출석이면 그 달에 적용할 정책 묶음으로 새로
	 * 시작하고, 아니면 현황에 고정된 묶음을 쓴다.</p>
	 */
	private StreakUpdate updateStreak(UUID userId, LocalDate today) {
		LocalDate month = today.withDayOfMonth(1);
		Optional<AttendanceStreak> locked = streakRepository.findForUpdate(userId, month);
		StreakPolicySet policySet = locked.isPresent()
				? policyRepository.findStreakPolicySetByIdForShare(locked.get().getPolicySetId())
						.orElseThrow(AttendanceRecorder::noPolicy)
				: streakPolicySetFor(month);
		List<LocalDate> dates = attendanceRepository.findAttendanceDates(userId, YearMonth.from(today));
		List<LocalDate> datesBeforeToday = dates.stream().filter(date -> !date.equals(today)).toList();

		AttendanceStreak base = locked.orElseGet(() -> AttendanceStreak.start(userId, policySet.getId(), today));
		AttendanceStreak streak = streakRepository.save(base.recalculate(dates));

		Set<Integer> alreadyReached = policySet.reachedBy(datesBeforeToday).stream()
				.map(reached -> reached.getMilestone().getMilestoneDays())
				.collect(Collectors.toSet());
		List<ReachedMilestone> newlyReached = policySet.reachedBy(dates).stream()
				.filter(reached -> !alreadyReached.contains(reached.getMilestone().getMilestoneDays()))
				.toList();
		return new StreakUpdate(streak, newlyReached);
	}

	private StreakPolicySet streakPolicySetFor(LocalDate month) {
		return policyRepository.findStreakPolicySetForShare(month).orElseThrow(AttendanceRecorder::noPolicy);
	}

	/** 연속 현황을 다시 계산한 결과와 이번 출석으로 새로 도달한 단계. */
	@Getter
	private static final class StreakUpdate {

		private final AttendanceStreak streak;
		private final List<ReachedMilestone> newlyReached;

		private StreakUpdate(AttendanceStreak streak, List<ReachedMilestone> newlyReached) {
			this.streak = streak;
			this.newlyReached = newlyReached;
		}
	}

	private AttendanceRewardReceipt claimAndGrant(AttendanceRewardClaim claim, String reason) {
		AttendanceRewardClaim saved = claimRepository.insert(claim);
		GrantResult granted = grantService.grant(new GrantCommand(saved.getUserId(), grantSource(saved),
				saved.getTicketCount(), reason));
		return toReceipt(saved, granted);
	}

	/** 이미 확정된 출석의 결과. 청구마다 응모권 지급 결과를 다시 읽는다. 새로 지급하지 않는다. */
	private AttendanceReceipt existingReceipt(Attendance attendance) {
		List<AttendanceRewardReceipt> rewards = claimRepository.findByAttendanceId(attendance.getId()).stream()
				.map(claim -> toReceipt(claim, grantService.findGrant(grantSource(claim))
						.orElseThrow(() -> new IllegalStateException("출석 보상 청구에 응모권 지급 결과가 없다."))))
				.toList();
		LocalDate month = attendance.getAttendanceDate().withDayOfMonth(1);
		int consecutiveDays = streakRepository.find(attendance.getUserId(), month)
				.map(AttendanceStreak::getConsecutiveDays)
				.orElseThrow(() -> new IllegalStateException("출석한 달의 연속 출석 현황이 없다."));
		return new AttendanceReceipt(attendance.getId(), attendance.getAttendanceDate(), consecutiveDays, rewards,
				attendance.getCreatedAt(), false);
	}

	private static GrantSource grantSource(AttendanceRewardClaim claim) {
		return new GrantSource(GrantSourceType.ATTENDANCE, claim.getId());
	}

	private static AttendanceRewardReceipt toReceipt(AttendanceRewardClaim claim, GrantResult granted) {
		return new AttendanceRewardReceipt(claim.getId(), claim.getRewardType(), claim.getMilestoneDays(),
				claim.getTicketCount(), granted.getGrantedAt(), granted.getExpiresAt());
	}

	private static String streakReason(int milestoneDays) {
		return "연속 출석 " + milestoneDays + "일 보상";
	}

	private static AttendanceException noPolicy() {
		return new AttendanceException(AttendanceErrorCode.ATTENDANCE_POLICY_NOT_FOUND);
	}
}
