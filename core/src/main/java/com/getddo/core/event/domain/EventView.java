package com.getddo.core.event.domain;

import java.time.Instant;
import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/** 이벤트 정보·공개 상태·취소 시각과 목록용 참여 여부·당첨 예정 인원 합계를 묶은 조회 결과. */
@Getter
@EqualsAndHashCode
public final class EventView {
	private final RegisteredEvent details;
	/** 재추첨 중에는 발표 기록 유무에 따라 PUBLISHED 또는 DRAW_CONFIRMED로 표시한다. */
	private final EventStatus publicStatus;
	private final Instant canceledAt;
	/** 현재 사용자의 정상 참여 여부. 관리자·사용자를 지정하지 않은 조회에서는 false다. */
	private final boolean myEntered;
	/** 해당 이벤트 경품에 설정된 당첨 예정 인원 합계. 실제 당첨 결과와 구분한다. */
	private final long winnerCount;

	public EventView(RegisteredEvent details, EventStatus publicStatus, Instant canceledAt) {
		this(details, publicStatus, canceledAt, details == null ? 0
				: details.getPrizes().stream().mapToLong(RegisteredEvent.Prize::getWinnerCount).sum());
	}

	/** 경품 상세를 읽지 않는 사용자 목록에서도 DB에서 계산한 합계를 전달한다. */
	public EventView(RegisteredEvent details, EventStatus publicStatus, Instant canceledAt, long winnerCount) {
		this(details, publicStatus, canceledAt, false, winnerCount);
	}

	private EventView(RegisteredEvent details, EventStatus publicStatus, Instant canceledAt,
			boolean myEntered, long winnerCount) {
		this.details = Objects.requireNonNull(details, "details");
		this.publicStatus = Objects.requireNonNull(publicStatus, "publicStatus");
		if (winnerCount < 0) {
			throw new IllegalArgumentException("당첨 예정 인원 합계는 음수일 수 없습니다.");
		}
		this.canceledAt = canceledAt;
		this.myEntered = myEntered;
		this.winnerCount = winnerCount;
	}

	/** 목록에서 읽은 이벤트 정보는 유지하고 요청 사용자의 참여 여부만 연결한다. */
	public EventView withMyEntered(boolean entered) {
		return new EventView(details, publicStatus, canceledAt, entered, winnerCount);
	}
}
