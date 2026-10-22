package com.getddo.db.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.StreakPolicyRecord;
import com.getddo.core.attendance.service.AdminAttendancePolicyService;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.NamedValue;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 출석 정책 조회(AP01·AP03)가 정책을 만든 관리자의 현재 이름을 함께 돌려주는지 실제 MySQL로 검증한다. */
class AdminAttendancePolicyNamesIntegrationTest extends AttendanceIntegrationTestSupport {

	private static final PageQuery PAGE = new PageQuery(1, 20);

	@Autowired
	private AdminAttendancePolicyService adminService;

	private User admin() {
		return new User(adminId, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
	}

	private String nameOf(UUID user) {
		return jdbc.queryForObject("select name from users where id = ?", String.class, bytes(user));
	}

	@Test
	@DisplayName("일일·연속 정책 목록이 작성자의 현재 이름을 담고 이름을 바꾸면 다음 조회에 바뀐 이름이 나간다")
	void policiesCarryCreatorName() {
		// given
		UUID dailyId = policies.dailyPolicy(adminId, 1, Instant.parse("2026-12-01T00:00:00Z"), null);
		UUID streakId = policies.streakPolicySet(adminId, LocalDate.parse("2027-01-01"), Map.of(7, 2));

		// when
		PageResult<NamedValue<DailyAttendancePolicy>> daily = adminService.findDailyPolicies(admin(), PAGE);
		PageResult<NamedValue<StreakPolicyRecord>> streak = adminService.findStreakPolicies(admin(), PAGE);

		// then
		assertThat(daily.getItems()).filteredOn(named -> named.getValue().getId().equals(dailyId))
				.hasSize(1).allSatisfy(named -> assertThat(named.getName()).isEqualTo(nameOf(adminId)));
		assertThat(streak.getItems()).filteredOn(named -> named.getValue().getId().equals(streakId))
				.hasSize(1).allSatisfy(named -> assertThat(named.getName()).isEqualTo(nameOf(adminId)));

		// when: 작성자의 이름이 바뀐다
		jdbc.update("update users set name = ? where id = ?", "바뀐 관리자", bytes(adminId));
		PageResult<NamedValue<DailyAttendancePolicy>> renamed = adminService.findDailyPolicies(admin(), PAGE);

		// then: 작성자 ID는 같고 이름만 현재 이름이다
		assertThat(renamed.getItems()).filteredOn(named -> named.getValue().getId().equals(dailyId))
				.hasSize(1).allSatisfy(named -> {
					assertThat(named.getValue().getCreatedBy()).isEqualTo(adminId);
					assertThat(named.getName()).isEqualTo("바뀐 관리자");
				});
	}
}
