package com.getddo.core.game.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GamePlayTest {

	private static final UUID USER = UUID.randomUUID();
	private static final UUID GAME = UUID.randomUUID();
	private static final Instant START = Instant.parse("2026-10-12T14:59:59Z");
	private static final Instant END = Instant.parse("2026-10-12T15:00:00Z");
	private static final String HASH = "a".repeat(64);

	@Test
	@DisplayName("같은 입력 재시도는 최초 결과와 완료 시각을 유지하고 변경 입력은 거절한다")
	void preservesFirstResult() {
		// given
		GamePlay valid = started().complete(10L, HASH, END);
		// when
		GamePlay replay = valid.complete(999L, HASH, END.plusSeconds(86400));
		// then
		assertThat(replay).isSameAs(valid);
		assertThatThrownBy(() -> valid.complete(20L, "b".repeat(64), END))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("시작 발급은 0이 아닌 unsigned 32-bit seed와 토큰 해시를 요구한다")
	void validatesStartContext() {
		// given
		Game game = new Game(GAME, "COLOR_WORD", "색깔", null, "{}", "color-word-v1", true);
		// when
		GamePlay maximum = GamePlay.start(game, USER, HASH, "color-word-generator-v1", 4294967295L);
		// then
		assertThat(maximum.getGenerationSeed()).isEqualTo(4294967295L);
		assertThat(maximum.getId()).isNull();
		assertThatThrownBy(() -> GamePlay.start(game, USER, HASH, "v1", 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GamePlay.start(game, USER, HASH, "v1", 4294967296L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GamePlay.start(game, USER, "raw-token", "v1", 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GamePlay.start(new Game(GAME, "OFF", "중지", null, "{}", "v1", false),
				USER, HASH, "v1", 1)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("검증 기준 없는 과거 플레이·음수 점수·시작 전 완료는 확정할 수 없다")
	void rejectsUnverifiableResult() {
		// given
		GamePlay legacy = new GamePlay(UUID.randomUUID(), GAME, USER, "v1", null, null, null, null,
				START, null, null, null);
		// when
		// then
		assertThatThrownBy(() -> legacy.complete(10L, HASH, END)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> started().complete(-1L, HASH, END)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> started().complete(10L, HASH, START.minusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("0점 정상 완료·무효 완료·미완료를 구분하고 완료 시각을 마이크로초로 보존한다")
	void distinguishesResultStates() {
		// given
		GamePlay unfinished = started();
		// when
		GamePlay zero = unfinished.complete(0L, HASH, END.plusNanos(123456789));
		GamePlay invalid = unfinished.complete(null, HASH, END);
		// then
		assertThat(unfinished.isCompleted()).isFalse();
		assertThat(unfinished.isValid()).isFalse();
		assertThat(zero.isCompleted()).isTrue();
		assertThat(zero.isValid()).isTrue();
		assertThat(zero.getScore()).isZero();
		assertThat(zero.getCompletedAt()).isEqualTo(END.plusNanos(123456000));
		assertThat(invalid.isCompleted()).isTrue();
		assertThat(invalid.isValid()).isFalse();
		assertThat(invalid.getScore()).isNull();
		assertThat(invalid.complete(10L, HASH, END.plusSeconds(1))).isSameAs(invalid);
	}

	@Test
	@DisplayName("복원 생성자는 검증 기준 4개 필드의 모든 부분 조합을 거절한다")
	void rejectsPartialVerificationContext() {
		// given
		for (int mask = 1; mask < 15; mask++) {
			String rules = (mask & 1) != 0 ? "{}" : null;
			String token = (mask & 2) != 0 ? HASH : null;
			String generator = (mask & 4) != 0 ? "v1" : null;
			Long seed = (mask & 8) != 0 ? 1L : null;
			// when / then
			assertThatThrownBy(() -> new GamePlay(UUID.randomUUID(), GAME, USER, "v1",
					rules, token, generator, seed, START, null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	@DisplayName("검증 기준이 모두 없는 과거 행은 시간 역전과 기존 결과 조합을 그대로 복원한다")
	void preservesLegacyResultFields() {
		// given
		Instant legacyCompletion = START.minusSeconds(1);
		// when
		GamePlay legacy = new GamePlay(UUID.randomUUID(), GAME, USER, "v1", null, null, null, null,
				START, -1L, null, legacyCompletion);
		// then
		assertThat(legacy.hasVerificationContext()).isFalse();
		assertThat(legacy.getScore()).isEqualTo(-1L);
		assertThat(legacy.getResultHash()).isNull();
		assertThat(legacy.getCompletedAt()).isEqualTo(legacyCompletion);
	}

	@Test
	@DisplayName("검증 기준이 있는 복원 객체는 잘못된 결과 조합과 시간·해시 형식 경계를 거절한다")
	void rejectsInvalidRestoredResults() {
		// given
		List<Runnable> invalid = List.of(
				() -> restore(0L, null, null, START),
				() -> restore(null, HASH, null, START),
				() -> restore(0L, HASH, null, START),
				() -> restore(null, null, END, START),
				() -> restore(-1L, HASH, END, START),
				() -> restore(0L, "a".repeat(63), END, START),
				() -> restore(0L, "a".repeat(65), END, START),
				() -> restore(0L, "g".repeat(64), END, START),
				() -> restore(0L, "A".repeat(64), END, START),
				() -> restore(0L, HASH, START.minusSeconds(1), START),
				() -> restore(0L, HASH, END, null));
		// when / then
		for (Runnable input : invalid) {
			assertThatThrownBy(input::run).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	@DisplayName("검증 기준이 있는 미완료·0점 완료·무효 완료를 복원하며 시작과 같은 완료 시각도 허용한다")
	void restoresValidResultStates() {
		// given / when
		GamePlay unfinished = restore(null, null, null, START);
		GamePlay zero = restore(0L, HASH, START, START);
		GamePlay invalid = restore(null, HASH, END, START);
		// then
		assertThat(unfinished.isCompleted()).isFalse();
		assertThat(zero.isValid()).isTrue();
		assertThat(zero.getScore()).isZero();
		assertThat(zero.getCompletedAt()).isEqualTo(START);
		assertThat(invalid.isCompleted()).isTrue();
		assertThat(invalid.isValid()).isFalse();
		assertThat(invalid.getResultHash()).isEqualTo(HASH);
	}

	@ParameterizedTest
	@CsvSource({"1,true", "4294967295,true", "0,false", "-1,false", "4294967296,false"})
	@DisplayName("게임별 공통 시작 정보 검사는 seed의 양쪽 경계를 동일하게 적용한다")
	void checksReplaySeedBoundary(long seed, boolean supported) {
		// given
		GamePlay play = new GamePlay(UUID.randomUUID(), GAME, USER, "takko-order-v1", "{}", HASH,
				"takko-order-generator-v1", seed, START, null, null, null);
		// when / then
		assertThat(play.hasVerificationContext("takko-order-v1", "takko-order-generator-v1")).isEqualTo(supported);
	}

	@Test
	@DisplayName("게임별 공통 시작 정보 검사는 다른 규칙·생성 버전과 검증 정보 없는 과거 행을 거절한다")
	void rejectsMismatchedReplayVersionAndLegacy() {
		// given
		GamePlay current = started();
		GamePlay legacy = new GamePlay(UUID.randomUUID(), GAME, USER, current.getRuleVersion(),
				null, null, null, null, START, null, null, null);
		// when / then
		assertThat(current.hasVerificationContext("unsupported-rule", current.getGeneratorVersion())).isFalse();
		assertThat(current.hasVerificationContext(current.getRuleVersion(), "unsupported-generator")).isFalse();
		assertThat(legacy.hasVerificationContext(current.getRuleVersion(), current.getGeneratorVersion())).isFalse();
	}

	private GamePlay restore(Long score, String hash, Instant completedAt, Instant startedAt) {
		return new GamePlay(UUID.randomUUID(), GAME, USER, "color-word-v1", "{}", HASH,
				"color-word-generator-v1", 123456789L, startedAt, score, hash, completedAt);
	}

	private GamePlay started() {
		return new GamePlay(UUID.randomUUID(), GAME, USER, "color-word-v1", "{}", HASH,
				"color-word-generator-v1", 123456789L, START, null, null, null);
	}
}
