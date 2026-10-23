package com.getddo.core.notification.service;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;

/** 외부 전송 없이 각 발송 시도의 성공·일시 실패를 모의 판정한다. 기본 실패 확률은 20%다. */
@Service
public class MockNotificationSender {
	private final int failurePercent;
	private final IntSupplier draw;

	/** 설정된 확률로 매 시도를 독립 판정한다. 범위를 벗어난 설정은 기동 시 거절한다. */
	@Autowired
	public MockNotificationSender(@Value("${getddo.notification.mock.failure-percent:20}") int failurePercent) {
		this(failurePercent, () -> ThreadLocalRandom.current().nextInt(100));
	}

	/** 확률 경계를 반복 가능한 값으로 검증할 수 있도록 난수 입력만 분리한다. */
	MockNotificationSender(int failurePercent, IntSupplier draw) {
		if (failurePercent < 0 || failurePercent > 100) {
			throw new IllegalArgumentException("모의 발송 실패율은 0~100이어야 합니다.");
		}
		this.failurePercent = failurePercent;
		this.draw = Objects.requireNonNull(draw, "draw");
	}

	/** 실패도 기존 발송 서비스가 저장·재시도하도록 결과만 반환하며 알림을 직접 수정하지 않는다. */
	public MockDeliveryStatus send(NotificationDelivery delivery) {
		return draw.getAsInt() < failurePercent ? MockDeliveryStatus.FAILED : MockDeliveryStatus.SENT;
	}
}
