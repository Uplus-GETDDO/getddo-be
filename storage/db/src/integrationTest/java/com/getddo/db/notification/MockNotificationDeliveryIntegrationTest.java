package com.getddo.db.notification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.getddo.core.notification.domain.AdminNotification;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDeliveryQuery;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.service.MockNotificationSender;
import com.getddo.core.notification.service.NotificationAdminService;
import com.getddo.core.notification.service.NotificationJobService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.common.util.UuidBinary;
import com.getddo.db.ticket.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 실제 모의 발송기의 실패 결과가 저장·자동 재시도·관리자 복구로 이어지는지 검증한다. */
@SpringBootTest(classes = NotificationJobIntegrationTest.TestApplication.class, properties = {
		"spring.jpa.hibernate.ddl-auto=validate", "getddo.notification.mock.failure-percent=100"
})
class MockNotificationDeliveryIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-23T01:00:00Z");
	@Autowired private NotificationJobService jobs;
	@Autowired private NotificationAdminService admin;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MutableClock clock;
	@MockitoSpyBean private MockNotificationSender sender;
	private UUID recipient;
	private User actor;
	private UUID jobId;

	/** 실제 실패를 낼 발송기와 고정 시계·독립 수신자를 준비한다. */
	@BeforeEach
	void setUp() {
		clock.set(NOW);
		recipient = user("USER");
		actor = new User(user("ADMIN"), "관리자", UserRole.ADMIN, UserStatus.ACTIVE,
				null, null, null, null, null, NOW, NOW);
	}

	/** 이 시나리오가 만든 데이터만 제거한다. */
	@AfterEach
	void clean() {
		jdbc.update("delete from audit_logs where actor_id = ?", bytes(actor.getId()));
		if (jobId != null) {
			jdbc.update("delete from notifications where job_id = ?", bytes(jobId));
			jdbc.update("delete from notification_jobs where id = ?", bytes(jobId));
		}
		jdbc.update("delete from users where id in (?, ?)", bytes(recipient), bytes(actor.getId()));
	}

	/** 무작위 표본 대신 100% 설정으로 모든 자동 재시도와 관리자 복구를 확정적으로 재현한다. */
	@Test
	@DisplayName("실제 모의 발송 실패는 10·30·60초 재시도 후 중단하고 관리자 복구로 같은 알림을 발송한다")
	void persistsMockFailuresAndRecoversSameNotification() {
		// given
		jobId = jobs.register(new NotificationJobRequest("mock-failure:" + UUID.randomUUID(),
				NotificationType.RESULT_PUBLISHED, null, null, "원래 제목", "원래 내용", "/events/test/results",
				NOW, List.of(recipient)));
		assertThat(jobs.processNextJob()).isTrue();
		AdminNotification original = notification();
		jdbc.update("update notifications set is_read = true where id = ?", bytes(original.getId()));
		int[] delays = {10, 30, 60};

		// when / then: 실제 발송기의 FAILED 반환으로 기존 재시도 경로를 실행한다.
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(jobs.processNextDelivery()).isTrue();
			AdminNotification failed = notification();
			assertThat(failed.getMockDeliveryStatus()).isEqualTo(MockDeliveryStatus.FAILED);
			assertThat(failed.getDeliveryAttemptCount()).isEqualTo(attempt);
			assertThat(failed.getLastDeliveryError()).isEqualTo("NOTIFICATION-005");
			assertThat(failed.getMockSentAt()).isNull();
			assertThat(failed.getId()).isEqualTo(original.getId());
			assertThat(failed.isRead()).isTrue();
			assertThat(failed.isRetryable()).isEqualTo(attempt == 4);
			if (attempt < 4) {
				Instant next = clock.instant().plusSeconds(delays[attempt - 1]);
				assertThat(failed.getNextDeliveryAttemptAt()).isEqualTo(next);
				assertThat(jobs.processNextDelivery()).isFalse();
				clock.set(next);
			} else {
				assertThat(failed.getNextDeliveryAttemptAt()).isNull();
			}
		}
		clock.set(clock.instant().plusSeconds(3600));
		assertThat(jobs.processNextDelivery()).isFalse();
		assertThat(admin.findJob(actor, jobId).getJob().getStatus().name()).isEqualTo("COMPLETED");

		// when: 장애 해소를 고정한 뒤 관리자 복구를 접수한다.
		doReturn(MockDeliveryStatus.SENT).when(sender).send(any());
		AdminNotification accepted = admin.retryDelivery(actor, original.getId());
		assertThat(accepted.getMockDeliveryStatus()).isEqualTo(MockDeliveryStatus.PENDING);
		assertThat(accepted.getDeliveryAttemptCount()).isEqualTo(4);
		assertThat(jobs.processNextDelivery()).isTrue();

		// then: 기존 ID·내용·읽음을 보존하고 발송 결과만 성공으로 바뀐다.
		AdminNotification sent = notification();
		assertThat(sent.getId()).isEqualTo(original.getId());
		assertThat(sent.getTitle()).isEqualTo(original.getTitle());
		assertThat(sent.getBody()).isEqualTo(original.getBody());
		assertThat(sent.getLinkUrl()).isEqualTo(original.getLinkUrl());
		assertThat(sent.getCreatedAt()).isEqualTo(original.getCreatedAt());
		assertThat(sent.isRead()).isTrue();
		assertThat(sent.getMockDeliveryStatus()).isEqualTo(MockDeliveryStatus.SENT);
		assertThat(sent.getDeliveryAttemptCount()).isEqualTo(5);
		assertThat(sent.getMockSentAt()).isEqualTo(clock.instant());
		assertThat(sent.getNextDeliveryAttemptAt()).isNull();
		assertThat(sent.getLastDeliveryError()).isNull();
		assertThat(jdbc.queryForObject("select count(*) from notifications where job_id = ?",
				Long.class, bytes(jobId))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id = ?",
				Long.class, bytes(actor.getId()))).isEqualTo(1);
		verify(sender, times(5)).send(any());
	}

	/** 관리자 조회와 동일한 경로에서 발송 상태를 읽는다. */
	private AdminNotification notification() {
		return admin.findDeliveries(actor, new NotificationDeliveryQuery(1, 20, recipient, null,
				jobId, null, null, null)).getItems().getFirst();
	}

	/** 외부 계정에 의존하지 않고 테스트 전용 사용자를 만든다. */
	private UUID user(String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id,name,role,status,created_at,updated_at) values (?,'모의 발송 검증',?,'ACTIVE',?,?)",
				bytes(id), role, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		return id;
	}

	/** 공용 UUID 저장 형식으로 변환한다. */
	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}
}
