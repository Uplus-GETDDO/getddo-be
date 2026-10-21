package com.getddo.db.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.domain.StreakMilestone;
import com.getddo.core.attendance.domain.StreakPolicyRecord;
import com.getddo.core.attendance.repository.AttendancePolicyAdminRepository;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/** 예약 출석 정책 수정 쿼리를 Flyway로 만든 실제 MySQL 스키마(V002·V004)에서 검증한다. */
class AttendancePolicyAdminRepositoryIntegrationTest extends AttendanceIntegrationTestSupport {

	private static final Instant TOMORROW = Instant.parse("2026-10-17T15:00:00Z");
	private static final LocalDate NOVEMBER = LocalDate.parse("2026-11-01");

	@Autowired
	private AttendancePolicyAdminRepository repository;

	@Test
	@DisplayName("일일 정책의 보상 수량만 바뀌고 적용 시점·종료 시각·다른 필드는 그대로이며 없는 ID는 비어 있다")
	void updatesOnlyRewardTicketCountOfDailyPolicy() {
		// given
		UUID policyId = policies.dailyPolicy(adminId, 1, TOMORROW, null);
		// when
		DailyAttendancePolicy updated = transaction.execute(status -> {
			assertThat(repository.findDailyPolicyForUpdate(policyId)).isPresent();
			return repository.updateDailyRewardTicketCount(policyId, 4);
		});
		Optional<DailyAttendancePolicy> missing = transaction
				.execute(status -> repository.findDailyPolicyForUpdate(UUID.randomUUID()));
		// then
		assertThat(updated.getRewardTicketCount()).isEqualTo(4);
		assertThat(updated.getEffectiveFrom()).isEqualTo(TOMORROW);
		assertThat(updated.getEffectiveUntil()).isNull();
		assertThat(updated.getCreatedBy()).isEqualTo(adminId);
		assertThat(count("select reward_ticket_count from reward_policies where id = ?", (Object) bytes(policyId)))
				.isEqualTo(4);
		assertThat(missing).isEmpty();
	}

	@Test
	@DisplayName("연속 출석 정책의 단계를 같은 일수를 포함해 통째로 바꿔도 UNIQUE에 걸리지 않고 적용월과 다른 달 묶음의 단계는 그대로다")
	void replacesStreakMilestonesWithSameDays() {
		// given
		UUID setId = policies.streakPolicySet(adminId, NOVEMBER, Map.of(7, 1, 14, 2));
		UUID otherSetId = policies.streakPolicySet(adminId, LocalDate.parse("2026-12-01"), Map.of(7, 1, 14, 2, 21, 3));
		// when
		StreakPolicyRecord found = transaction.execute(status -> repository.findStreakPolicyForUpdate(setId))
				.orElseThrow();
		StreakPolicyRecord replaced = transaction.execute(status -> {
			repository.findStreakPolicyForUpdate(setId);
			return repository.replaceStreakMilestones(setId,
					List.of(new MilestoneDraft(7, 5), new MilestoneDraft(28, 9)));
		});
		// then
		assertThat(found.getMilestones()).extracting(StreakMilestone::getMilestoneDays).containsExactly(7, 14);
		assertThat(replaced.getEffectiveMonth()).isEqualTo(NOVEMBER);
		assertThat(replaced.getMilestones()).extracting(StreakMilestone::getMilestoneDays)
				.containsExactly(7, 28);
		assertThat(replaced.getMilestones()).extracting(StreakMilestone::getRewardTicketCount)
				.containsExactly(5, 9);
		assertThat(count("select count(*) from attendance_streak_policies where policy_set_id = ?",
				(Object) bytes(setId))).isEqualTo(2);
		assertThat(count("select count(*) from attendance_streak_policy_sets where id = ?", (Object) bytes(setId)))
				.isEqualTo(1);
		assertThat(count("select count(*) from attendance_streak_policies where policy_set_id = ?",
				(Object) bytes(otherSetId))).isEqualTo(3);
	}

	@Test
	@DisplayName("출석이 아닌 보상 정책은 잠가 읽지도 수량을 바꾸지도 않는다")
	void ignoresNonAttendancePolicies() {
		// given
		UUID missionPolicyId = seeds.missionRewardPolicy(adminId);
		// when
		Optional<DailyAttendancePolicy> found = transaction
				.execute(status -> repository.findDailyPolicyForUpdate(missionPolicyId));
		transaction.execute(status -> repository.updateDailyRewardTicketCount(missionPolicyId, 4));
		// then
		assertThat(found).isEmpty();
		assertThat(count("select reward_ticket_count from reward_policies where id = ?", (Object) bytes(missionPolicyId)))
				.isEqualTo(1);
	}
}
