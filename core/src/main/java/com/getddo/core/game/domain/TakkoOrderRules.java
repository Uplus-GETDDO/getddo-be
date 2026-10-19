package com.getddo.core.game.domain;

import java.util.Objects;

import lombok.Getter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 주문 게임 v1의 공개 목숨·길이 규칙. 실제 판정은 발급 당시 저장한 규칙을 사용한다. */
@Getter
public final class TakkoOrderRules implements GameStartRules {

	public static final String GAME_CODE = "TAKKO_ORDER";
	public static final String RULE_VERSION = "takko-order-v1";
	public static final String GENERATOR_VERSION = "takko-order-generator-v1";
	public static final int INITIAL_LIVES = 3;
	public static final int START_LENGTH = 3;
	public static final int MAX_LENGTH = 8;
	public static final int MINIMUM_REWARD_SCORE = 18;

	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private final int initialLives;
	private final int startLength;
	private final int maxLength;

	private TakkoOrderRules(int initialLives, int startLength, int maxLength) {
		if (initialLives != INITIAL_LIVES || startLength != START_LENGTH || maxLength != MAX_LENGTH) {
			throw new IllegalArgumentException("takko-order-v1에 맞는 저장 규칙이 필요합니다.");
		}
		this.initialLives = initialLives;
		this.startLength = startLength;
		this.maxLength = maxLength;
	}

	/** 누락·소수·중복 키를 보정하지 않고 서버에 저장된 v1 규칙을 검증한다. */
	public static TakkoOrderRules fromSnapshot(String snapshot) {
		JsonNode rules = JSON.readTree(Objects.requireNonNull(snapshot, "snapshot"));
		if (rules == null || !rules.isObject()) {
			throw new IllegalArgumentException("주문 게임 규칙은 JSON 객체여야 합니다.");
		}
		return new TakkoOrderRules(integer(rules, "initialLives"), integer(rules, "startLength"),
				integer(rules, "maxLength"));
	}

	/** 저장 설정 오류는 사용자 입력의 INVALID와 구분하고 원문 JSON을 예외에 남기지 않는다. */
	public static TakkoOrderRules fromStoredSnapshot(String snapshot) {
		try {
			return fromSnapshot(snapshot);
		} catch (JacksonException | IllegalArgumentException invalidConfiguration) {
			IllegalStateException safeCause = new IllegalStateException(invalidConfiguration.getClass().getName());
			safeCause.setStackTrace(invalidConfiguration.getStackTrace());
			throw new IllegalStateException("저장된 주문 게임 규칙이 올바르지 않습니다.", safeCause);
		}
	}

	private static int integer(JsonNode rules, String name) {
		JsonNode value = rules.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IllegalArgumentException("주문 게임 규칙에는 정수 필드가 필요합니다.");
		}
		return value.intValue();
	}
}
