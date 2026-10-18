package com.getddo.core.event.domain;

import java.time.Instant;
import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/** 실제 이벤트 정보와 공개 상태, 취소 시각, 요청 사용자의 목록용 참여 여부를 묶은 조회 결과. */
@Getter
@EqualsAndHashCode
public final class EventView {
	private final RegisteredEvent details;
	/** 재추첨 중에는 발표 기록 유무에 따라 PUBLISHED 또는 DRAW_CONFIRMED로 표시한다. */
	private final EventStatus publicStatus;
	private final Instant canceledAt;
	/** 현재 사용자의 정상 참여 여부. 관리자·사용자를 지정하지 않은 조회에서는 false다. */
	private final boolean myEntered;

	public EventView(RegisteredEvent details, EventStatus publicStatus, Instant canceledAt) {
		this(details, publicStatus, canceledAt, false);
	}

	private EventView(RegisteredEvent details, EventStatus publicStatus, Instant canceledAt, boolean myEntered) {
		this.details = Objects.requireNonNull(details, "details");
		this.publicStatus = Objects.requireNonNull(publicStatus, "publicStatus");
		this.canceledAt = canceledAt;
		this.myEntered = myEntered;
	}

	/** 목록에서 읽은 이벤트 정보는 유지하고 요청 사용자의 참여 여부만 연결한다. */
	public EventView withMyEntered(boolean entered) {
		return new EventView(details, publicStatus, canceledAt, entered);
	}
}
