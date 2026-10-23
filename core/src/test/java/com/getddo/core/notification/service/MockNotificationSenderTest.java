package com.getddo.core.notification.service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockNotificationSenderTest {
	private static final NotificationDelivery DELIVERY = new NotificationDelivery(
			UUID.randomUUID(), UUID.randomUUID(), "제목", "내용", null, 1);

	/** 100개의 균등한 난수 후보 중 20개만 실패 영역에 포함한다. */
	@ParameterizedTest
	@CsvSource({"0,FAILED", "19,FAILED", "20,SENT", "99,SENT"})
	@DisplayName("기본 20%의 실패 영역은 0~19이고 20부터 성공이다")
	void appliesFailureBoundary(int draw, MockDeliveryStatus expected) {
		// given
		MockNotificationSender sender = new MockNotificationSender(20, () -> draw);
		// when / then
		assertThat(sender.send(DELIVERY)).isEqualTo(expected);
	}

	/** 임의 표본의 비율을 검사하지 않고 가능한 난수 후보를 모두 대조한다. */
	@Test
	@DisplayName("기본 설정은 전체 난수 후보 100개 중 20개에서 실패한다")
	void failsForTwentyOfOneHundredPossibleDraws() {
		// given
		AtomicInteger draw = new AtomicInteger();
		MockNotificationSender sender = new MockNotificationSender(20, draw::getAndIncrement);
		// when
		long failures = IntStream.range(0, 100).mapToObj(index -> sender.send(DELIVERY))
				.filter(status -> status == MockDeliveryStatus.FAILED).count();
		// then
		assertThat(failures).isEqualTo(20);
	}

	/** 같은 수신자·알림을 고정 실패 대상으로 만들지 않고 시도마다 새 값을 사용한다. */
	@Test
	@DisplayName("동일 알림도 시도마다 새로 판정하여 실패 뒤 성공할 수 있다")
	void drawsAgainForEachAttempt() {
		// given
		int[] values = {0, 99, 0};
		AtomicInteger index = new AtomicInteger();
		MockNotificationSender sender = new MockNotificationSender(20, () -> values[index.getAndIncrement()]);
		// when / then
		assertThat(sender.send(DELIVERY)).isEqualTo(MockDeliveryStatus.FAILED);
		assertThat(sender.send(DELIVERY)).isEqualTo(MockDeliveryStatus.SENT);
		assertThat(sender.send(DELIVERY)).isEqualTo(MockDeliveryStatus.FAILED);
	}

	/** 통합 테스트에서 성공·실패 경로를 결정적으로 만들 수 있다. */
	@ParameterizedTest
	@CsvSource({"0,0,SENT", "100,99,FAILED"})
	@DisplayName("실패율 0%와 100%는 각각 항상 성공·실패한다")
	void supportsDeterministicSettings(int percent, int draw, MockDeliveryStatus expected) {
		// given
		MockNotificationSender sender = new MockNotificationSender(percent, () -> draw);
		// when / then
		assertThat(sender.send(DELIVERY)).isEqualTo(expected);
	}

	/** 잘못된 설정이 의도하지 않은 전체 성공·실패로 묵인되지 않게 한다. */
	@ParameterizedTest
	@ValueSource(ints = {-1, 101})
	@DisplayName("범위를 벗어난 실패율은 설정 오류로 거절한다")
	void rejectsInvalidPercentage(int percent) {
		// given / when / then
		assertThatThrownBy(() -> new MockNotificationSender(percent))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
