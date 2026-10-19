package com.getddo.core.game.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.game.domain.Game;
import com.getddo.core.game.domain.GameStartConfiguration;
import com.getddo.core.game.domain.TakkoTimingRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakkoTimingPlayStartHandlerTest {

	private static final String RULES = "{\"points\":10,\"perfectPoints\":20,\"minimumRewardScore\":50}";
	private final TakkoTimingPlayStartHandler handler = new TakkoTimingPlayStartHandler();

	@Test
	@DisplayName("타이밍 코드와 규칙 버전이 함께 맞아야 시작·상세 규칙을 제공한다")
	void supportsOnlyTimingVersion() {
		// given
		Game game = game(TakkoTimingRules.GAME_CODE, TakkoTimingRules.RULE_VERSION, RULES);
		// when
		GameStartConfiguration configuration = handler.prepare(game);
		// then
		assertThat(handler.supports(game)).isTrue();
		assertThat(configuration.getGeneratorVersion()).isEqualTo(TakkoTimingRules.GENERATOR_VERSION);
		assertThat(configuration.getRules()).isInstanceOf(TakkoTimingRules.class);
		assertThat(handler.supports(game("COLOR_WORD", TakkoTimingRules.RULE_VERSION, RULES))).isFalse();
		assertThat(handler.supports(game("TAKKO_RUN", TakkoTimingRules.RULE_VERSION, RULES))).isFalse();
		assertThat(handler.supports(game(TakkoTimingRules.GAME_CODE, "v1", RULES))).isFalse();
	}

	@Test
	@DisplayName("저장 규칙의 누락·소수·중복·추가 JSON·버전별 값 불일치는 서버 설정 오류다")
	void rejectsInvalidStoredRules() {
		// given
		List<String> invalid = List.of("null", "[]", "{}", RULES + "{}",
				RULES.replace("\"points\":10,", ""), RULES.replace("\"points\":10", "\"points\":10.0"),
				RULES.replace("\"points\":10", "\"points\":\"10\""),
				RULES.replace("\"points\":10", "\"points\":10,\"points\":10"),
				RULES.replace("\"perfectPoints\":20", "\"perfectPoints\":10"),
				RULES.replace("\"minimumRewardScore\":50", "\"minimumRewardScore\":40"));
		// when / then
		for (String snapshot : invalid) {
			assertThatThrownBy(() -> handler.prepare(game(TakkoTimingRules.GAME_CODE,
					TakkoTimingRules.RULE_VERSION, snapshot))).isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	@DisplayName("손상된 저장 JSON의 값은 예외 메시지·원인 체인에 포함하지 않는다")
	void hidesStoredJsonInErrors() {
		// given
		String snapshot = "{\"private-marker\": not-json}";
		// when / then
		assertThatThrownBy(() -> handler.prepare(game(TakkoTimingRules.GAME_CODE,
				TakkoTimingRules.RULE_VERSION, snapshot)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("저장된 타이밍 게임 규칙이 올바르지 않습니다.")
				.satisfies(failure -> {
					assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
					assertThat(failure.getCause().getMessage()).doesNotContain("private-marker", "not-json");
					assertThat(failure.getCause().getCause()).isNull();
				});
	}

	private static Game game(String code, String version, String snapshot) {
		return new Game(UUID.randomUUID(), code, "타꼬 타이밍 맞추기", null, snapshot, version, true);
	}
}
