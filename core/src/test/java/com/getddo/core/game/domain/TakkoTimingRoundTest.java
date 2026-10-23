package com.getddo.core.game.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TakkoTimingRoundTest {

	@Test
	@DisplayName("성공·퍼펙트 경계는 포함하고 바로 바깥 정수 ms는 각각 일반 성공 또는 실패다")
	void includesBothBoundaries() {
		// given: 경계가 이진수로 정확히 표현되는 구간
		TakkoTimingRound round = new TakkoTimingRound(0.5, 0.625, 1024);
		// when / then
		assertThat(round.pointsAt(192)).isEqualTo(10);
		assertThat(round.pointsAt(832)).isEqualTo(10);
		assertThat(round.pointsAt(191)).isZero();
		assertThat(round.pointsAt(833)).isZero();
		assertThat(round.pointsAt(416)).isEqualTo(20);
		assertThat(round.pointsAt(608)).isEqualTo(20);
		assertThat(round.pointsAt(415)).isEqualTo(10);
		assertThat(round.pointsAt(609)).isEqualTo(10);
	}

	@Test
	@DisplayName("seed1의 두 번째 구간은 추가 난수 없이 반대편으로 바꾸고 난이도 하한을 유지한다")
	void reproducesSeedAndDifficultyFloor() {
		// given
		TakkoTimingRoundGenerator generator = new TakkoTimingRoundGenerator(1);
		// when
		TakkoTimingRound first = generator.next(0);
		TakkoTimingRound second = generator.next(1);
		TakkoTimingRound last = generator.next(10_000);
		// then
		assertThat(first.getCenter()).isEqualTo(0.1900390291167423);
		assertThat(second.getCenter()).isEqualTo(0.8050791202508845);
		assertThat(second.getSweepMs()).isEqualTo(1540);
		assertThat(last.getWidth()).isEqualTo(0.09);
		assertThat(last.getSweepMs()).isEqualTo(650);
	}
}
