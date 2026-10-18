package com.getddo.core.game.domain;

import java.util.Objects;

import lombok.Getter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 color-word-v1 스냅샷에서 해당 플레이의 공개 규칙만 읽는다. */
@Getter
public final class ColorWordRules implements GameStartRules {

	public static final String GAME_CODE = "COLOR_WORD";
	public static final String RULE_VERSION = "color-word-v1";
	public static final String GENERATOR_VERSION = "color-word-generator-v1";
	public static final int MINIMUM_REWARD_SCORE = 50;
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private final int initialLives;
	private final int pointsPerCorrect;
	private final int initialTimeLimitMs;
	private final int timeLimitDecreaseMs;
	private final int minimumTimeLimitMs;

	private ColorWordRules(int initialLives, int pointsPerCorrect, int initialTimeLimitMs,
			int timeLimitDecreaseMs, int minimumTimeLimitMs) {
		if (initialLives != 3 || pointsPerCorrect != 10 || initialTimeLimitMs != 3000
				|| timeLimitDecreaseMs != 80 || minimumTimeLimitMs != 1200) {
			throw new IllegalArgumentException("color-word-v1에 맞는 저장 규칙이 필요합니다.");
		}
		this.initialLives = initialLives;
		this.pointsPerCorrect = pointsPerCorrect;
		this.initialTimeLimitMs = initialTimeLimitMs;
		this.timeLimitDecreaseMs = timeLimitDecreaseMs;
		this.minimumTimeLimitMs = minimumTimeLimitMs;
	}

	/** 누락·문자열 숫자·소수·중복 키는 기본값으로 보정하지 않고 거절한다. */
	public static ColorWordRules fromSnapshot(String snapshot) {
		JsonNode rules = JSON.readTree(Objects.requireNonNull(snapshot, "snapshot"));
		if (rules == null || !rules.isObject()) {
			throw new IllegalArgumentException("색깔 게임 규칙은 JSON 객체여야 합니다.");
		}
		return new ColorWordRules(integer(rules, "initialLives"), integer(rules, "pointsPerCorrect"),
				integer(rules, "initialTimeLimitMs"), integer(rules, "timeLimitDecreaseMs"),
				integer(rules, "minimumTimeLimitMs"));
	}

	private static int integer(JsonNode rules, String name) {
		JsonNode value = rules.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IllegalArgumentException("색깔 게임 규칙에는 정수 필드가 필요합니다.");
		}
		return value.intValue();
	}
}
