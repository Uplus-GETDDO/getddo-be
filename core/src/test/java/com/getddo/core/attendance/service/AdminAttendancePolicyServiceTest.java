package com.getddo.core.attendance.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.domain.StreakMilestone;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAttendancePolicyServiceTest {

	/** 2026-10-17 12:00 KST. */
	private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
	private static final Instant NEXT_MIDNIGHT = Instant.parse("2026-10-17T15:00:00Z");
	private static final PageQuery FIRST_PAGE = new PageQuery(1, 20);
	private static final User ADMIN = user(UserRole.ADMIN, UserStatus.ACTIVE);

	@Mock
	private AttendancePolicyAdminRepository repository;
	@Mock
	private AuditLogService auditLogs;

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "사용자", role, status, null, null, null, null, null, null, null);
	}

	private AdminAttendancePolicyService service(Instant now) {
		return new AdminAttendancePolicyService(repository,
				new TimeProvider(Clock.fixed(now, ZoneOffset.UTC)), auditLogs);
	}

	private static DailyAttendancePolicy daily(int count, Instant from, Instant until) {
		return new DailyAttendancePolicy(UUID.randomUUID(), count, from, until, UUID.randomUUID(), NOW);
	}

	private static void assertError(Throwable thrown, AttendanceErrorCode expected) {
		assertThat(thrown).isInstanceOfSatisfying(AttendanceException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	@Test
	@DisplayName("관리자가 아니거나 활성이 아니면 ATTENDANCE-002로 거절하고 저장소를 읽지 않는다")
	void rejectsNonAdmins() {
		for (User caller : new User[] {null, user(UserRole.USER, UserStatus.ACTIVE),
				user(UserRole.ADMIN, UserStatus.INACTIVE)}) {
			assertThatThrownBy(() -> service(NOW).findDailyPolicies(caller, FIRST_PAGE))
					.satisfies(e -> assertError(e, AttendanceErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service(NOW).findStreakPolicies(caller, FIRST_PAGE))
					.satisfies(e -> assertError(e, AttendanceErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service(NOW).createDailyPolicy(caller, 1))
					.satisfies(e -> assertError(e, AttendanceErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service(NOW).createStreakPolicy(caller, List.of(new MilestoneDraft(7, 1))))
					.satisfies(e -> assertError(e, AttendanceErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(repository, auditLogs);
	}

	@Test
	@DisplayName("관리자의 정책 조회는 저장소 결과를 그대로 돌려준다")
	void listsPolicies() {
		// given
		PageResult<DailyAttendancePolicy> dailyPage = new PageResult<>(List.of(), 1, 20, 0);
		PageResult<StreakPolicyRecord> streakPage = new PageResult<>(List.of(), 1, 20, 0);
		when(repository.findDailyPolicies(FIRST_PAGE)).thenReturn(dailyPage);
		when(repository.findStreakPolicies(FIRST_PAGE)).thenReturn(streakPage);
		// when
		// then
		assertThat(service(NOW).findDailyPolicies(ADMIN, FIRST_PAGE)).isSameAs(dailyPage);
		assertThat(service(NOW).findStreakPolicies(ADMIN, FIRST_PAGE)).isSameAs(streakPage);
	}

	@Test
	@DisplayName("일일 정책은 다음 KST 자정부터 적용하고 열린 이전 정책을 그 시각에 종료한다")
	void createsDailyPolicyFromNextKstMidnight() {
		// given
		DailyAttendancePolicy open = daily(1, Instant.parse("2026-10-01T00:00:00Z"), null);
		DailyAttendancePolicy created = daily(3, NEXT_MIDNIGHT, null);
		when(repository.findOpenDailyPolicyForUpdate()).thenReturn(Optional.of(open));
		when(repository.replaceDailyPolicy(open.getId(), 3, NEXT_MIDNIGHT, ADMIN.getId())).thenReturn(created);
		// when
		DailyAttendancePolicy result = service(NOW).createDailyPolicy(ADMIN, 3);
		// then
		assertThat(result).isSameAs(created);
		ArgumentCaptor<AuditLogCommand> audit = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(audit.capture());
		assertThat(audit.getValue().getAction()).isEqualTo("ATTENDANCE_DAILY_POLICY_CREATE");
		assertThat(audit.getValue().getTargetId()).isEqualTo(created.getId());
		assertThat(audit.getValue().getBeforeData()).containsEntry("rewardTicketCount", 1);
	}

	@Test
	@DisplayName("KST 자정 직후(UTC로는 전날)에 등록해도 다음 KST 자정을 적용 시각으로 계산한다")
	void usesKstBusinessDateNearMidnight() {
		// given 2026-10-18 00:30 KST
		Instant justAfterKstMidnight = Instant.parse("2026-10-17T15:30:00Z");
		Instant expectedFrom = Instant.parse("2026-10-18T15:00:00Z");
		when(repository.findOpenDailyPolicyForUpdate()).thenReturn(Optional.empty());
		when(repository.replaceDailyPolicy(null, 2, expectedFrom, ADMIN.getId()))
				.thenReturn(daily(2, expectedFrom, null));
		// when
		DailyAttendancePolicy result = service(justAfterKstMidnight).createDailyPolicy(ADMIN, 2);
		// then
		assertThat(result.getEffectiveFrom()).isEqualTo(expectedFrom);
	}

	@Test
	@DisplayName("같은 적용일에 이미 예약된 일일 정책이 있으면 ATTENDANCE-004로 거절한다")
	void rejectsDuplicateDailyPolicy() {
		// given
		when(repository.findOpenDailyPolicyForUpdate()).thenReturn(Optional.of(daily(2, NEXT_MIDNIGHT, null)));
		// when
		// then
		assertThatThrownBy(() -> service(NOW).createDailyPolicy(ADMIN, 3))
				.satisfies(e -> assertError(e, AttendanceErrorCode.DAILY_POLICY_ALREADY_SCHEDULED));
		verify(repository, never()).replaceDailyPolicy(any(), anyInt(), any(), any());
		verifyNoInteractions(auditLogs);
	}

	@Test
	@DisplayName("열린 정책이 없을 때 동시에 등록돼 저장이 UNIQUE에 걸리면 ATTENDANCE-004로 바꿔 거절한다")
	void mapsConcurrentDailyRegistration() {
		// given
		when(repository.findOpenDailyPolicyForUpdate()).thenReturn(Optional.empty());
		when(repository.replaceDailyPolicy(any(), anyInt(), any(), any()))
				.thenThrow(new DataIntegrityViolationException("duplicate"));
		// when
		// then
		assertThatThrownBy(() -> service(NOW).createDailyPolicy(ADMIN, 3))
				.satisfies(e -> assertError(e, AttendanceErrorCode.DAILY_POLICY_ALREADY_SCHEDULED));
	}

	@Test
	@DisplayName("일일 보상 수량이 1 미만이면 ATTENDANCE-003으로 거절한다")
	void rejectsInvalidDailyReward() {
		assertThatThrownBy(() -> service(NOW).createDailyPolicy(ADMIN, 0))
				.satisfies(e -> assertError(e, AttendanceErrorCode.INVALID_POLICY));
		verifyNoInteractions(repository, auditLogs);
	}

	@Test
	@DisplayName("연속 출석 정책은 다음 KST 기준월 1일부터 적용하도록 저장한다")
	void createsStreakPolicyForNextMonth() {
		// given
		List<MilestoneDraft> milestones = List.of(new MilestoneDraft(7, 1), new MilestoneDraft(28, 7));
		LocalDate nextMonth = LocalDate.of(2026, 11, 1);
		StreakPolicyRecord created = new StreakPolicyRecord(UUID.randomUUID(), nextMonth, List.of(),
				ADMIN.getId(), NOW);
		when(repository.existsStreakPolicy(nextMonth)).thenReturn(false);
		when(repository.saveStreakPolicy(nextMonth, milestones, ADMIN.getId())).thenReturn(created);
		// when
		StreakPolicyRecord result = service(NOW).createStreakPolicy(ADMIN, milestones);
		// then
		assertThat(result).isSameAs(created);
		ArgumentCaptor<AuditLogCommand> audit = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(audit.capture());
		assertThat(audit.getValue().getAction()).isEqualTo("ATTENDANCE_STREAK_POLICY_CREATE");
	}

	@Test
	@DisplayName("UTC로는 10월 말이어도 KST로 이미 11월이면 12월 1일부터 적용한다")
	void usesKstMonthAtMonthBoundary() {
		// given 2026-11-01 00:30 KST
		Instant kstNovember = Instant.parse("2026-10-31T15:30:00Z");
		LocalDate expected = LocalDate.of(2026, 12, 1);
		List<MilestoneDraft> milestones = List.of(new MilestoneDraft(7, 1));
		when(repository.existsStreakPolicy(expected)).thenReturn(false);
		when(repository.saveStreakPolicy(eq(expected), eq(milestones), any()))
				.thenReturn(new StreakPolicyRecord(UUID.randomUUID(), expected, List.of(), ADMIN.getId(), NOW));
		// when
		StreakPolicyRecord result = service(kstNovember).createStreakPolicy(ADMIN, milestones);
		// then
		assertThat(result.getEffectiveMonth()).isEqualTo(expected);
	}

	@Test
	@DisplayName("같은 적용월에 이미 예약된 연속 출석 정책이 있으면 ATTENDANCE-005로 거절한다")
	void rejectsDuplicateStreakPolicy() {
		// given
		when(repository.existsStreakPolicy(LocalDate.of(2026, 11, 1))).thenReturn(true);
		// when
		// then
		assertThatThrownBy(() -> service(NOW).createStreakPolicy(ADMIN, List.of(new MilestoneDraft(7, 1))))
				.satisfies(e -> assertError(e, AttendanceErrorCode.STREAK_POLICY_ALREADY_SCHEDULED));
		verify(repository, never()).saveStreakPolicy(any(), any(), any());
	}

	@Test
	@DisplayName("동시에 같은 적용월로 저장돼 UNIQUE에 걸리면 ATTENDANCE-005로 바꿔 거절한다")
	void mapsConcurrentStreakRegistration() {
		// given
		when(repository.existsStreakPolicy(any())).thenReturn(false);
		when(repository.saveStreakPolicy(any(), any(), any())).thenThrow(new DataIntegrityViolationException("dup"));
		// when
		// then
		assertThatThrownBy(() -> service(NOW).createStreakPolicy(ADMIN, List.of(new MilestoneDraft(7, 1))))
				.satisfies(e -> assertError(e, AttendanceErrorCode.STREAK_POLICY_ALREADY_SCHEDULED));
	}

	@Test
	@DisplayName("단계가 비었거나 범위 밖이거나 오름차순이 아니거나 중복이거나 보상이 0이면 ATTENDANCE-003으로 거절한다")
	void rejectsInvalidMilestones() {
		List<List<MilestoneDraft>> invalid = List.of(
				List.of(),
				List.of(new MilestoneDraft(0, 1)),
				List.of(new MilestoneDraft(29, 1)),
				List.of(new MilestoneDraft(14, 1), new MilestoneDraft(7, 1)),
				List.of(new MilestoneDraft(7, 1), new MilestoneDraft(7, 2)),
				List.of(new MilestoneDraft(7, 0)));
		for (List<MilestoneDraft> milestones : invalid) {
			assertThatThrownBy(() -> service(NOW).createStreakPolicy(ADMIN, milestones))
					.satisfies(e -> assertError(e, AttendanceErrorCode.INVALID_POLICY));
		}
		assertThatThrownBy(() -> service(NOW).createStreakPolicy(ADMIN,
				java.util.Arrays.asList(new MilestoneDraft(7, 1), null)))
				.satisfies(e -> assertError(e, AttendanceErrorCode.INVALID_POLICY));
		assertThatThrownBy(() -> service(NOW).createStreakPolicy(ADMIN, null))
				.satisfies(e -> assertError(e, AttendanceErrorCode.INVALID_POLICY));
		verifyNoInteractions(repository, auditLogs);
	}

	@Test
	@DisplayName("관리자가 아니면 예약 정책 수정을 ATTENDANCE-002로 거절하고 저장소를 읽지 않는다")
	void rejectsNonAdminUpdates() {
		for (User caller : new User[] {null, user(UserRole.USER, UserStatus.ACTIVE),
				user(UserRole.ADMIN, UserStatus.INACTIVE)}) {
			assertThatThrownBy(() -> service(NOW).updateDailyPolicy(caller, UUID.randomUUID(), 2))
					.satisfies(e -> assertError(e, AttendanceErrorCode.ADMIN_REQUIRED));
			assertThatThrownBy(() -> service(NOW).updateStreakPolicy(caller, UUID.randomUUID(),
					List.of(new MilestoneDraft(7, 1))))
					.satisfies(e -> assertError(e, AttendanceErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(repository, auditLogs);
	}

	@Test
	@DisplayName("아직 시작하지 않은 예약 일일 정책의 보상 수량을 바꾸고 수정 전·후 값을 감사 기록에 남긴다")
	void updatesScheduledDailyPolicy() {
		// given
		DailyAttendancePolicy scheduled = daily(1, NEXT_MIDNIGHT, null);
		DailyAttendancePolicy updated = new DailyAttendancePolicy(scheduled.getId(), 3, NEXT_MIDNIGHT, null,
				scheduled.getCreatedBy(), NOW);
		when(repository.findDailyPolicyForUpdate(scheduled.getId())).thenReturn(Optional.of(scheduled));
		when(repository.updateDailyRewardTicketCount(scheduled.getId(), 3)).thenReturn(updated);
		// when
		DailyAttendancePolicy result = service(NOW).updateDailyPolicy(ADMIN, scheduled.getId(), 3);
		// then
		assertThat(result).isSameAs(updated);
		ArgumentCaptor<AuditLogCommand> audit = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(audit.capture());
		assertThat(audit.getValue().getAction()).isEqualTo("ATTENDANCE_DAILY_POLICY_UPDATE");
		assertThat(audit.getValue().getTargetId()).isEqualTo(scheduled.getId());
		assertThat(audit.getValue().getBeforeData()).containsEntry("rewardTicketCount", 1);
		assertThat(audit.getValue().getAfterData()).containsEntry("rewardTicketCount", 3);
	}

	@Test
	@DisplayName("적용 시작 시각 정각이거나 이미 시작한 일일 정책은 ATTENDANCE-007로 거절하고 바꾸지 않는다")
	void rejectsStartedDailyPolicy() {
		// given
		DailyAttendancePolicy current = daily(1, Instant.parse("2026-10-16T15:00:00Z"), null);
		when(repository.findDailyPolicyForUpdate(current.getId())).thenReturn(Optional.of(current));
		DailyAttendancePolicy atBoundary = daily(1, NOW, null);
		when(repository.findDailyPolicyForUpdate(atBoundary.getId())).thenReturn(Optional.of(atBoundary));
		// when
		// then
		assertThatThrownBy(() -> service(NOW).updateDailyPolicy(ADMIN, current.getId(), 3))
				.satisfies(e -> assertError(e, AttendanceErrorCode.POLICY_ALREADY_STARTED));
		assertThatThrownBy(() -> service(NOW).updateDailyPolicy(ADMIN, atBoundary.getId(), 3))
				.satisfies(e -> assertError(e, AttendanceErrorCode.POLICY_ALREADY_STARTED));
		verify(repository, never()).updateDailyRewardTicketCount(any(), anyInt());
		verifyNoInteractions(auditLogs);
	}

	@Test
	@DisplayName("없는 일일 정책은 ATTENDANCE-006, 보상 수량이 1 미만이면 ATTENDANCE-003으로 거절한다")
	void rejectsMissingOrInvalidDailyUpdate() {
		// given
		UUID missing = UUID.randomUUID();
		when(repository.findDailyPolicyForUpdate(missing)).thenReturn(Optional.empty());
		// when
		// then
		assertThatThrownBy(() -> service(NOW).updateDailyPolicy(ADMIN, missing, 2))
				.satisfies(e -> assertError(e, AttendanceErrorCode.POLICY_NOT_FOUND));
		assertThatThrownBy(() -> service(NOW).updateDailyPolicy(ADMIN, UUID.randomUUID(), 0))
				.satisfies(e -> assertError(e, AttendanceErrorCode.INVALID_POLICY));
		verify(repository, never()).updateDailyRewardTicketCount(any(), anyInt());
		verifyNoInteractions(auditLogs);
	}

	@Test
	@DisplayName("아직 시작하지 않은 예약 연속 출석 정책의 단계를 모두 바꾸고 수정 전·후 단계를 감사 기록에 남긴다")
	void updatesScheduledStreakPolicy() {
		// given
		UUID setId = UUID.randomUUID();
		StreakPolicyRecord scheduled = new StreakPolicyRecord(setId, LocalDate.of(2026, 11, 1),
				List.of(new StreakMilestone(UUID.randomUUID(), 7, 1)), ADMIN.getId(), NOW);
		List<MilestoneDraft> next = List.of(new MilestoneDraft(7, 2), new MilestoneDraft(28, 7));
		StreakPolicyRecord updated = new StreakPolicyRecord(setId, LocalDate.of(2026, 11, 1), List.of(),
				ADMIN.getId(), NOW);
		when(repository.findStreakPolicyForUpdate(setId)).thenReturn(Optional.of(scheduled));
		when(repository.replaceStreakMilestones(setId, next)).thenReturn(updated);
		// when
		StreakPolicyRecord result = service(NOW).updateStreakPolicy(ADMIN, setId, next);
		// then
		assertThat(result).isSameAs(updated);
		ArgumentCaptor<AuditLogCommand> audit = ArgumentCaptor.forClass(AuditLogCommand.class);
		verify(auditLogs).record(audit.capture());
		assertThat(audit.getValue().getAction()).isEqualTo("ATTENDANCE_STREAK_POLICY_UPDATE");
		assertThat(audit.getValue().getTargetId()).isEqualTo(setId);
		assertThat(audit.getValue().getBeforeData()).containsKey("milestones");
		assertThat(audit.getValue().getAfterData()).containsEntry("effectiveMonth", "2026-11-01");
	}

	@Test
	@DisplayName("적용월 1일(KST)부터는 이미 시작한 연속 출석 정책으로 보고 ATTENDANCE-007로 거절한다")
	void rejectsStartedStreakPolicy() {
		// given 2026-11-01 00:30 KST
		Instant kstNovember = Instant.parse("2026-10-31T15:30:00Z");
		UUID setId = UUID.randomUUID();
		StreakPolicyRecord november = new StreakPolicyRecord(setId, LocalDate.of(2026, 11, 1), List.of(),
				ADMIN.getId(), NOW);
		when(repository.findStreakPolicyForUpdate(setId)).thenReturn(Optional.of(november));
		// when
		// then
		assertThatThrownBy(() -> service(kstNovember).updateStreakPolicy(ADMIN, setId,
				List.of(new MilestoneDraft(7, 2))))
				.satisfies(e -> assertError(e, AttendanceErrorCode.POLICY_ALREADY_STARTED));
		verify(repository, never()).replaceStreakMilestones(any(), any());
		verifyNoInteractions(auditLogs);
	}

	@Test
	@DisplayName("없는 연속 출석 정책 묶음은 ATTENDANCE-006, 단계 규칙을 어기면 ATTENDANCE-003으로 거절하고 저장소를 바꾸지 않는다")
	void rejectsMissingOrInvalidStreakUpdate() {
		// given
		UUID missing = UUID.randomUUID();
		when(repository.findStreakPolicyForUpdate(missing)).thenReturn(Optional.empty());
		// when
		// then
		assertThatThrownBy(() -> service(NOW).updateStreakPolicy(ADMIN, missing, List.of(new MilestoneDraft(7, 1))))
				.satisfies(e -> assertError(e, AttendanceErrorCode.POLICY_NOT_FOUND));
		assertThatThrownBy(() -> service(NOW).updateStreakPolicy(ADMIN, UUID.randomUUID(),
				List.of(new MilestoneDraft(14, 1), new MilestoneDraft(7, 1))))
				.satisfies(e -> assertError(e, AttendanceErrorCode.INVALID_POLICY));
		verify(repository, never()).replaceStreakMilestones(any(), any());
		verifyNoInteractions(auditLogs);
	}
}
