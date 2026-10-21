package com.getddo.core.attendance.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.attendance.domain.Attendance;
import com.getddo.core.attendance.domain.AttendanceReceipt;
import com.getddo.core.attendance.domain.AttendanceRewardClaim;
import com.getddo.core.attendance.domain.AttendanceRewardReceipt;
import com.getddo.core.attendance.domain.AttendanceRewardType;
import com.getddo.core.attendance.domain.AttendanceStreak;
import com.getddo.core.attendance.domain.DailyRewardPolicy;
import com.getddo.core.attendance.domain.StreakMilestone;
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
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.ticket.service.TicketGrantService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceRecorderTest {

	/** 2026-09-07 12:00 KST. */
	private static final Instant NOW = Instant.parse("2026-09-07T03:00:00.123456Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-09-07");
	private static final LocalDate SEPTEMBER = LocalDate.parse("2026-09-01");
	private static final UUID USER_ID = UUID.randomUUID();
	private static final DailyRewardPolicy DAILY = new DailyRewardPolicy(UUID.randomUUID(), 1);
	private static final StreakMilestone SEVEN = new StreakMilestone(UUID.randomUUID(), 7, 1);
	private static final StreakPolicySet SET = new StreakPolicySet(UUID.randomUUID(), List.of(SEVEN,
			new StreakMilestone(UUID.randomUUID(), 14, 3), new StreakMilestone(UUID.randomUUID(), 28, 7)));

	@Mock
	private AttendanceRepository attendanceRepository;
	@Mock
	private AttendanceStreakRepository streakRepository;
	@Mock
	private AttendanceRewardClaimRepository claimRepository;
	@Mock
	private AttendancePolicyRepository policyRepository;
	@Mock
	private TicketGrantService grantService;

	private AttendanceRecorder recorder;

	@BeforeEach
	void setUp() {
		// 출석일은 넘겨받은 요청 시각으로만 정한다. 시계를 다른 날로 두어 시계를 읽지 않는지 함께 확인한다.
		recorder = new AttendanceRecorder(attendanceRepository, streakRepository, claimRepository, policyRepository,
				grantService, new TimeProvider(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
	}

	private static Attendance saved(LocalDate date) {
		return new Attendance(UUID.randomUUID(), USER_ID, date, NOW);
	}

	private void insertReturnsSavedAttendance(LocalDate date) {
		when(attendanceRepository.insert(any())).thenReturn(saved(date));
	}

	private void claimInsertAssignsId() {
		when(claimRepository.insert(any())).thenAnswer(invocation -> {
			AttendanceRewardClaim claim = invocation.getArgument(0);
			return new AttendanceRewardClaim(UUID.randomUUID(), claim.getUserId(), claim.getAttendanceId(),
					claim.getRewardType(), claim.getRewardPolicyId(), claim.getStreakPolicyId(),
					claim.getRewardDate(), claim.getMilestoneDays(), claim.getSourceKey(), claim.getTicketCount());
		});
	}

	private void grantSucceeds() {
		when(grantService.grant(any())).thenAnswer(invocation -> {
			GrantCommand command = invocation.getArgument(0);
			return new GrantResult(command.getQuantity(), TicketGrade.BRONZE, NOW,
					Instant.parse("2026-09-30T15:00:00Z"), false);
		});
	}

	/** 오늘을 포함한 그 달 출석 날짜를 돌려준다. */
	private void attendedDates(YearMonth month, LocalDate... dates) {
		when(attendanceRepository.findAttendanceDates(USER_ID, month)).thenReturn(List.of(dates));
	}

	private static LocalDate[] septemberDays(int fromDay, int toDay) {
		return java.util.stream.IntStream.rangeClosed(fromDay, toDay)
				.mapToObj(day -> LocalDate.of(2026, 9, day)).toArray(LocalDate[]::new);
	}

	private void streakSavedAsIs() {
		when(streakRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private static void assertErrorCode(Runnable call, AttendanceErrorCode expected) {
		assertThatThrownBy(call::run)
				.isInstanceOf(AttendanceException.class)
				.extracting(error -> ((AttendanceException) error).getErrorCode())
				.isEqualTo(expected);
	}

	@Test
	@DisplayName("그 달 첫 출석은 연속 1일로 시작하고 일일 보상만 청구·지급한다")
	void firstAttendanceOfMonth() {
		// given
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		insertReturnsSavedAttendance(TODAY);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.empty());
		when(policyRepository.findStreakPolicySetForShare(SEPTEMBER)).thenReturn(Optional.of(SET));
		attendedDates(YearMonth.of(2026, 9), TODAY);
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		claimInsertAssignsId();
		grantSucceeds();
		// when
		AttendanceReceipt receipt = recorder.record(USER_ID, NOW);
		// then
		assertThat(receipt.isCreated()).isTrue();
		assertThat(receipt.getAttendanceDate()).isEqualTo(TODAY);
		assertThat(receipt.getConsecutiveDays()).isEqualTo(1);
		assertThat(receipt.getRewards()).singleElement().satisfies(reward -> {
			assertThat(reward.getRewardType()).isEqualTo(AttendanceRewardType.DAILY);
			assertThat(reward.getTicketCount()).isEqualTo(1);
		});
		ArgumentCaptor<AttendanceStreak> streak = ArgumentCaptor.forClass(AttendanceStreak.class);
		verify(streakRepository).save(streak.capture());
		assertThat(streak.getValue().getPolicySetId()).isEqualTo(SET.getId());
		assertThat(streak.getValue().getConsecutiveDays()).isEqualTo(1);
	}

	@Test
	@DisplayName("일일 정책은 넘겨받은 요청 시각으로 조회한다")
	void looksUpDailyPolicyAtRequestedTime() {
		// given
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		insertReturnsSavedAttendance(TODAY);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.empty());
		when(policyRepository.findStreakPolicySetForShare(SEPTEMBER)).thenReturn(Optional.of(SET));
		attendedDates(YearMonth.of(2026, 9), TODAY);
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		claimInsertAssignsId();
		grantSucceeds();
		// when
		recorder.record(USER_ID, NOW);
		// then
		verify(policyRepository).findDailyPolicyForShare(NOW);
	}

	@Test
	@DisplayName("연속 7일째 출석이면 일일 보상 다음에 7일 단계 보상을 청구·지급한다")
	void reachesMilestone() {
		// given: 9/1~9/6 연속 출석한 현황
		AttendanceStreak sixDays = new AttendanceStreak(UUID.randomUUID(), USER_ID, SET.getId(), SEPTEMBER, 6,
				TODAY.minusDays(1));
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		insertReturnsSavedAttendance(TODAY);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.of(sixDays));
		attendedDates(YearMonth.of(2026, 9), septemberDays(1, 7));
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		when(policyRepository.findStreakPolicySetByIdForShare(SET.getId())).thenReturn(Optional.of(SET));
		when(claimRepository.exists(USER_ID, AttendanceRewardType.STREAK, "2026-09:7")).thenReturn(false);
		claimInsertAssignsId();
		grantSucceeds();
		// when
		AttendanceReceipt receipt = recorder.record(USER_ID, NOW);
		// then
		assertThat(receipt.getConsecutiveDays()).isEqualTo(7);
		assertThat(receipt.getRewards()).extracting(AttendanceRewardReceipt::getRewardType)
				.containsExactly(AttendanceRewardType.DAILY, AttendanceRewardType.STREAK);
		assertThat(receipt.getRewards()).extracting(AttendanceRewardReceipt::getMilestoneDays)
				.containsExactly(null, 7);
		ArgumentCaptor<GrantCommand> commands = ArgumentCaptor.forClass(GrantCommand.class);
		verify(grantService, times(2)).grant(commands.capture());
		assertThat(commands.getAllValues()).extracting(command -> command.getSource().getType())
				.containsOnly(GrantSourceType.ATTENDANCE);
		assertThat(commands.getAllValues()).extracting(GrantCommand::getQuantity).containsExactly(1L, 1L);
		assertThat(commands.getAllValues()).extracting(GrantCommand::getReason)
				.containsExactly("출석 보상", "연속 출석 7일 보상");
		verify(policyRepository, never()).findStreakPolicySetForShare(any());
	}

	@Test
	@DisplayName("이번 달 같은 단계 보상을 이미 받았으면 다시 도달해도 단계 보상을 청구하지 않는다")
	void doesNotRepeatMilestoneInSameMonth() {
		// given
		AttendanceStreak sixDays = new AttendanceStreak(UUID.randomUUID(), USER_ID, SET.getId(), SEPTEMBER, 6,
				TODAY.minusDays(1));
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		insertReturnsSavedAttendance(TODAY);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.of(sixDays));
		attendedDates(YearMonth.of(2026, 9), septemberDays(1, 7));
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		when(policyRepository.findStreakPolicySetByIdForShare(SET.getId())).thenReturn(Optional.of(SET));
		when(claimRepository.exists(USER_ID, AttendanceRewardType.STREAK, "2026-09:7")).thenReturn(true);
		claimInsertAssignsId();
		grantSucceeds();
		// when
		AttendanceReceipt receipt = recorder.record(USER_ID, NOW);
		// then
		assertThat(receipt.getRewards()).extracting(AttendanceRewardReceipt::getRewardType)
				.containsExactly(AttendanceRewardType.DAILY);
		verify(grantService, times(1)).grant(any());
	}

	@Test
	@DisplayName("오늘 이미 출석했으면 새로 기록·지급하지 않고 저장된 청구의 지급 결과로 영수증을 돌려준다")
	void returnsExistingReceipt() {
		// given
		Attendance existing = saved(TODAY);
		AttendanceRewardClaim dailyClaim = new AttendanceRewardClaim(UUID.randomUUID(), USER_ID, existing.getId(),
				AttendanceRewardType.DAILY, DAILY.getId(), null, TODAY, null, "2026-09-07", 1);
		GrantResult granted = new GrantResult(1, TicketGrade.BRONZE, NOW,
				Instant.parse("2026-09-30T15:00:00Z"), true);
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.of(existing));
		when(claimRepository.findByAttendanceId(existing.getId())).thenReturn(List.of(dailyClaim));
		when(grantService.findGrant(refEq(new GrantSource(GrantSourceType.ATTENDANCE, dailyClaim.getId()))))
				.thenReturn(Optional.of(granted));
		when(streakRepository.find(USER_ID, SEPTEMBER)).thenReturn(Optional.of(
				new AttendanceStreak(UUID.randomUUID(), USER_ID, SET.getId(), SEPTEMBER, 3, TODAY)));
		// when
		AttendanceReceipt receipt = recorder.record(USER_ID, NOW);
		// then
		assertThat(receipt.isCreated()).isFalse();
		assertThat(receipt.getAttendanceId()).isEqualTo(existing.getId());
		assertThat(receipt.getConsecutiveDays()).isEqualTo(3);
		assertThat(receipt.getRewards()).usingRecursiveFieldByFieldElementComparator()
				.containsExactly(new AttendanceRewardReceipt(dailyClaim.getId(),
				AttendanceRewardType.DAILY, null, 1, granted.getGrantedAt(), granted.getExpiresAt()));
		verify(attendanceRepository, never()).insert(any());
		verify(grantService, never()).grant(any());
	}

	@Test
	@DisplayName("업무일은 KST 기준이다. UTC로 9/30 15:30은 10/1 출석이며 10월 현황으로 새로 시작한다")
	void usesKstBusinessDate() {
		// given
		Instant utcSeptemberKstOctober = Instant.parse("2026-09-30T15:30:00Z");
		LocalDate october = LocalDate.parse("2026-10-01");
		when(attendanceRepository.findByUserIdAndDate(USER_ID, october)).thenReturn(Optional.empty());
		insertReturnsSavedAttendance(october);
		when(streakRepository.findForUpdate(USER_ID, october)).thenReturn(Optional.empty());
		when(policyRepository.findStreakPolicySetForShare(october)).thenReturn(Optional.of(SET));
		attendedDates(YearMonth.of(2026, 10), october);
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		claimInsertAssignsId();
		grantSucceeds();
		// when
		AttendanceReceipt receipt = recorder.record(USER_ID, utcSeptemberKstOctober);
		// then
		assertThat(receipt.getAttendanceDate()).isEqualTo(october);
		assertThat(receipt.getConsecutiveDays()).isEqualTo(1);
	}

	@Test
	@DisplayName("출석 시각에 적용할 일일 정책이 없으면 ATTENDANCE-001로 실패하고 지급하지 않는다")
	void failsWithoutDailyPolicy() {
		// given
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.empty());
		// when
		// then
		assertErrorCode(() -> recorder.record(USER_ID, NOW), AttendanceErrorCode.ATTENDANCE_POLICY_NOT_FOUND);
		verify(attendanceRepository, never()).insert(any());
		verify(grantService, never()).grant(any());
	}

	@Test
	@DisplayName("그 달에 적용할 연속 출석 정책 묶음이 없으면 ATTENDANCE-001로 실패한다")
	void failsWithoutStreakPolicy() {
		// given
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		insertReturnsSavedAttendance(TODAY);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.empty());
		when(policyRepository.findStreakPolicySetForShare(SEPTEMBER)).thenReturn(Optional.empty());
		// when
		// then
		assertErrorCode(() -> recorder.record(USER_ID, NOW), AttendanceErrorCode.ATTENDANCE_POLICY_NOT_FOUND);
		verify(grantService, never()).grant(any());
	}

	@Test
	@DisplayName("8일 출석이 먼저 저장된 뒤 7일 출석이 처리되면 연속 8일로 다시 계산하고 7일 단계 보상을 7일 출석으로 청구한다")
	void lateAttendanceCompletesRunAndClaimsMilestone() {
		// given: 9/1~9/6 출석 뒤 8일 요청이 먼저 커밋되어 현황은 연속 1일, 마지막 출석일은 8일이다
		LocalDate eighth = LocalDate.parse("2026-09-08");
		AttendanceStreak resetByEighth = new AttendanceStreak(UUID.randomUUID(), USER_ID, SET.getId(), SEPTEMBER, 1,
				eighth);
		when(attendanceRepository.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(Optional.empty());
		insertReturnsSavedAttendance(TODAY);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.of(resetByEighth));
		attendedDates(YearMonth.of(2026, 9), septemberDays(1, 8));
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		when(policyRepository.findStreakPolicySetByIdForShare(SET.getId())).thenReturn(Optional.of(SET));
		when(claimRepository.exists(USER_ID, AttendanceRewardType.STREAK, "2026-09:7")).thenReturn(false);
		claimInsertAssignsId();
		grantSucceeds();
		// when: 요청 시각은 7일이다
		AttendanceReceipt receipt = recorder.record(USER_ID, NOW);
		// then
		assertThat(receipt.getAttendanceDate()).isEqualTo(TODAY);
		assertThat(receipt.getConsecutiveDays()).isEqualTo(8);
		assertThat(receipt.getRewards()).extracting(AttendanceRewardReceipt::getRewardType, AttendanceRewardReceipt::getMilestoneDays)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(AttendanceRewardType.DAILY, null),
						org.assertj.core.groups.Tuple.tuple(AttendanceRewardType.STREAK, 7));
		ArgumentCaptor<AttendanceStreak> streak = ArgumentCaptor.forClass(AttendanceStreak.class);
		verify(streakRepository).save(streak.capture());
		assertThat(streak.getValue().getConsecutiveDays()).isEqualTo(8);
		assertThat(streak.getValue().getLastAttendanceDate()).isEqualTo(eighth);
	}

	@Test
	@DisplayName("늦게 저장된 중간 날짜 덕에 이미 저장된 뒤 날짜에서 단계에 도달하면 그 날의 출석으로 단계 보상을 청구한다")
	void claimsMilestoneOnTheDayAlreadyStored() {
		// given: 1~5일과 7일이 저장돼 있고 6일이 나중에 들어와 7일째 되는 날은 이미 저장된 7일 출석이다
		LocalDate sixth = LocalDate.parse("2026-09-06");
		LocalDate seventh = LocalDate.parse("2026-09-07");
		Attendance sixthAttendance = saved(sixth);
		Attendance seventhAttendance = saved(seventh);
		AttendanceStreak brokenBySeventh = new AttendanceStreak(UUID.randomUUID(), USER_ID, SET.getId(), SEPTEMBER, 1,
				seventh);
		Instant sixthDay = Instant.parse("2026-09-06T03:00:00Z");
		when(attendanceRepository.findByUserIdAndDate(USER_ID, sixth)).thenReturn(Optional.empty());
		when(attendanceRepository.insert(any())).thenReturn(sixthAttendance);
		when(streakRepository.findForUpdate(USER_ID, SEPTEMBER)).thenReturn(Optional.of(brokenBySeventh));
		attendedDates(YearMonth.of(2026, 9), septemberDays(1, 7));
		when(attendanceRepository.findByUserIdAndDate(USER_ID, seventh)).thenReturn(Optional.of(seventhAttendance));
		streakSavedAsIs();
		when(policyRepository.findDailyPolicyForShare(any())).thenReturn(Optional.of(DAILY));
		when(policyRepository.findStreakPolicySetByIdForShare(SET.getId())).thenReturn(Optional.of(SET));
		when(claimRepository.exists(USER_ID, AttendanceRewardType.STREAK, "2026-09:7")).thenReturn(false);
		claimInsertAssignsId();
		grantSucceeds();
		// when
		AttendanceReceipt receipt = recorder.record(USER_ID, sixthDay);
		// then
		assertThat(receipt.getConsecutiveDays()).isEqualTo(7);
		ArgumentCaptor<AttendanceRewardClaim> claims = ArgumentCaptor.forClass(AttendanceRewardClaim.class);
		verify(claimRepository, times(2)).insert(claims.capture());
		AttendanceRewardClaim streakClaim = claims.getAllValues().get(1);
		assertThat(streakClaim.getRewardType()).isEqualTo(AttendanceRewardType.STREAK);
		assertThat(streakClaim.getAttendanceId()).isEqualTo(seventhAttendance.getId());
		assertThat(streakClaim.getRewardDate()).isEqualTo(seventh);
	}
}
