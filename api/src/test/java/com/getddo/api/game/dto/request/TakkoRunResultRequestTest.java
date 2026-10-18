package com.getddo.api.game.dto.request;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.game.domain.GameResultSubmission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakkoRunResultRequestTest {

	private final JsonMapper json = JsonMapper.builder().build();
	private final UUID game = UUID.randomUUID();
	private final UUID play = UUID.randomUUID();

	@Test
	@DisplayName("타꼬런 입력의 JSON 키 순서와 공백은 해시에 영향을 주지 않고 종료 tick·같은 tick 입력 순서는 영향을 준다")
	void hashesNormalizedTimeline() {
		// given
		GameResultSubmission first = parse("""
				{"endTick":174,"inputs":[{"tick":0,"type":"press"},{"tick":0,"type":"release"}]}
				""");
		GameResultSubmission reorderedKeys = parse("""
				{"inputs":[{"type":"press","tick":0},{"type":"release","tick":0}],"endTick":174}
				""");
		GameResultSubmission reorderedInputs = parse("""
				{"endTick":174,"inputs":[{"tick":0,"type":"release"},{"tick":0,"type":"press"}]}
				""");
		// when / then
		assertThat(first.getResultHash()).isEqualTo(reorderedKeys.getResultHash())
				.isNotEqualTo(reorderedInputs.getResultHash());
		assertThat(first.getResultHash()).isNotEqualTo(parse("""
				{"endTick":175,"inputs":[{"tick":0,"type":"press"},{"tick":0,"type":"release"}]}
				""").getResultHash());
		assertThatThrownBy(() -> first.getTakkoRun().getInputs().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{}", "{\"endTick\":174}", "{\"endTick\":\"174\",\"inputs\":[]}",
			"{\"endTick\":1.5,\"inputs\":[]}", "{\"endTick\":2147483648,\"inputs\":[]}",
			"{\"endTick\":174,\"inputs\":[null]}",
			"{\"endTick\":174,\"inputs\":[{\"type\":\"press\"}]}",
			"{\"endTick\":174,\"inputs\":[{\"tick\":0,\"type\":\"PRESS\"}]}",
			"{\"endTick\":174,\"inputs\":[{\"tick\":0.5,\"type\":\"press\"}]}",
			"{\"endTick\":174,\"inputs\":[],\"answers\":[]}",
			"{\"totalTicks\":174,\"inputs\":[]}",
			"{\"endTick\":174,\"totalTicks\":175,\"inputs\":[]}",
			"{\"endTick\":174,\"inputs\":[{\"tick\":0,\"type\":\"press\",\"action\":\"PRESS\"}]}"
	})
	@DisplayName("타꼬런의 누락·잘못된 정수/enum·색깔 입력 혼합은 COMMON-005다")
	void rejectsMalformedInput(String input) {
		// given / when / then
		assertThatThrownBy(() -> parse(input)).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode().getCode()).isEqualTo("COMMON-005"));
	}

	@Test
	@DisplayName("입력 10001개는 INVALID 저장 전에 GAME-008로 거절한다")
	void rejectsOversizedInput() {
		// given
		String inputs = "{\"tick\":0,\"type\":\"press\"},".repeat(10_000)
				+ "{\"tick\":0,\"type\":\"press\"}";
		// when / then
		assertThatThrownBy(() -> parse("{\"endTick\":174,\"inputs\":[" + inputs + "]}"))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode().getCode()).isEqualTo("GAME-008"));
	}

	private GameResultSubmission parse(String input) {
		return new GameResultRequest("token", json.readTree(input)).toDomain(game, play);
	}
}
