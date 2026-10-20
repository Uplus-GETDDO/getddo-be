package com.getddo.api.game.dto.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.game.domain.GameResultSubmission;
import com.getddo.core.game.domain.TakkoTimingSubmission;
import com.getddo.core.game.domain.TakkoTimingTap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakkoTimingResultRequestTest {

	private final JsonMapper json = JsonMapper.builder().build();
	private final UUID game = UUID.randomUUID();
	private final UUID play = UUID.randomUUID();

	@Test
	@DisplayName("타이밍 재시도 해시는 JSON 키 순서가 아닌 원본 번호·정수 ms·배열 순서로 식별한다")
	void preservesCanonicalInput() {
		// given
		GameResultSubmission first = parse("{\"taps\":[{\"sequence\":1,\"elapsedMs\":304},{\"sequence\":2,\"elapsedMs\":0}]}");
		GameResultSubmission same = parse("{\"taps\":[{\"elapsedMs\":304,\"sequence\":1},{\"elapsedMs\":0,\"sequence\":2}]}");
		// when / then
		assertThat(first.getResultHash()).isEqualTo(same.getResultHash());
		assertThat(first.getResultHash()).isNotEqualTo(parse("{\"taps\":[{\"sequence\":1,\"elapsedMs\":305},{\"sequence\":2,\"elapsedMs\":0}]}").getResultHash());
		assertThat(first.getResultHash()).isNotEqualTo(parse("{\"taps\":[{\"sequence\":2,\"elapsedMs\":0},{\"sequence\":1,\"elapsedMs\":304}]}").getResultHash());
		assertThatThrownBy(() -> first.getTakkoTiming().getTaps().clear()).isInstanceOf(UnsupportedOperationException.class);
		List<TakkoTimingTap> mutable = new ArrayList<>(List.of(new TakkoTimingTap(1, 0)));
		TakkoTimingSubmission copied = new TakkoTimingSubmission(mutable);
		mutable.clear();
		assertThat(copied.getTaps()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"taps\":null}", "{\"taps\":{}}", "{\"taps\":[null]}",
			"{\"taps\":[{\"sequence\":1}]}", "{\"taps\":[{\"sequence\":1,\"elapsedMs\":0.5}]}",
			"{\"taps\":[{\"sequence\":\"1\",\"elapsedMs\":0}]}", "{\"taps\":[{\"sequence\":2147483648,\"elapsedMs\":0}]}",
			"{\"taps\":[{\"sequence\":1,\"elapsedMs\":9223372036854775808}]}",
			"{\"taps\":[],\"answers\":[]}", "{\"taps\":[],\"inputs\":[]}", "{\"taps\":[],\"endTick\":1}",
			"{\"taps\":[],\"totalTicks\":1}"})
	@DisplayName("누락·잘못된 정수·다른 게임 입력 혼합은 COMMON-005로 거절한다")
	void rejectsMalformed(String input) {
		// given / when / then
		assertThatThrownBy(() -> parse(input)).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode().getCode()).isEqualTo("COMMON-005"));
	}

	@Test
	@DisplayName("꽂기 10000개는 파싱하고 10001개는 기존 GAME-008로 거절한다")
	void boundsTapCount() {
		// given
		String tap = "{\"sequence\":1,\"elapsedMs\":0}";
		String maximum = tap + ("," + tap).repeat(9999);
		// when / then
		assertThat(parse("{\"taps\":[" + maximum + "]}").getTakkoTiming().getTaps()).hasSize(10_000);
		assertThatThrownBy(() -> parse("{\"taps\":[" + maximum + "," + tap + "]}"))
				.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.getErrorCode().getCode()).isEqualTo("GAME-008"));
	}

	private GameResultSubmission parse(String input) {
		return new GameResultRequest("token", json.readTree(input)).toDomain(game, play);
	}
}
