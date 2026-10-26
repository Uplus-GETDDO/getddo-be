package com.getddo.core.notification.domain;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/** 발송을 다시 실행하지 않고 저장·재저장할 수 있는 한 시도의 확정 결과다. */
@Getter
public final class NotificationDeliveryResult {
	private final NotificationDelivery delivery;
	private final MockDeliveryStatus status;
	private final Instant sentAt;
	private final Instant nextAttemptAt;
	private final String errorCode;

	/** 성공·실패 팩터리만 상태에 맞는 결과를 만들며 선점 전 입력은 거절한다. */
	private NotificationDeliveryResult(NotificationDelivery delivery, MockDeliveryStatus status,
			Instant sentAt, Instant nextAttemptAt, String errorCode) {
		this.delivery = Objects.requireNonNull(delivery, "delivery");
		if (delivery.getCycleAttemptCount() < 1) {
			throw new IllegalArgumentException("발송 결과에는 선점된 시도 차수가 필요합니다.");
		}
		this.status = status;
		this.sentAt = sentAt;
		this.nextAttemptAt = nextAttemptAt;
		this.errorCode = errorCode;
	}

	/** 성공한 시각을 보관하며 이전 오류와 재시도 시각은 비운다. */
	public static NotificationDeliveryResult sent(NotificationDelivery delivery, Instant sentAt) {
		return new NotificationDeliveryResult(delivery, MockDeliveryStatus.SENT,
				Objects.requireNonNull(sentAt, "sentAt"), null, null);
	}

	/** 안전한 오류 코드와 다음 시각을 보관한다. 다음 시각 null은 최종 실패다. */
	public static NotificationDeliveryResult failed(NotificationDelivery delivery, Instant nextAttemptAt, String errorCode) {
		if (errorCode == null || errorCode.isBlank()) {
			throw new IllegalArgumentException("발송 실패에는 오류 코드가 필요합니다.");
		}
		return new NotificationDeliveryResult(delivery, MockDeliveryStatus.FAILED, null, nextAttemptAt, errorCode);
	}
}
