package com.getddo.core.game.domain;

import lombok.Getter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** FE 계약의 120Hz·xorshift32 재현과 활성 10분 종료 규칙. 저장값을 임의로 보정하지 않는다. */
@Getter
public final class TakkoRunRules implements GameStartRules {

	public static final String GAME_CODE = "TAKKO_RUN";
	public static final String RULE_VERSION = "takko-run-v1";
	public static final String GENERATOR_VERSION = "takko-run-generator-v1";
	public static final int TICK_RATE = 120;
	public static final double TICK_MILLIS = 1000.0 / TICK_RATE;
	public static final int MAX_TICKS = 72_000;
	public static final int DISTANCE_PER_POINT = 10;
	public static final int MINIMUM_REWARD_SCORE = 400;

	private final int minimumRewardScore;
	private final int maxTicks;

	private TakkoRunRules(int minimumRewardScore, int maxTicks) {
		if (minimumRewardScore != MINIMUM_REWARD_SCORE || maxTicks != MAX_TICKS) {
			throw new IllegalArgumentException("takko-run-v1에 맞는 저장 규칙이 필요합니다.");
		}
		this.minimumRewardScore = minimumRewardScore;
		this.maxTicks = maxTicks;
	}

	/** 공개 규칙을 시작 스냅샷에서 검증한다. 누락·중복·타입 오류는 서버 설정 오류다. */
	public static TakkoRunRules fromSnapshot(String snapshot) {
		JsonNode rules = StoredRulesSnapshot.readObject(snapshot, "타꼬런 규칙은 JSON 객체여야 합니다.");
		return new TakkoRunRules(integer(rules, "minimumRewardScore"), integer(rules, "maxTicks"));
	}

	/** 저장 설정 오류의 원문 JSON을 예외 원인에 남기지 않고 시작·결과에서 같은 오류로 변환한다. */
	public static TakkoRunRules fromStoredSnapshot(String snapshot) {
		try {
			return fromSnapshot(snapshot);
		} catch (JacksonException | IllegalArgumentException invalidConfiguration) {
			throw StoredRulesSnapshot.wrapStoredFailure("저장된 타꼬런 규칙이 올바르지 않습니다.", invalidConfiguration);
		}
	}

	private static int integer(JsonNode rules, String name) {
		return StoredRulesSnapshot.requireInt(rules, name, "타꼬런 규칙에는 정수 필드가 필요합니다.");
	}
}
