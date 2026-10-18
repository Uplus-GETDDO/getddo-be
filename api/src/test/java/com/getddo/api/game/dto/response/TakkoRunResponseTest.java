package com.getddo.api.game.dto.response;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.game.domain.ColorWordRules;
import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoRunRules;

import static org.assertj.core.api.Assertions.assertThat;

class TakkoRunResponseTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	@DisplayName("타꼬런만 tickMs를 제공하고 8.333으로 잘라내지 않는다")
	void exposesExactTickInterval() {
		// given
		GamePlay play = play(TakkoRunRules.RULE_VERSION, TakkoRunRules.GENERATOR_VERSION);
		// when
		JsonNode data = json.valueToTree(GamePlayDataResponse.from(play));
		// then
		assertThat(data.size()).isEqualTo(3);
		assertThat(data.path("tickMs").doubleValue()).isEqualTo(1000.0 / 120);
		assertThat(data.path("seed").longValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("색깔 게임의 gameData는 기존 generatorVersion·seed 두 필드만 유지한다")
	void preservesColorWordContract() {
		// given
		GamePlay play = play(ColorWordRules.RULE_VERSION, ColorWordRules.GENERATOR_VERSION);
		// when
		JsonNode data = json.valueToTree(GamePlayDataResponse.from(play));
		// then
		assertThat(data.size()).isEqualTo(2);
		assertThat(data.has("tickMs")).isFalse();
	}

	private static GamePlay play(String version, String generator) {
		return new GamePlay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), version, "{}",
				"a".repeat(64), generator, 1L, Instant.EPOCH, null, null, null);
	}
}
