package com.getddo.core.banner.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/** 저장된 배너의 읽기 모델. 공개 이미지 주소는 API 계층에서 구성한다. */
@Getter
public final class Banner {
	private final UUID id;
	private final UUID eventId;
	private final String imageKey;
	private final int displayOrder;
	private final Instant createdAt;
	private final Instant updatedAt;

	public Banner(UUID id, UUID eventId, String imageKey, int displayOrder, Instant createdAt, Instant updatedAt) {
		this.id = Objects.requireNonNull(id, "id");
		// 사용자 승인: 이전 URL 행은 이벤트 연결 전까지 null이며 관리자에게만 표시한다.
		this.eventId = eventId;
		this.imageKey = Objects.requireNonNull(imageKey, "imageKey");
		if (displayOrder < 1 || displayOrder > 5) throw new IllegalArgumentException("배너 순서는 1~5여야 합니다.");
		this.displayOrder = displayOrder;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
	}
}
