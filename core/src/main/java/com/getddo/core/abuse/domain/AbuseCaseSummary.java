package com.getddo.core.abuse.domain;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;

/** 차단 이력에 현재 사용자 닉네임·이벤트 제목을 함께 읽은 불변 조회 결과다. */
@Getter
public final class AbuseCaseSummary {

	private final UUID id;
	private final UUID userId;
	private final String userNickname;
	private final UUID eventId;
	private final String eventTitle;
	private final String sourceType;
	private final String detectionType;
	private final String detectionReason;
	private final Instant blockedAt;
	private final Instant blockedUntil;

	/** 이벤트를 확인하지 못한 차단은 eventId·eventTitle이 모두 null일 수 있다. */
	public AbuseCaseSummary(UUID id, UUID userId, String userNickname, UUID eventId, String eventTitle,
			String sourceType, String detectionType, String detectionReason, Instant blockedAt, Instant blockedUntil) {
		this.id = id;
		this.userId = userId;
		this.userNickname = userNickname;
		this.eventId = eventId;
		this.eventTitle = eventTitle;
		this.sourceType = sourceType;
		this.detectionType = detectionType;
		this.detectionReason = detectionReason;
		this.blockedAt = blockedAt;
		this.blockedUntil = blockedUntil;
	}
}
