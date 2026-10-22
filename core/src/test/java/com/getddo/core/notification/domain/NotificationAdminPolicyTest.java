package com.getddo.core.notification.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.getddo.core.event.domain.EventStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationAdminPolicyTest {
	private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");

	/** 시작 안내 생성 복구는 미삭제·시작 전·진행 예정일 때만 허용한다 */
	@ParameterizedTest
	@EnumSource(EventStatus.class)
	@DisplayName("시작 안내 생성 복구는 미삭제·시작 전·진행 예정일 때만 허용한다")
	void requiresScheduledEvent(EventStatus status) {
		// given / when / then
		assertThat(job(status, NOW.plusSeconds(1), false, null).isRetryable(NOW))
				.isEqualTo(status == EventStatus.SCHEDULED);
	}

	/** 시작 경계·삭제·중지된 시작 작업은 복구하지 않는다 */
	@Test
	@DisplayName("시작 경계·삭제·중지된 시작 작업은 복구하지 않는다")
	void rejectsObsoleteAndStoppedJobs() {
		// given / when / then
		assertThat(job(EventStatus.SCHEDULED, NOW, false, null).isRetryable(NOW)).isFalse();
		assertThat(job(EventStatus.SCHEDULED, NOW.plusSeconds(1), true, null).isRetryable(NOW)).isFalse();
		assertThat(job(EventStatus.SCHEDULED, NOW.plusSeconds(1), false, "event-change:test").isRetryable(NOW)).isFalse();
	}

	/** 수동 복구의 구간 횟수와 누적 선점 차수를 구분한다 */
	@Test
	@DisplayName("수동 복구의 구간 횟수와 누적 선점 차수를 구분한다")
	void preservesFenceAndRestartsRetryCycle() {
		// given / when
		NotificationJob job = new NotificationJob(UUID.randomUUID(), 5, 4);
		NotificationDelivery delivery = new NotificationDelivery(UUID.randomUUID(), UUID.randomUUID(), "제목", "내용", null, 8, 4);
		// then
		assertThat(job.getAttemptCount()).isEqualTo(5);
		assertThat(job.getCycleAttemptCount()).isEqualTo(1);
		assertThat(delivery.getCycleAttemptCount()).isEqualTo(4);
		assertThatThrownBy(() -> new NotificationJob(UUID.randomUUID(), 1, 2)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NotificationDelivery(UUID.randomUUID(), UUID.randomUUID(), "제목", "내용", null, 1, -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/** 잘못된 페이지·크기·기간은 조회 전에 거절한다 */
	@Test
	@DisplayName("잘못된 페이지·크기·기간은 조회 전에 거절한다")
	void validatesQueries() {
		// given / when / then
		assertThatThrownBy(() -> new NotificationJobQuery(0, 20, null, null, null, null, null)).hasMessageContaining("조회 조건");
		assertThatThrownBy(() -> new NotificationJobQuery(1, 101, null, null, null, null, null)).hasMessageContaining("조회 조건");
		assertThatThrownBy(() -> new NotificationDeliveryQuery(1, 20, null, null, null, null, NOW, NOW)).hasMessageContaining("조회 조건");
	}

	/** 두 조회 모델이 잘못된 차수를 조회 결과로 전달하지 못하도록 검증한다. */
	@Test
	@DisplayName("관리자 조회 모델은 음수·누적 차수 초과 기준을 거절한다")
	void rejectsInvalidAdminCounts() {
		// given / when / then
		for (int[] counts : new int[][] {{-1, 0}, {1, -1}, {1, 2}}) {
			assertThatThrownBy(() -> adminJob(UUID.randomUUID(), NotificationType.RESULT_PUBLISHED,
					NotificationJobStatus.FAILED, counts[0], counts[1])).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> adminDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
					MockDeliveryStatus.FAILED, counts[0], counts[1])).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(adminJob(UUID.randomUUID(), NotificationType.RESULT_PUBLISHED, NotificationJobStatus.FAILED, 4, 4)
				.getAttemptBase()).isEqualTo(4);
		assertThat(adminDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), MockDeliveryStatus.FAILED, 0, 0)
				.getDeliveryAttemptBase()).isZero();
	}

	/** 선택 값은 null로 허용하되 식별자·상태는 반드시 있어야 한다. */
	@Test
	@DisplayName("관리자 조회 모델은 필수 식별자·상태의 null을 거절한다")
	void rejectsMissingAdminFields() {
		// given
		UUID id = UUID.randomUUID();
		// when / then
		assertThatThrownBy(() -> adminJob(null, NotificationType.RESULT_PUBLISHED, NotificationJobStatus.FAILED, 0, 0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adminJob(id, null, NotificationJobStatus.FAILED, 0, 0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adminJob(id, NotificationType.RESULT_PUBLISHED, null, 0, 0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adminDelivery(null, id, id, MockDeliveryStatus.FAILED, 0, 0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adminDelivery(id, null, id, MockDeliveryStatus.FAILED, 0, 0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adminDelivery(id, id, null, MockDeliveryStatus.FAILED, 0, 0)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adminDelivery(id, id, id, null, 0, 0)).isInstanceOf(NullPointerException.class);
	}

	/** 필수 값・차수 검증용 작업 조회 결과를 만든다. */
	private AdminNotificationJob adminJob(UUID id, NotificationType type, NotificationJobStatus status, int count, int base) {
		return new AdminNotificationJob(id, null, null, type, status, count, base, NOW, null, NOW, null,
				null, null, null, null, null, null, null, false);
	}

	/** 필수 값・차수 검증용 발송 조회 결과를 만든다. */
	private AdminNotification adminDelivery(UUID id, UUID jobId, UUID userId, MockDeliveryStatus status, int count, int base) {
		return new AdminNotification(id, jobId, userId, "수신자", null, "제목", "내용", null, NOW, false,
				status, null, count, base, null, null);
	}

	private AdminNotificationJob job(EventStatus eventStatus, Instant starts, boolean deleted, String stopped) {
		return new AdminNotificationJob(UUID.randomUUID(), UUID.randomUUID(), null, NotificationType.EVENT_START,
				NotificationJobStatus.FAILED, 4, 0, NOW, null, NOW, null, stopped, "NOTIFICATION-005", null,
				null, null, eventStatus, starts, deleted);
	}
}
