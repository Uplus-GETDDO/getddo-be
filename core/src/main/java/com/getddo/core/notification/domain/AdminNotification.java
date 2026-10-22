package com.getddo.core.notification.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

import lombok.Getter;

/** 관리자 알림 조회의 불변 저장소 결과다. HTTP DTO와 분리한다. */
@Getter
public final class AdminNotification {
	private final UUID id;
	private final UUID jobId;
	private final UUID userId;
	/** 조회 시점의 사용자 이름. 업무 기록에 이름을 복제해 저장하지 않는다. */
	private final String userName;
	private final UUID eventId;
	private final String title;
	private final String body;
	private final String linkUrl;
	private final Instant createdAt;
	private final boolean isRead;
	private final MockDeliveryStatus mockDeliveryStatus;
	private final Instant mockSentAt;
	private final int deliveryAttemptCount;
	private final int deliveryAttemptBase;
	private final Instant nextDeliveryAttemptAt;
	private final String lastDeliveryError;

	/**
	 * 저장소 결과의 필수 값과 복구 기준 차수를 검증한다.
	 * @throws NullPointerException 필수 조회 필드가 null인 경우
	 * @throws IllegalArgumentException 0 <= deliveryAttemptBase <= deliveryAttemptCount를 만족하지 않는 경우
	 */
	public AdminNotification(UUID id,
			UUID jobId,
			UUID userId,
			String userName,
			UUID eventId,
			String title,
			String body,
			String linkUrl,
			Instant createdAt,
			boolean isRead,
			MockDeliveryStatus mockDeliveryStatus,
			Instant mockSentAt,
			int deliveryAttemptCount,
			int deliveryAttemptBase,
			Instant nextDeliveryAttemptAt,
			String lastDeliveryError) {
		this.id = Objects.requireNonNull(id, "id");
		this.jobId = Objects.requireNonNull(jobId, "jobId");
		this.userId = Objects.requireNonNull(userId, "userId");
		this.userName = Objects.requireNonNull(userName, "userName");
		this.eventId = eventId;
		this.title = Objects.requireNonNull(title, "title");
		this.body = Objects.requireNonNull(body, "body");
		this.linkUrl = linkUrl;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.isRead = isRead;
		this.mockDeliveryStatus = Objects.requireNonNull(mockDeliveryStatus, "mockDeliveryStatus");
		this.mockSentAt = mockSentAt;
		if (deliveryAttemptCount < 0 || deliveryAttemptBase < 0 || deliveryAttemptBase > deliveryAttemptCount) {
			throw new IllegalArgumentException("0 <= deliveryAttemptBase <= deliveryAttemptCount is required");
		}
		this.deliveryAttemptCount = deliveryAttemptCount;
		this.deliveryAttemptBase = deliveryAttemptBase;
		this.nextDeliveryAttemptAt = nextDeliveryAttemptAt;
		this.lastDeliveryError = lastDeliveryError;
	}

	/** 이미 생성된 알림의 최종 모의 발송 실패만 복구한다. 읽음 상태와는 별개다. */
	public boolean isRetryable() {
		return mockDeliveryStatus == MockDeliveryStatus.FAILED && nextDeliveryAttemptAt == null
				&& deliveryAttemptCount < Integer.MAX_VALUE;
	}
}
