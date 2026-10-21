package com.getddo.core.drawing.service;

import java.time.Instant;

import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.event.domain.EventStatus;

/** 이벤트 상태와 마감 시각을 확인해 최초 추첨·재추첨을 실행할 수 있는지 판단한다. */
final class DrawExecutionEligibility {
	/** 상태 없는 공통 실행 조건 검사만 제공하므로 인스턴스를 만들지 않는다. */
	private DrawExecutionEligibility() {
	}

	/** 최초 추첨은 저장 상태가 SCHEDULED로 남아 있어도 마감 후 1분부터 허용한다. 재추첨은 REDRAWING에서만 허용한다. */
	static boolean isExecutable(DrawSnapshotSource.Event event, DrawType type, Instant now) {
		boolean allowedStatus = type == DrawType.INITIAL
		? event.status() == EventStatus.SCHEDULED || event.status() == EventStatus.OPEN || event.status() == EventStatus.CLOSED
		: type == DrawType.REPLACEMENT && event.status() == EventStatus.REDRAWING;
		return !event.deleted() && allowedStatus && !now.isBefore(event.endsAt().plusSeconds(60));
	}
}
