package com.getddo.core.notification.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDeliveryResultTest {
	private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

	/** 성공 시각과 실패 재시도 정보가 서로 섞이지 않는지 확인한다. */
	@Test
	@DisplayName("성공은 발송 시각, 실패는 다음 시각과 안전한 오류 코드만 가진다")
	void separatesSuccessfulAndFailedResults() {
		NotificationDelivery delivery = delivery(7, 6);
		NotificationDeliveryResult sent = NotificationDeliveryResult.sent(delivery, NOW);
		assertThat(sent.getStatus()).isEqualTo(MockDeliveryStatus.SENT);
		assertThat(sent.getSentAt()).isEqualTo(NOW);
		assertThat(sent.getNextAttemptAt()).isNull();
		assertThat(sent.getErrorCode()).isNull();
		NotificationDeliveryResult failed = NotificationDeliveryResult.failed(delivery, NOW.plusSeconds(10), "NOTIFICATION-005");
		assertThat(failed.getDelivery()).isSameAs(delivery);
		assertThat(failed.getStatus()).isEqualTo(MockDeliveryStatus.FAILED);
		assertThat(failed.getSentAt()).isNull();
		assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(failed.getErrorCode()).isEqualTo("NOTIFICATION-005");
		assertThat(NotificationDeliveryResult.failed(delivery, null, "NOTIFICATION-006").getNextAttemptAt()).isNull();
	}

	/** 선점 전·복구 구간 초기 상태와 필수 값 누락을 저장 결과로 만들지 못하게 한다. */
	@Test
	@DisplayName("선점되지 않은 입력과 성공 시각·오류 코드 누락을 거절한다")
	void rejectsInvalidResults() {
		assertThatThrownBy(() -> NotificationDeliveryResult.sent(delivery(0, 0), NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NotificationDeliveryResult.failed(delivery(6, 6), null, "NOTIFICATION-005"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NotificationDeliveryResult.sent(delivery(1, 0), null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> NotificationDeliveryResult.failed(delivery(1, 0), null, " "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NotificationDeliveryResult.sent(null, NOW)).isInstanceOf(NullPointerException.class);
	}

	/** 수동 복구 전후의 선점 차수를 지정한 발송 입력을 만든다. */
	private NotificationDelivery delivery(int count, int base) {
		return new NotificationDelivery(UUID.randomUUID(), UUID.randomUUID(), "제목", "본문", null, count, base);
	}
}
