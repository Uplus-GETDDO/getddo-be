package com.getddo.api.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class EntryLimitDecisionTest {
	@Test
	@DisplayName("판정·기록 상태가 없으면 생성 시 거절한다")
	void rejectsMissingStates() {
		// given / when / then
		assertThatThrownBy(() -> new EntryLimitDecision(null, EntryRecordingStatus.NOT_APPLICABLE, 0, null, null, null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new EntryLimitDecision(EntryLimitResult.ALLOWED, null, 0, null, null, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("허용 판정의 대기 시간은 정확히 0이어야 한다")
	void allowedHasNoWait() {
		// given / when / then
		for (long invalid : new long[] {-1, 1}) {
			assertThatThrownBy(() -> new EntryLimitDecision(EntryLimitResult.ALLOWED,
					EntryRecordingStatus.NOT_APPLICABLE, invalid, null, null, null))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(new EntryLimitDecision(EntryLimitResult.ALLOWED, EntryRecordingStatus.NOT_APPLICABLE,
				0, null, null, null).getRetryAfterSeconds()).isZero();
	}

	@ParameterizedTest
	@EnumSource(value = EntryLimitResult.class, names = {"RATE_LIMITED", "BLOCKED"})
	@DisplayName("거절·차단 판정은 최소 1초의 대기 시간을 보장한다")
	void deniedRequiresWait(EntryLimitResult result) {
		// given / when / then
		for (long invalid : new long[] {-1, 0}) {
			assertThatThrownBy(() -> new EntryLimitDecision(result, EntryRecordingStatus.RECORDED,
					invalid, null, null, null)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(new EntryLimitDecision(result, EntryRecordingStatus.RECORDED,
				1, null, null, null).getRetryAfterSeconds()).isEqualTo(1);
	}
}
