package com.getddo.api.game.dto.response;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoTimingRules;

import static org.assertj.core.api.Assertions.assertThat;

class TakkoTimingResponseTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	@DisplayName("타이밍 공개 규칙은 점수 세 필드만 제공하고 내부 저장 필드는 숨긴다")
	void exposesOnlyPublicTimingRules() {
		// given
		TakkoTimingRules rules = TakkoTimingRules.fromSnapshot(
				"{\"points\":10,\"perfectPoints\":20,\"minimumRewardScore\":50,\"privateNote\":\"hidden\"}");
		// when
		JsonNode data = json.valueToTree(GameRulesResponse.from(rules));
		// then
		assertThat(data.size()).isEqualTo(3);
		assertThat(data.path("points").intValue()).isEqualTo(10);
		assertThat(data.path("perfectPoints").intValue()).isEqualTo(20);
		assertThat(data.path("minimumRewardScore").intValue()).isEqualTo(50);
		assertThat(data.has("privateNote")).isFalse();
	}

	@Test
	@DisplayName("타이밍 gameData는 seed·생성 버전만 제공하고 타꼬런의 tickMs를 추가하지 않는다")
	void preservesTimingGameDataShape() {
		// given
		GamePlay play = new GamePlay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				TakkoTimingRules.RULE_VERSION, "{}", "a".repeat(64), TakkoTimingRules.GENERATOR_VERSION,
				1L, Instant.EPOCH, null, null, null);
		// when
		JsonNode data = json.valueToTree(GamePlayDataResponse.from(play));
		// then
		assertThat(data.size()).isEqualTo(2);
		assertThat(data.path("seed").longValue()).isEqualTo(1);
		assertThat(data.path("generatorVersion").asString()).isEqualTo(TakkoTimingRules.GENERATOR_VERSION);
		assertThat(data.has("tickMs")).isFalse();
	}
}
