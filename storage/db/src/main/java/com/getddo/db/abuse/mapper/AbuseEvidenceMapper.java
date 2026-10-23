package com.getddo.db.abuse.mapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;

/** 저장 JSON 전체를 노출하지 않고 확정된 일곱 필드만 타입을 확인해 복원한다. */
@Component
public class AbuseEvidenceMapper {

	private final JsonMapper json = JsonMapper.builder().build();

	/** 손상된 근거는 내부 오류이며 빈 객체·현재 설정·다른 범위의 값으로 대체하지 않는다. */
	public AbuseEvidence toDomain(String value) {
		try {
			JsonNode node = json.readTree(value);
			if (node == null || !node.isObject()) {
				throw new IllegalArgumentException("Evidence must be an object");
			}
			return new AbuseEvidence(text(node, "ruleVersion"),
					AbuseTriggerScope.valueOf(text(node, "triggerScope")),
					Instant.parse(text(node, "windowStartedAt")), Instant.parse(text(node, "windowEndsAt")),
					integer(node, "requestLimit"), integer(node, "rejectedCount"), integer(node, "rejectionThreshold"));
		} catch (JacksonException | IllegalArgumentException | DateTimeException invalidEvidence) {
			// 파서 예외의 입력 스니펫이 공통 오류 로그에 원문 근거·토큰을 남기지 않게 원인을 연결하지 않는다.
			throw new IllegalStateException("Stored abuse evidence is invalid");
		}
	}

	/** 허용된 일곱 필드만 저장하고 UTC instant를 문자열로 명시해 JVM 시간대에 의존하지 않는다. */
	public String toJson(AbuseEvidence evidence) {
		return json.writeValueAsString(Map.of(
				"ruleVersion", evidence.getRuleVersion(), "triggerScope", evidence.getTriggerScope().name(),
				"windowStartedAt", evidence.getWindowStartedAt().toString(),
				"windowEndsAt", evidence.getWindowEndsAt().toString(), "requestLimit", evidence.getRequestLimit(),
				"rejectedCount", evidence.getRejectedCount(), "rejectionThreshold", evidence.getRejectionThreshold()));
	}

	/** JSON 필드 순서는 무시하되 추가·누락 필드나 값이 다른 기존 근거는 중복 성공으로 인정하지 않는다. */
	public boolean sameContent(String stored, String expected) {
		try {
			return stored != null && json.readTree(expected).equals(json.readTree(stored));
		} catch (RuntimeException invalid) {
			return false;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString()) {
			throw new IllegalArgumentException("Invalid evidence field");
		}
		return value.stringValue();
	}

	private static int integer(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IllegalArgumentException("Invalid evidence field");
		}
		return value.intValue();
	}
}
