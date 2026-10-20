package com.getddo.core.game.domain;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredRulesSnapshotTest {

	@Test
	@DisplayName("세 게임의 null 저장 설정은 공통 파서에서 안전한 서버 설정 오류로 변환한다")
	void rejectsMissingStoredSnapshot() {
		// given
		List<Function<String, GameStartRules>> readers = List.of(TakkoTimingRules::fromStoredSnapshot,
				TakkoOrderRules::fromStoredSnapshot, TakkoRunRules::fromStoredSnapshot);
		// when / then
		for (Function<String, GameStartRules> reader : readers) {
			assertThatThrownBy(() -> reader.apply(null)).isInstanceOf(IllegalStateException.class)
					.hasCauseInstanceOf(IllegalStateException.class);
		}
	}
}
