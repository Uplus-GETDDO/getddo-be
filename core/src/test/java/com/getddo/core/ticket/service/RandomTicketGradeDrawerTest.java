package com.getddo.core.ticket.service;

import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.domain.TicketGrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RandomTicketGradeDrawerTest {

	/** 실버 18%, 골드 2%, 플래티넘 0.02%(확정 확률). 브론즈는 나머지 79.98%다. */
	private static RandomTicketGradeDrawer withPlatinum(int roll) {
		return new RandomTicketGradeDrawer(new FixedRoll(roll), 1800, 200, 2);
	}

	@ParameterizedTest
	@CsvSource({"0,BRONZE", "7997,BRONZE", "7998,SILVER", "9797,SILVER", "9798,GOLD", "9997,GOLD", "9998,PLATINUM",
			"9999,PLATINUM"})
	@DisplayName("미션·게임은 0~9999 중 7998 미만 브론즈, 9798 미만 실버, 9998 미만 골드, 나머지 플래티넘이다")
	void drawsByThreshold(int roll, TicketGrade expected) {
		// given
		RandomTicketGradeDrawer drawer = withPlatinum(roll);
		// when
		// then
		assertThat(drawer.draw(GrantSourceType.MISSION)).isEqualTo(expected);
		assertThat(drawer.draw(GrantSourceType.GAME)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({"0,BRONZE", "7999,BRONZE", "8000,SILVER", "9799,SILVER", "9800,GOLD", "9999,GOLD"})
	@DisplayName("플래티넘 확률이 0이면 기존과 같은 80/18/2 구간이고 플래티넘은 나오지 않는다")
	void platinumDisabledKeepsPreviousSplit(int roll, TicketGrade expected) {
		// given
		RandomTicketGradeDrawer drawer = new RandomTicketGradeDrawer(new FixedRoll(roll), 1800, 200, 0);
		// when
		// then
		assertThat(drawer.draw(GrantSourceType.MISSION)).isEqualTo(expected);
	}

	@Test
	@DisplayName("설정이 없으면 확정 확률 79.98/18/2/0.02로 동작한다")
	void defaultsToConfirmedProbabilities() {
		// given
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			// JVM 시스템 속성과 환경 변수가 기본값을 덮어쓰지 않도록 격리한다
			context.getEnvironment().getPropertySources()
					.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
			context.getEnvironment().getPropertySources()
					.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
			context.register(RandomTicketGradeDrawer.class);
			context.refresh();
			// when
			RandomTicketGradeDrawer drawer = context.getBean(RandomTicketGradeDrawer.class);
			// then
			assertThat(ReflectionTestUtils.getField(drawer, "bronzeUpperBound")).isEqualTo(7998);
			assertThat(ReflectionTestUtils.getField(drawer, "silverUpperBound")).isEqualTo(9798);
			assertThat(ReflectionTestUtils.getField(drawer, "goldUpperBound")).isEqualTo(9998);
		}
	}

	@Test
	@DisplayName("출석은 난수와 관계없이 브론즈로 고정한다")
	void attendanceIsAlwaysBronze() {
		// given
		RandomTicketGradeDrawer drawer = withPlatinum(9999);
		// when
		TicketGrade grade = drawer.draw(GrantSourceType.ATTENDANCE);
		// then
		assertThat(grade).isEqualTo(TicketGrade.BRONZE);
	}

	@Test
	@DisplayName("난수 범위는 0 이상 10000 미만이다")
	void rollsWithinTenThousand() {
		// given
		FixedRoll random = new FixedRoll(0);
		RandomTicketGradeDrawer drawer = new RandomTicketGradeDrawer(random, 1800, 200, 2);
		// when
		drawer.draw(GrantSourceType.MISSION);
		// then
		assertThat(random.lastBound).isEqualTo(10_000);
	}

	@Test
	@DisplayName("모든 확률이 0이면 항상 브론즈이고, 합이 정확히 10000이면 브론즈는 나오지 않는다")
	void extremeConfigurations() {
		assertThat(new RandomTicketGradeDrawer(new FixedRoll(9999), 0, 0, 0).draw(GrantSourceType.GAME))
				.isEqualTo(TicketGrade.BRONZE);
		assertThat(new RandomTicketGradeDrawer(new FixedRoll(0), 5000, 3000, 2000).draw(GrantSourceType.GAME))
				.isEqualTo(TicketGrade.SILVER);
	}

	@Test
	@DisplayName("확률이 음수이거나 합이 10000을 넘으면 만들 수 없다")
	void rejectsInvalidConfiguration() {
		assertThatThrownBy(() -> new RandomTicketGradeDrawer(new FixedRoll(0), -1, 200, 2))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RandomTicketGradeDrawer(new FixedRoll(0), 1800, -1, 2))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RandomTicketGradeDrawer(new FixedRoll(0), 1800, 200, -1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RandomTicketGradeDrawer(new FixedRoll(0), 5000, 3000, 2001))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RandomTicketGradeDrawer(new FixedRoll(0), Integer.MAX_VALUE, Integer.MAX_VALUE, 2))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static final class FixedRoll implements RandomGenerator {

		private final int roll;
		private int lastBound;

		FixedRoll(int roll) {
			this.roll = roll;
		}

		@Override
		public int nextInt(int bound) {
			lastBound = bound;
			return roll;
		}

		@Override
		public long nextLong() {
			throw new UnsupportedOperationException();
		}
	}
}
