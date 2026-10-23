package com.getddo.redis.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EntryBlockPayloadMapperTest {
	static final String PAYLOAD = """
		{"id":"00000000-0000-0000-0000-000000000001","userId":"00000000-0000-0000-0000-000000000002",
		"eventId":null,"sourceType":"ENTRY","detectionType":"REPEATED_RATE_LIMIT_EXCEEDED",
		"detectionReason":"응모 요청 한도 초과가 반복되어 일시 차단되었습니다.",
		"occurredAt":"2026-10-22T20:00:00.123456Z","detectedAt":1792699200123,
		"blockedAt":1792699200123,"blockedUntil":1792699800123,
		"evidence":{"ruleVersion":"entry-v1","triggerScope":"USER","windowStartedAt":1792699180123,
		"windowEndsAt":1792699240123,"requestLimit":20,"rejectedCount":20,"rejectionThreshold":20}}
		""";
	private final EntryBlockPayloadMapper mapper = new EntryBlockPayloadMapper();

	@Test
	void restoresOriginalTimesAndEvidence() {
		var block = mapper.read(PAYLOAD);
		assertThat(block.getEventId()).isNull();
		assertThat(block.getOccurredAt()).isEqualTo(Instant.parse("2026-10-22T20:00:00.123456Z"));
		assertThat(block.getBlockedAt()).isEqualTo(Instant.ofEpochMilli(1792699200123L));
		assertThat(block.getEvidence().getRuleVersion()).isEqualTo("entry-v1");
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"requestLimit\":\"20\"", "\"requestLimit\":20.5", "\"requestLimit\":2147483648"})
	void rejectsCoercedOrOverflowingEvidence(String replacement) {
		assertThatThrownBy(() -> mapper.read(PAYLOAD.replace("\"requestLimit\":20", replacement)))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void rejectsOtherSourceAndChangedDetectionTime() {
		assertThatThrownBy(() -> mapper.read(PAYLOAD.replace("\"ENTRY\"", "\"GAME\"")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> mapper.read(PAYLOAD.replace("\"detectedAt\":1792699200123", "\"detectedAt\":1792699200124")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
