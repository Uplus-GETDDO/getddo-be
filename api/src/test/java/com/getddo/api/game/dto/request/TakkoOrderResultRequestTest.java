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

class TakkoOrderResultRequestTest {

	private final JsonMapper json = JsonMapper.builder().build();
	private final UUID game = UUID.randomUUID();
	private final UUID play = UUID.randomUUID();

	@Test
	@DisplayName("주문 재시도는 키 순서를 무시하고 메뉴 순서와 주문 경계가 달라지면 다른 해시다")
	void hashesCanonicalOrders() {
		// given
		GameResultSubmission first = parse("{\"rounds\":[{\"sequence\":1,\"picks\":[\"scallion\",\"cheese\"]},{\"sequence\":2,\"picks\":[\"shrimp\"]}]}");
		GameResultSubmission same = parse("{\"rounds\":[{\"picks\":[\"scallion\",\"cheese\"],\"sequence\":1},{\"picks\":[\"shrimp\"],\"sequence\":2}]}");
		// when / then
		assertThat(first.getResultHash()).isEqualTo(same.getResultHash());
		assertThat(first.getResultHash()).isNotEqualTo(parse("{\"rounds\":[{\"sequence\":1,\"picks\":[\"scallion\"]},{\"sequence\":2,\"picks\":[\"cheese\",\"shrimp\"]}]}").getResultHash());
		assertThat(first.getResultHash()).isNotEqualTo(parse("{\"rounds\":[{\"sequence\":1,\"picks\":[\"cheese\",\"scallion\"]},{\"sequence\":2,\"picks\":[\"shrimp\"]}]}").getResultHash());
		assertThatThrownBy(() -> first.getTakkoOrder().getRounds().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.getTakkoOrder().getRounds().getFirst().getPicks().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"rounds\":null}", "{\"rounds\":{}}", "{\"rounds\":[null]}",
			"{\"rounds\":[{\"sequence\":1}]}", "{\"rounds\":[{\"sequence\":1.1,\"picks\":[]}]}",
			"{\"rounds\":[{\"sequence\":1,\"picks\":[null]}]}", "{\"rounds\":[{\"sequence\":1,\"picks\":[\"CHEESE\"]}]}",
			"{\"rounds\":[],\"answers\":[]}", "{\"rounds\":[],\"inputs\":[]}", "{\"rounds\":[],\"taps\":[]}",
			"{\"rounds\":[],\"endTick\":0}", "{\"rounds\":[],\"totalTicks\":0}"})
	@DisplayName("누락·소수 번호·잘못된 메뉴·다른 게임 입력 혼합은 COMMON-005다")
	void rejectsMalformed(String input) {
		// given / when / then
		assertThatThrownBy(() -> parse(input)).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode().getCode()).isEqualTo("COMMON-005"));
	}

	@Test
	@DisplayName("주문10000개는 파싱하고10001개는 GAME-008로 거절한다")
	void boundsRoundCount() {
		// given
		String round = "{\"sequence\":1,\"picks\":[]}";
		String maximum = round + ("," + round).repeat(9999);
		// when / then
		assertThat(parse("{\"rounds\":[" + maximum + "]}").getTakkoOrder().getRounds()).hasSize(10_000);
		assertThatThrownBy(() -> parse("{\"rounds\":[" + maximum + "," + round + "]}"))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode().getCode()).isEqualTo("GAME-008"));
	}

	private GameResultSubmission parse(String input) {
		return new GameResultRequest("token", json.readTree(input)).toDomain(game, play);
	}
}
