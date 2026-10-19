package com.getddo.core.game.domain;

import java.util.Objects;

import lombok.Getter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 타이밍 게임 v1의 공개 점수 규칙. 실제 판정은 발급 당시 저장한 규칙을 사용한다. */
@Getter
public final class TakkoTimingRules implements GameStartRules {

	public static final String GAME_CODE = "TAKKO_TIMING";
	public static final String RULE_VERSION = "takko-timing-v1";
	public static final String GENERATOR_VERSION = "takko-timing-generator-v1";
	public static final int POINTS = 10;
	public static final int PERFECT_POINTS = 20;
	public static final int MINIMUM_REWARD_SCORE = 50;

	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private final int points;
	private final int perfectPoints;
	private final int minimumRewardScore;

	private TakkoTimingRules(int points, int perfectPoints, int minimumRewardScore) {
		if (points != POINTS || perfectPoints != PERFECT_POINTS || minimumRewardScore != MINIMUM_REWARD_SCORE) {
			throw new IllegalArgumentException("takko-timing-v1에 맞는 저장 규칙이 필요합니다.");
		}
		this.points = points;
		this.perfectPoints = perfectPoints;
		this.minimumRewardScore = minimumRewardScore;
	}

	/** 누락·소수·중복 키를 보정하지 않고 서버에 저장된 v1 규칙을 검증한다. */
	public static TakkoTimingRules fromSnapshot(String snapshot) {
		JsonNode rules = JSON.readTree(Objects.requireNonNull(snapshot, "snapshot"));
		if (rules == null || !rules.isObject()) {
			throw new IllegalArgumentException("타이밍 게임 규칙은 JSON 객체여야 합니다.");
		}
		return new TakkoTimingRules(integer(rules, "points"), integer(rules, "perfectPoints"),
				integer(rules, "minimumRewardScore"));
	}

	/** 저장 설정 오류는 사용자 입력의 INVALID와 구분하고 원문 JSON을 예외에 남기지 않는다. */
	public static TakkoTimingRules fromStoredSnapshot(String snapshot) {
		try {
			return fromSnapshot(snapshot);
		} catch (JacksonException | IllegalArgumentException invalidConfiguration) {
			IllegalStateException safeCause = new IllegalStateException(invalidConfiguration.getClass().getName());
			safeCause.setStackTrace(invalidConfiguration.getStackTrace());
			throw new IllegalStateException("저장된 타이밍 게임 규칙이 올바르지 않습니다.", safeCause);
		}
	}

	private static int integer(JsonNode rules, String name) {
		JsonNode value = rules.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IllegalArgumentException("타이밍 게임 규칙에는 정수 필드가 필요합니다.");
		}
		return value.intValue();
	}
}
