package com.getddo.db.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.core.attendance.domain.AttendanceReceipt;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.service.AdminAttendancePolicyService;
import com.getddo.core.attendance.service.AttendanceService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.ticket.ConcurrentTasks;
import com.getddo.db.ticket.LockWaitProbe;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예약 출석 정책 수정과 출석 기록이 같은 정책 행에서 직렬화되는지 실제 MySQL 잠금으로 검증한다. 수정 트랜잭션이 정책 행을
 * 잡고 있는 동안 들어온 출석은 수정이 끝날 때까지 기다린 뒤 수정된 값으로 보상을 확정해야 한다.
 */
class AttendancePolicyEditConcurrencyTest extends AttendanceIntegrationTestSupport {

	private static final long WAIT_SECONDS = 30;

	@Autowired
	private AttendanceService attendanceService;
	@Autowired
	private AdminAttendancePolicyService adminService;
	@Autowired
	private MySQLContainer mysql;

	/** 정책 수정이 남긴 감사 기록이 관리자 사용자를 참조하므로 부모 정리(사용자 삭제) 전에 지운다. */
	@AfterEach
	void deleteAuditLogs() {
		jdbc.update("delete from audit_logs where actor_id = ?", bytes(adminId));
	}

	private User admin() {
		return new User(adminId, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("예약 일일 정책을 수정하는 트랜잭션이 끝나기 전에 시작 시각이 지나 들어온 출석은 기다렸다가 수정된 수량으로 지급한다")
	void attendanceWaitsForDailyPolicyEditAndUsesEditedCount() throws Exception {
		// given: 수정 트랜잭션이 시작 시각 전에 수정을 마치고 커밋 직전에 멈춘다
		Instant beforeStart = Instant.parse("2026-09-15T05:00:00Z");
		Instant start = Instant.parse("2026-09-15T06:00:00Z");
		UUID policyId = policies.dailyPolicy(adminId, 1, start, null);
		policies.streakPolicySet(adminId, LocalDate.parse("2026-01-01"), AttendanceSeeds.DEFAULT_MILESTONES);
		clock.set(beforeStart);
		CountDownLatch edited = new CountDownLatch(1);
		CountDownLatch releaseEdit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> edit = executor.submit(() -> transaction.execute(status -> {
				adminService.updateDailyPolicy(admin(), policyId, 5);
				edited.countDown();
				await(releaseEdit);
				return null;
			}));
			assertThat(edited.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

			// when: 시작 시각이 지난 뒤 출석이 들어와 정책 행의 잠금을 기다린다
			clock.set(Instant.parse("2026-09-15T07:00:00Z"));
			Future<AttendanceReceipt> attend = executor.submit(() -> attendanceService.attend(userId));
			new LockWaitProbe(mysql).awaitLockWaits(1);
			assertThat(attend.isDone()).isFalse();
			releaseEdit.countDown();
			edit.get(WAIT_SECONDS, TimeUnit.SECONDS);
			AttendanceReceipt receipt = attend.get(WAIT_SECONDS, TimeUnit.SECONDS);

			// then
			assertThat(receipt.isCreated()).isTrue();
			assertThat(count("select ticket_count from attendance_reward_claims where user_id = ? and reward_type = 'DAILY'",
					bytes(userId))).isEqualTo(5);
		} finally {
			releaseEdit.countDown();
			ConcurrentTasks.shutdownAndAwait(executor);
		}
	}

	@Test
	@DisplayName("예약 연속 정책 단계를 바꾸는 트랜잭션이 끝나기 전에 적용월이 시작해 들어온 출석은 기다렸다가 수정된 단계로 현황을 만든다")
	void attendanceWaitsForStreakPolicyEdit() throws Exception {
		// given: 10/31 23:00 KST에 11월 묶음의 단계를 바꾸는 트랜잭션이 커밋 직전에 멈춘다
		policies.dailyPolicy(adminId, 1, Instant.parse("2025-12-31T15:00:00Z"), null);
		UUID setId = policies.streakPolicySet(adminId, LocalDate.parse("2026-11-01"), Map.of(1, 1));
		clock.set(Instant.parse("2026-10-31T14:00:00Z"));
		CountDownLatch edited = new CountDownLatch(1);
		CountDownLatch releaseEdit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> edit = executor.submit(() -> transaction.execute(status -> {
				adminService.updateStreakPolicy(admin(), setId, java.util.List.of(new MilestoneDraft(1, 9)));
				edited.countDown();
				await(releaseEdit);
				return null;
			}));
			assertThat(edited.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

			// when: 11월 1일 00:30 KST 출석이 묶음 행의 잠금을 기다린다
			clock.set(Instant.parse("2026-10-31T15:30:00Z"));
			Future<AttendanceReceipt> attend = executor.submit(() -> attendanceService.attend(userId));
			new LockWaitProbe(mysql).awaitLockWaits(1);
			assertThat(attend.isDone()).isFalse();
			releaseEdit.countDown();
			edit.get(WAIT_SECONDS, TimeUnit.SECONDS);
			AttendanceReceipt receipt = attend.get(WAIT_SECONDS, TimeUnit.SECONDS);

			// then
			assertThat(receipt.isCreated()).isTrue();
			assertThat(count("select count(*) from attendance_streaks where user_id = ? and policy_set_id = ?",
					bytes(userId), bytes(setId))).isEqualTo(1);
			assertThat(count("select reward_ticket_count from attendance_streak_policies where policy_set_id = ?",
					bytes(setId))).isEqualTo(9);
			assertThat(count("select ticket_count from attendance_reward_claims where user_id = ? and reward_type = 'STREAK'",
					bytes(userId))).isEqualTo(9);
		} finally {
			releaseEdit.countDown();
			ConcurrentTasks.shutdownAndAwait(executor);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("대기 시간이 지났다.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
