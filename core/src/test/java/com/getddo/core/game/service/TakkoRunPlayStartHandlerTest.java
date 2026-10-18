package com.getddo.core.game.service;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.game.domain.Game;
import com.getddo.core.game.domain.TakkoRunRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakkoRunPlayStartHandlerTest {

	private static final String RULES = "{\"minimumRewardScore\":400,\"maxTicks\":72000}";
	private final TakkoRunPlayStartHandler handler = new TakkoRunPlayStartHandler();

	@Test
	@DisplayName("FE의 TAKKO_RUN 코드·버전만 지원하고 보상기준과 최대 tick 규칙을 준비한다")
	void preparesVersionedRules() {
		// given
		Game game = game(TakkoRunRules.GAME_CODE, TakkoRunRules.RULE_VERSION, RULES);
		// when / then
		assertThat(handler.supports(game)).isTrue();
		assertThat(handler.prepare(game).getGeneratorVersion()).isEqualTo(TakkoRunRules.GENERATOR_VERSION);
		assertThat(((TakkoRunRules) handler.prepare(game).getRules()).getMaxTicks()).isEqualTo(72_000);
		assertThat(((TakkoRunRules) handler.prepare(game).getRules()).getMinimumRewardScore()).isEqualTo(400);
		assertThat(handler.supports(game("TAKKORUN", TakkoRunRules.RULE_VERSION, RULES))).isFalse();
		assertThat(handler.supports(game("COLOR_WORD", TakkoRunRules.RULE_VERSION, RULES))).isFalse();
		assertThat(handler.supports(game(TakkoRunRules.GAME_CODE, "v1", RULES))).isFalse();
	}

	@Test
	@DisplayName("규칙 누락·숫자 문자열·중복 키·다른 한도는 기본값으로 보정하지 않는다")
	void refusesCorruptRules() {
		// given / when / then
		for (String rules : new String[] {"{}", RULES.replace("72000", "\"72000\""),
				RULES.replace("\"maxTicks\":72000", "\"maxTicks\":72000,\"maxTicks\":72000"),
				RULES.replace("72000", "36000")}) {
			assertThatThrownBy(() -> handler.prepare(game(TakkoRunRules.GAME_CODE, TakkoRunRules.RULE_VERSION, rules)))
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	@DisplayName("저장 규칙 JSON 오류는 서버 오류로 변환하며 원문 값을 예외 원인에 남기지 않는다")
	void hidesInvalidStoredJson() {
		// given
		String snapshot = "{\"private-marker\": not-json}";
		// when / then
		assertThatThrownBy(() -> handler.prepare(game(TakkoRunRules.GAME_CODE, TakkoRunRules.RULE_VERSION, snapshot)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("저장된 타꼬런 규칙이 올바르지 않습니다.")
				.satisfies(error -> {
					assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
					assertThat(error.getCause().getMessage()).doesNotContain("private-marker", "not-json");
					assertThat(error.getCause().getCause()).isNull();
					assertThat(error.getCause().getStackTrace()).isNotEmpty();
				});
	}

	private static Game game(String code, String version, String rules) {
		return new Game(UUID.randomUUID(), code, "타꼬런", null, rules, version, true);
	}
}
