package com.getddo.db.abuse.mapper;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbuseEvidenceMapperTest {

	private static final String VALID = """
			{"ruleVersion":"stored-v2","triggerScope":"USER",
			 "windowStartedAt":"2026-10-21T06:00:00.123456Z","windowEndsAt":"2026-10-21T06:01:00.123456Z",
			 "requestLimit":37,"rejectedCount":39,"rejectionThreshold":37,
			 "token":"private-token","requestBody":{"email":"private@example.test"}}
			""";

	@ParameterizedTest
	@EnumSource(AbuseTriggerScope.class)
	@DisplayName("USER·EVENT의 저장값과 정밀도를 그대로 복원하고 허용되지 않은 JSON 필드는 옮기지 않는다")
	void restoresOriginalEvidence(AbuseTriggerScope scope) {
		// given
		String stored = VALID.replace("\"triggerScope\":\"USER\"", "\"triggerScope\":\"" + scope.name() + "\"");

		// when
		AbuseEvidence result = new AbuseEvidenceMapper().toDomain(stored);

		// then
		assertThat(result).usingRecursiveComparison().isEqualTo(new AbuseEvidence("stored-v2", scope,
				Instant.parse("2026-10-21T06:00:00.123456Z"), Instant.parse("2026-10-21T06:01:00.123456Z"), 37, 39, 37));
	}

	@ParameterizedTest
	@NullSource
	@MethodSource("invalidEvidence")
	@DisplayName("누락·잘못된 타입·오버플로·잘못된 근거는 원문을 포함하지 않는 내부 오류로 거절한다")
	void rejectsInvalidStoredEvidenceWithoutLeakingInput(String value) {
		// given / when / then
		assertThatThrownBy(() -> new AbuseEvidenceMapper().toDomain(value))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Stored abuse evidence is invalid").hasNoCause();
	}

	private static Stream<String> invalidEvidence() {
		return Stream.of("null", "[]", "{}", "invalid-private-token", "",
				VALID.replace("\"stored-v2\"", "null"), VALID.replace("\"stored-v2\"", "17"),
				VALID.replace("stored-v2", " "), VALID.replace("\"USER\"", "\"UNKNOWN\""),
				VALID.replace("2026-10-21T06:00:00.123456Z", "not-a-time"),
				VALID.replace("2026-10-21T06:01:00.123456Z", "2026-10-21T06:00:00.123456Z"),
				VALID.replace("\"requestLimit\":37", "\"requestLimit\":0"),
				VALID.replace("\"requestLimit\":37", "\"requestLimit\":\"37\""),
				VALID.replace("\"requestLimit\":37", "\"requestLimit\":37.0"),
				VALID.replace("\"requestLimit\":37", "\"requestLimit\":2147483648"),
				VALID.replace("\"rejectedCount\":39", "\"rejectedCount\":36"),
				VALID.replace("\"rejectedCount\":39", "\"rejectedCount\":null"),
				VALID.replace("\"rejectedCount\":39", "\"rejectedCount\":2147483648"),
				VALID.replace("\"rejectionThreshold\":37", "\"rejectionThreshold\":0"),
				VALID.replace("\"rejectionThreshold\":37", "\"rejectionThreshold\":true"));
	}
}
