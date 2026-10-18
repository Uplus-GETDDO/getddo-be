package com.getddo.core.game.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoRunAction;
import com.getddo.core.game.domain.TakkoRunInput;
import com.getddo.core.game.domain.TakkoRunReplay;
import com.getddo.core.game.domain.TakkoRunRules;
import com.getddo.core.game.domain.TakkoRunSubmission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TakkoRunValidatorTest {

	private static final String RULES = "{\"minimumRewardScore\":400,\"maxTicks\":72000}";
	private final TakkoRunValidator validator = new TakkoRunValidator();

	@ParameterizedTest
	@CsvSource({"1,348,122", "123456789,347,121"})
	@DisplayName("120Hz·xorshift32 최초 장애물의 종료 tick과 점수는 독립 산식과 일치한다")
	void replaysFirstObstacle(long seed, int endTick, long score) {
		// given: 420/120=3.5px씩 이동, 144번째 진행에 x860에서 첫 장애물 생성.
		// seed1은 폭82, seed123456789는 폭76.5. 최초 충돌은 각각 349/348번째 진행이다.
		TakkoRunSubmission input = new TakkoRunSubmission(endTick, firstJump());
		// when / then
		assertThat(validator.validate(play(seed, RULES), input)).isEqualTo(score);
	}

	@Test
	@DisplayName("종료 tick은 마지막 진행 번호다: 직전 종료·충돌 뒤 추가 진행·10분 범위 초과는 무효다")
	void rejectsNonFinalAndUnboundedTicks() {
		// given
		GamePlay play = play(1, RULES);
		// when / then
		for (int endTick : new int[] {-1, 347, 349, TakkoRunRules.MAX_TICKS, Integer.MAX_VALUE}) {
			assertThat(validator.validate(play, new TakkoRunSubmission(endTick, firstJump()))).isNull();
		}
	}

	@Test
	@DisplayName("충돌이 없어도 마지막 번호71999에서만 활성10분 정상 종료다")
	void acceptsExactTimeLimit() {
		// given: 물리 성공 여부와 분리해 종료 경계와 반복 상한을 검증한다.
		try (MockedConstruction<TakkoRunReplay> replay = mockConstruction(TakkoRunReplay.class,
				(mock, context) -> when(mock.getScore()).thenReturn(4321L))) {
			// when / then
			assertThat(validator.validate(play(1, RULES),
					new TakkoRunSubmission(TakkoRunRules.MAX_TICKS - 1, firstJump()))).isEqualTo(4321L);
			verify(replay.constructed().getFirst(), times(TakkoRunRules.MAX_TICKS)).step();
		}
	}

	@Test
	@DisplayName("상한 밖 번호는 재생 객체를 만들기 전에 무효 처리한다")
	void rejectsBeforeReplay() {
		// given
		try (MockedConstruction<TakkoRunReplay> replay = mockConstruction(TakkoRunReplay.class)) {
			// when / then
			assertThat(validator.validate(play(1, RULES),
					new TakkoRunSubmission(Integer.MAX_VALUE, firstJump()))).isNull();
			assertThat(replay.constructed()).isEmpty();
		}
	}

	@Test
	@DisplayName("첫 점프 부재·입력 역순·음수·종료 tick 초과 입력은 무효다")
	void rejectsInputTimelineViolations() {
		// given
		List<List<TakkoRunInput>> inputs = List.of(
				List.of(), List.of(new TakkoRunInput(0, TakkoRunAction.RELEASE)),
				List.of(new TakkoRunInput(1, TakkoRunAction.PRESS)),
				List.of(new TakkoRunInput(0, TakkoRunAction.PRESS), new TakkoRunInput(-1, TakkoRunAction.RELEASE)),
				List.of(new TakkoRunInput(0, TakkoRunAction.PRESS), new TakkoRunInput(2, TakkoRunAction.RELEASE),
						new TakkoRunInput(1, TakkoRunAction.PRESS)),
				List.of(new TakkoRunInput(0, TakkoRunAction.PRESS), new TakkoRunInput(349, TakkoRunAction.RELEASE)));
		// when / then
		for (List<TakkoRunInput> input : inputs) {
			assertThat(validator.validate(play(1, RULES), new TakkoRunSubmission(348, input))).isNull();
		}
	}

	@Test
	@DisplayName("저장된 보상 기준·최대 tick 손상은 INVALID 대신 서버 오류다")
	void rejectsCorruptServerSnapshot() {
		// given
		TakkoRunSubmission submission = new TakkoRunSubmission(348, firstJump());
		// when / then
		for (String rules : new String[] {RULES.replace("72000", "1"), RULES.replace("400", "50")}) {
			assertThatThrownBy(() -> validator.validate(play(1, rules), submission))
					.isInstanceOf(IllegalStateException.class);
		}
	}

	private static List<TakkoRunInput> firstJump() {
		return List.of(new TakkoRunInput(0, TakkoRunAction.PRESS));
	}

	private static GamePlay play(long seed, String rules) {
		return new GamePlay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TakkoRunRules.RULE_VERSION,
				rules, "a".repeat(64), TakkoRunRules.GENERATOR_VERSION, seed, Instant.EPOCH, null, null, null);
	}
}
