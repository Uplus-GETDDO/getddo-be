package com.getddo.core.game.domain;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 게임별 저장 규칙의 엄격한 JSON 해석과 원문을 숨긴 설정 오류 변환을 한곳에서 유지한다. */
final class StoredRulesSnapshot {

	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private StoredRulesSnapshot() {
	}

	/** null·배열·중복 키·후행 토큰을 허용하지 않고 저장 규칙 객체를 읽는다. */
	static JsonNode readObject(String snapshot, String message) {
		if (snapshot == null) throw new IllegalArgumentException(message);
		JsonNode rules = JSON.readTree(snapshot);
		if (rules == null || !rules.isObject()) throw new IllegalArgumentException(message);
		return rules;
	}

	/** 필수 정수는 타입과 int 범위를 모두 검사하고 보정하지 않는다. */
	static int requireInt(JsonNode rules, String name, String message) {
		JsonNode value = rules.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IllegalArgumentException(message);
		}
		return value.intValue();
	}

	/** 원인 타입·스택만 복사한다. Jackson 원인의 메시지·원문 JSON·중첩 원인은 전달하지 않는다. */
	static IllegalStateException wrapStoredFailure(String message, RuntimeException failure) {
		IllegalStateException safeCause = new IllegalStateException(failure.getClass().getName());
		safeCause.setStackTrace(failure.getStackTrace());
		return new IllegalStateException(message, safeCause);
	}
}
