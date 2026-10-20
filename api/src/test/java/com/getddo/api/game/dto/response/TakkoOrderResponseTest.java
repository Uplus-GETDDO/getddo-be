package com.getddo.api.game.dto.response;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoOrderRules;

import static org.assertj.core.api.Assertions.assertThat;

class TakkoOrderResponseTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	@DisplayName("주문 공개 규칙은 목숨·길이 세 필드만 제공하고 내부 저장 필드는 숨긴다")
	void exposesOnlyPublicOrderRules() {
		// given
		TakkoOrderRules rules = TakkoOrderRules.fromSnapshot(
				"{\"initialLives\":3,\"startLength\":3,\"maxLength\":8,\"privateNote\":\"hidden\"}");
		// when
		JsonNode data = json.valueToTree(GameRulesResponse.from(rules));
		// then
		assertThat(data.size()).isEqualTo(3);
		assertThat(data.path("initialLives").intValue()).isEqualTo(3);
		assertThat(data.path("startLength").intValue()).isEqualTo(3);
		assertThat(data.path("maxLength").intValue()).isEqualTo(8);
		assertThat(data.has("privateNote")).isFalse();
	}

	@Test
	@DisplayName("주문 gameData는 seed·생성 버전만 제공하고 타꼬런의 tickMs를 추가하지 않는다")
	void preservesOrderGameDataShape() {
		// given
		GamePlay play = new GamePlay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				TakkoOrderRules.RULE_VERSION, "{}", "a".repeat(64), TakkoOrderRules.GENERATOR_VERSION,
				1L, Instant.EPOCH, null, null, null);
		// when
		JsonNode data = json.valueToTree(GamePlayDataResponse.from(play));
		// then
		assertThat(data.size()).isEqualTo(2);
		assertThat(data.path("seed").longValue()).isEqualTo(1);
		assertThat(data.path("generatorVersion").asString()).isEqualTo(TakkoOrderRules.GENERATOR_VERSION);
		assertThat(data.has("tickMs")).isFalse();
	}
}
