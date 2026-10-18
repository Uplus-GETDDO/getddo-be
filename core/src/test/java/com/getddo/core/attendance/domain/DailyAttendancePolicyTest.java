package com.getddo.core.attendance.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyAttendancePolicyTest {

	private static final Instant FROM = Instant.parse("2026-10-17T15:00:00Z");

	private static DailyAttendancePolicy policy(int count, Instant until) {
		return new DailyAttendancePolicy(UUID.randomUUID(), count, FROM, until, UUID.randomUUID(), FROM);
	}

	@Test
	@DisplayName("종료 시각이 없는 열린 정책과 시작보다 늦은 종료 시각을 가진 정책을 만들 수 있다")
	void createsOpenAndClosedPolicies() {
		assertThat(policy(1, null).getEffectiveUntil()).isNull();
		assertThat(policy(3, FROM.plusSeconds(1)).getEffectiveUntil()).isEqualTo(FROM.plusSeconds(1));
	}

	@Test
	@DisplayName("보상 수량이 1 미만이면 만들 수 없다")
	void rejectsNonPositiveReward() {
		assertThatThrownBy(() -> policy(0, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("종료 시각이 시작 시각과 같거나 앞서면 만들 수 없다")
	void rejectsInvalidPeriod() {
		assertThatThrownBy(() -> policy(1, FROM)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy(1, FROM.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
	}
}
