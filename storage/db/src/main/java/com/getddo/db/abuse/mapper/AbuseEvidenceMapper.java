package com.getddo.db.abuse.mapper;

import java.time.DateTimeException;
import java.time.Instant;

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
