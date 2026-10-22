package com.getddo.api.common.ratelimit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;

/** 단일 Lua 평가의 저장값을 복원한다. 최초 payload를 현재 설정·TTL로 재계산하지 않는다. */
final class RedisEntryRateLimiter {
	static final String PREFIX = "getddo:entry-block:";
	private final EntryRedisScriptExecutor executor;
	private final JsonMapper json = JsonMapper.builder().build();
	private final byte[] activeScript = EntryRedisScriptExecutor.script("entry-active");
	private final byte[] evaluateScript = EntryRedisScriptExecutor.script("entry-evaluate");

	RedisEntryRateLimiter(EntryRedisScriptExecutor executor) {
		this.executor = executor;
	}

	/** 읽기 전용 조회 실패는 호출자에서 판정 전 fail-open으로 처리한다. */
	Optional<EntryLimitDecision> active(UUID userId) {
		String result = executor.execute(activeScript, List.of(userIndex(userId)), List.of(userId.toString()));
		return result.isEmpty() ? Optional.empty() : Optional.of(decision(result));
	}

	/** 최초 후보 ID를 한 번 생성하고 정확히 한 번 평가한다. */
	EntryLimitDecision evaluate(UUID userId, UUID scope, UUID verifiedEvent, Instant received,
			RateLimitProperties.Policy policy, EntryBlockProperties settings) {
		settings.validateWindow(policy.window());
		UUID candidate = UUID.randomUUID();
		List<String> keys = List.of(userIndex(userId), PREFIX + "counters:" + userId,
				PREFIX + "journal:" + candidate, PREFIX + "journal-ids", PREFIX + "pending");
		List<String> args = List.of(userId.toString(), scope == null ? "" : scope.toString(),
				verifiedEvent == null ? "" : verifiedEvent.toString(),
				received.truncatedTo(ChronoUnit.MICROS).toString(), candidate.toString(),
				Long.toString(policy.window().toMillis()), Integer.toString(policy.limit()),
				policy.scopedLimit() == null ? "0" : policy.scopedLimit().toString(),
				Integer.toString(settings.userThreshold()), Integer.toString(settings.eventThreshold()),
				Long.toString(settings.duration().toMillis()), settings.ruleVersion());
		return decision(executor.execute(evaluateScript, keys, args));
	}

	private static String userIndex(UUID user) {
		return PREFIX + "user:" + user;
	}

	private EntryLimitDecision decision(String value) {
		try {
			JsonNode node = json.readTree(value);
			return new EntryLimitDecision(EntryLimitResult.valueOf(node.get("result").asText()),
					EntryRecordingStatus.valueOf(node.get("recordingStatus").asText()),
					node.get("retryAfterSeconds").asLong(), window(node.get("userWindow")),
					window(node.get("eventWindow")), block(node.get("block")));
		} catch (RuntimeException exception) {
			// JSON 예외 원문에는 내부 근거가 포함될 수 있으므로 연결하지 않는다.
			throw new IllegalStateException("응모 제한 결과의 형식이 올바르지 않다.");
		}
	}

	private EntryWindowSnapshot window(JsonNode node) {
		return node == null || node.isNull() ? null : new EntryWindowSnapshot(
				Instant.ofEpochMilli(node.get("startedAt").asLong()),
				Instant.ofEpochMilli(node.get("endsAt").asLong()), node.get("rejectedCount").asInt());
	}

	private EntryBlock block(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		JsonNode evidence = node.get("evidence");
		AbuseEvidence proof = new AbuseEvidence(evidence.get("ruleVersion").asText(),
				AbuseTriggerScope.valueOf(evidence.get("triggerScope").asText()),
				Instant.ofEpochMilli(evidence.get("windowStartedAt").asLong()),
				Instant.ofEpochMilli(evidence.get("windowEndsAt").asLong()),
				evidence.get("requestLimit").asInt(), evidence.get("rejectedCount").asInt(),
				evidence.get("rejectionThreshold").asInt());
		return new EntryBlock(UUID.fromString(node.get("id").asText()), UUID.fromString(node.get("userId").asText()),
				node.get("eventId").isNull() ? null : UUID.fromString(node.get("eventId").asText()),
				Instant.parse(node.get("occurredAt").asText()), Instant.ofEpochMilli(node.get("blockedAt").asLong()),
				Instant.ofEpochMilli(node.get("blockedUntil").asLong()), proof);
	}

	void destroy() {
		executor.destroy();
	}
}
