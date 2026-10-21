package com.getddo.core.drawing.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventType;

import static org.assertj.core.api.Assertions.assertThat;

class DrawExecutionEligibilityTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");

	/** 최초는 SCHEDULED·OPEN·CLOSED, 재추첨은 REDRAWING에서만 새 시도를 허용한다. */
	@ParameterizedTest
	@CsvSource({"SCHEDULED, true, false", "OPEN, true, false", "CLOSED, true, false",
			"DRAW_CONFIRMED, false, false", "PUBLISHED, false, false", "CANCELED, false, false",
			"REDRAWING, false, true", "NO_ENTRANTS, false, false", "NO_ELIGIBLE_ENTRANTS, false, false"})
	@DisplayName("최초는 SCHEDULED·OPEN·CLOSED, 재추첨은 REDRAWING에서만 새 시도를 허용한다")
	void restrictsEventStatuses(EventStatus status, boolean initialAllowed, boolean replacementAllowed) {
		// given
		DrawSnapshotSource.Event event = event(status, false);
		// when / then
		assertThat(DrawExecutionEligibility.isExecutable(event, DrawType.INITIAL, NOW)).isEqualTo(initialAllowed);
		assertThat(DrawExecutionEligibility.isExecutable(event, DrawType.REPLACEMENT, NOW)).isEqualTo(replacementAllowed);
	}

	/** 두 종류 모두 마감 후 1분 경계를 지킨다. */
	@ParameterizedTest
	@CsvSource({"-1, false", "0, true", "1, true"})
	@DisplayName("두 종류 모두 마감 후 1분 경계를 지킨다")
	void preservesExecutionBoundary(long offsetNanos, boolean expected) {
		// given
		Instant now = NOW.plusNanos(offsetNanos);
		// when / then
		assertThat(DrawExecutionEligibility.isExecutable(event(EventStatus.SCHEDULED, false), DrawType.INITIAL, now))
				.isEqualTo(expected);
		assertThat(DrawExecutionEligibility.isExecutable(event(EventStatus.CLOSED, false), DrawType.INITIAL, now))
				.isEqualTo(expected);
		assertThat(DrawExecutionEligibility.isExecutable(event(EventStatus.REDRAWING, false), DrawType.REPLACEMENT, now))
				.isEqualTo(expected);
	}

	/** 허용 상태여도 삭제된 이벤트는 실행하지 않는다. */
	@Test
	@DisplayName("허용 상태여도 삭제된 이벤트는 실행하지 않는다")
	void rejectsDeletedEvents() {
		// when / then
		assertThat(DrawExecutionEligibility.isExecutable(event(EventStatus.SCHEDULED, true), DrawType.INITIAL, NOW)).isFalse();
		assertThat(DrawExecutionEligibility.isExecutable(event(EventStatus.CLOSED, true), DrawType.INITIAL, NOW)).isFalse();
		assertThat(DrawExecutionEligibility.isExecutable(event(EventStatus.REDRAWING, true), DrawType.REPLACEMENT, NOW)).isFalse();
	}

	/** 삭제 여부와 상태가 다른 이벤트를 동일한 마감 후 1분 경계에 맞춰 구성한다. */
	private static DrawSnapshotSource.Event event(EventStatus status, boolean deleted) {
		return new DrawSnapshotSource.Event(UUID.randomUUID(), EventType.NO_TICKET, false,
				NOW.minusSeconds(60), status, deleted, null, List.of());
	}
}
