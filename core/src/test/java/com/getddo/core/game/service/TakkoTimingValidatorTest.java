package com.getddo.core.game.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoTimingRules;
import com.getddo.core.game.domain.TakkoTimingSubmission;
import com.getddo.core.game.domain.TakkoTimingTap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakkoTimingValidatorTest {

	private final TakkoTimingValidator validator = new TakkoTimingValidator();
	private final GamePlay play = new GamePlay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			TakkoTimingRules.RULE_VERSION, "{\"points\":10,\"perfectPoints\":20,\"minimumRewardScore\":50}",
			"a".repeat(64), TakkoTimingRules.GENERATOR_VERSION, 1L, Instant.parse("2026-10-18T00:00:00Z"),
			null, null, null);

	@ParameterizedTest
	@CsvSource({"0,0", "304:1240:0,40", "304:1240:954:0,50", "304:1240:850:0,60", "2896:1840:954:0,50"})
	@DisplayName("seed1 고정 입력은 일반·퍼펙트·반사 이동을 재현하고 마지막 실패에서만 점수를 확정한다")
	void replaysFixedTimeline(String timeline, long expected) {
		// given
		TakkoTimingSubmission input = input(timeline);
		// when / then
		assertThat(validator.validate(play, input)).isEqualTo(expected);
	}

	@Test
	@DisplayName("미완료·중간 실패·불연속 번호·음수 시간은 통계에 넣을 수 없는 무효 기록이다")
	void rejectsUnfinishedAndInvalidTimeline() {
		// given
		List<TakkoTimingSubmission> invalid = List.of(input(""), input("304"), input("0:304"),
				new TakkoTimingSubmission(List.of(new TakkoTimingTap(2, 0))), input("-1"));
		// when / then
		for (TakkoTimingSubmission candidate : invalid) {
			assertThat(validator.validate(play, candidate)).isNull();
		}
	}

	@Test
	@DisplayName("저장된 규칙이 손상되면 사용자 기록을 INVALID로 확정하지 않고 설정 오류를 알린다")
	void rejectsCorruptSnapshot() {
		// given
		GamePlay corrupt = new GamePlay(play.getId(), play.getGameId(), play.getUserId(), play.getRuleVersion(),
				"{}", play.getPlayTokenHash(), play.getGeneratorVersion(), 1L, play.getStartedAt(), null, null, null);
		// when / then
		assertThatThrownBy(() -> validator.validate(corrupt, input("0"))).isInstanceOf(IllegalStateException.class);
	}

	private static TakkoTimingSubmission input(String times) {
		List<TakkoTimingTap> taps = new ArrayList<>();
		if (!times.isEmpty()) {
			for (String value : times.split(":")) taps.add(new TakkoTimingTap(taps.size() + 1, Long.parseLong(value)));
		}
		return new TakkoTimingSubmission(taps);
	}
}
