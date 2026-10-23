package com.getddo.redis.ratelimit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.common.ratelimit.EntryBlock;

/** 최초 Redis 저널을 복원한다. 손상된 값이나 다른 종류의 기록을 차단 이력으로 합성하지 않는다. */
final class EntryBlockPayloadMapper {
	private final JsonMapper json = JsonMapper.builder().build();

	EntryBlock read(String payload) {
		return read(json.readTree(payload));
	}

	EntryBlock read(JsonNode node) {
		if (node == null || !node.isObject()
				|| !EntryBlock.SOURCE_TYPE.equals(text(node, "sourceType"))
				|| !EntryBlock.DETECTION_TYPE.equals(text(node, "detectionType"))
				|| !EntryBlock.DETECTION_REASON.equals(text(node, "detectionReason"))) {
			throw invalid();
		}
		JsonNode proof = node.get("evidence");
		AbuseEvidence evidence = new AbuseEvidence(text(proof, "ruleVersion"),
				AbuseTriggerScope.valueOf(text(proof, "triggerScope")),
				Instant.ofEpochMilli(number(proof, "windowStartedAt")),
				Instant.ofEpochMilli(number(proof, "windowEndsAt")),
				Math.toIntExact(number(proof, "requestLimit")), Math.toIntExact(number(proof, "rejectedCount")),
				Math.toIntExact(number(proof, "rejectionThreshold")));
		Instant occurredAt = Instant.parse(text(node, "occurredAt"));
		if (!occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS))
				|| number(node, "detectedAt") != number(node, "blockedAt") || node.get("eventId") == null) {
			throw invalid();
		}
		return new EntryBlock(uuid(text(node, "id")), uuid(text(node, "userId")),
				node.get("eventId").isNull() ? null : uuid(text(node, "eventId")), occurredAt,
				Instant.ofEpochMilli(number(node, "blockedAt")),
				Instant.ofEpochMilli(number(node, "blockedUntil")), evidence);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || !value.isString() || value.asText().isBlank()) {
			throw invalid();
		}
		return value.asText();
	}

	private static long number(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			throw invalid();
		}
		return value.asLong();
	}

	private static UUID uuid(String value) {
		UUID id = UUID.fromString(value);
		if (!id.toString().equals(value)) {
			throw invalid();
		}
		return id;
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("차단 저널 형식이 올바르지 않다.");
	}
}
