package com.getddo.db.notification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.audit.repository.AuditLogRepository;
import com.getddo.core.notification.domain.AdminNotification;
import com.getddo.core.notification.domain.AdminNotificationJob;
import com.getddo.core.notification.domain.AdminNotificationJobResult;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryQuery;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobQuery;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationJobStatus;
import com.getddo.core.notification.domain.NotificationRetryPolicy;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.repository.NotificationJobRepository;
import com.getddo.core.notification.service.NotificationAdminService;
import com.getddo.core.notification.service.NotificationJobService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.common.util.UuidBinary;
import com.getddo.db.ticket.MutableClock;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(classes = NotificationJobIntegrationTest.TestApplication.class,
		properties = "spring.jpa.hibernate.ddl-auto=validate")
class NotificationAdminIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
	@Autowired private NotificationAdminService admin;
	@Autowired private NotificationJobService jobs;
	@Autowired private NotificationJobRepository repository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MutableClock clock;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoSpyBean private AuditLogRepository auditRepository;
	private User actor;
	private UUID recipient;
	private final List<UUID> jobIds = new ArrayList<>();
	private final List<UUID> events = new ArrayList<>();

	/** 테스트 데이터와 고정 시계를 준비한다. */
	@BeforeEach
	void setUp() {
		clock.set(NOW);
		UUID id = user("ADMIN");
		actor = new User(id, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, NOW, NOW);
		recipient = user("USER");
	}

	/** 테스트에서 만든 알림·감사·이벤트·사용자만 정리한다. */
	@AfterEach
	void clean() {
		jdbc.update("delete from audit_logs where actor_id = ?", bytes(actor.getId()));
		for (UUID job : jobIds) {
			jdbc.update("delete from notifications where job_id = ?", bytes(job));
			jdbc.update("delete from notification_jobs where id = ?", bytes(job));
		}
		for (UUID event : events) jdbc.update("delete from events where id = ?", bytes(event));
		jdbc.update("delete from users where id in (?, ?)", bytes(actor.getId()), bytes(recipient));
	}

	@Test
	@DisplayName("관리자 알림 목록은 수신자의 현재 이름을 읽고 원본 내용·읽음·발송 상태를 바꾸지 않는다")
	void returnsCurrentRecipientNameWithoutChangingNotification() {
		// given
		UUID job = job(NotificationType.RESULT_PUBLISHED, null);
		UUID notification = prepareNotification(job);
		NotificationDeliveryQuery query = new NotificationDeliveryQuery(1, 1, recipient, null, job, null, null, null);
		AdminNotification before = admin.findDeliveries(actor, query).getItems().getFirst();
		jdbc.update("update users set name = ? where id = ?", "이름 변경 수신자", bytes(recipient));

		// when
		PageResult<AdminNotification> page = admin.findDeliveries(actor, query);
		AdminNotification after = page.getItems().getFirst();

		// then
		assertThat(before.getUserName()).isEqualTo("알림 운영 테스트");
		assertThat(after.getUserName()).isEqualTo("이름 변경 수신자");
		assertThat(after.getUserId()).isEqualTo(recipient);
		assertThat(after.getId()).isEqualTo(notification);
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(after.getBody()).isEqualTo(before.getBody());
		assertThat(after.isRead()).isEqualTo(before.isRead());
		assertThat(after.getMockDeliveryStatus()).isEqualTo(before.getMockDeliveryStatus());
		assertThat(after.getDeliveryAttemptCount()).isEqualTo(before.getDeliveryAttemptCount());
		assertThat(admin.findDeliveries(actor,
				new NotificationDeliveryQuery(2, 1, recipient, null, job, null, null, null)).getItems()).isEmpty();
	}

	@Test
	@DisplayName("발송 재시도 응답에도 처리자가 아닌 수신자의 현재 이름을 반환하고 읽음·원본을 보존한다")
	void returnsRecipientNameOnDeliveryRetry() {
		// given
		UUID job = job(NotificationType.RESULT_PUBLISHED, null);
		UUID notification = prepareNotification(job);
		jdbc.update("update users set name = ? where id = ?", "수신자 현재 이름", bytes(recipient));
		jdbc.update("update users set name = ? where id = ?", "재처리 관리자", bytes(actor.getId()));
		jdbc.update("update notifications set is_read = true, mock_delivery_status = 'FAILED', "
				+ "delivery_attempt_count = 4, next_delivery_attempt_at = null where id = ?", bytes(notification));

		// when
		AdminNotification accepted = admin.retryDelivery(actor, notification);

		// then
		assertThat(accepted.getUserName()).isEqualTo("수신자 현재 이름");
		assertThat(accepted.getUserId()).isEqualTo(recipient);
		assertThat(accepted.isRead()).isTrue();
		assertThat(accepted.getDeliveryAttemptCount()).isEqualTo(4);
		assertThat(accepted.getMockDeliveryStatus().name()).isEqualTo("PENDING");
		assertThat(accepted.getBody()).isEqualTo(repository.findRequest(job).getBody());
		assertThat(auditCount(notification)).isEqualTo(1);
	}

	/** 최종 생성 실패 복구는 기존 알림을 보존하고 오래된 워커를 차단한다 */
	@Test
	@DisplayName("최종 생성 실패 복구는 기존 알림을 보존하고 오래된 워커를 차단한다")
	void retriesGenerationWithoutDuplicatingExistingNotifications() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		NotificationJob old = repository.claimNextJob(NOW).orElseThrow();
		NotificationJobRequest original = repository.findRequest(id);
		assertThat(repository.createNotification(old, original, recipient, NOW)).isTrue();
		repository.failJob(old, null, "NOTIFICATION-006");
		UUID notification = notificationId(id);
		jdbc.update("update notifications set is_read = true where id = ?", bytes(notification));
		// when
		AdminNotificationJob accepted = admin.retryJob(actor, id).getJob();
		// then
		assertThat(accepted.getAttemptCount()).isEqualTo(1);
		assertThat(accepted.getAttemptBase()).isEqualTo(1);
		repository.completeJob(old, NOW);
		assertThat(admin.findJob(actor, id).getJob().getStatus()).isEqualTo(NotificationJobStatus.PENDING);
		NotificationJob current = repository.claimNextJob(NOW).orElseThrow();
		assertThat(current.getAttemptCount()).isEqualTo(2);
		assertThat(current.getCycleAttemptCount()).isEqualTo(1);
		assertThat(repository.createNotification(old, original, recipient, NOW)).isFalse();
		repository.failJob(old, null, "NOTIFICATION-006");
		assertThat(repository.createNotification(current, original, recipient, NOW)).isTrue();
		repository.completeJob(current, NOW);
		assertThat(notificationId(id)).isEqualTo(notification);
		assertThat(jdbc.queryForObject("select is_read from notifications where id = ?", Boolean.class, bytes(notification))).isTrue();
		assertThat(auditCount(id)).isEqualTo(1);
	}

	/** 수동 복구 후 누적 차수 5~8에도 해당 구간의 4회 한도를 적용한다 */
	@Test
	@DisplayName("수동 복구 후 누적 차수 5~8에도 해당 구간의 4회 한도를 적용한다")
	void allowsFourAttemptsInNewCycle() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		jdbc.update("update notification_jobs set status='FAILED', attempt_count=4 where id=?", bytes(id));
		admin.retryJob(actor, id);
		// when / then
		for (int cycle = 1; cycle <= 4; cycle++) {
			NotificationJob current = repository.claimNextJob(clock.instant()).orElseThrow();
			assertThat(current.getAttemptCount()).isEqualTo(4 + cycle);
			assertThat(current.getCycleAttemptCount()).isEqualTo(cycle);
			Instant next = NotificationRetryPolicy.nextAttemptAt(current.getCycleAttemptCount(), clock.instant(),
					new DataAccessResourceFailureException("temporary"));
			repository.failJob(current, next, "NOTIFICATION-005");
			if (next != null) clock.set(next);
		}
		assertThat(repository.claimNextJob(clock.instant())).isEmpty();
		assertThat(admin.findJob(actor, id).getJob().getStatus()).isEqualTo(NotificationJobStatus.FAILED);
	}

	/** 발송 복구 직후 새 선점 전에도 오래된 완료·실패 기록을 차단한다 */
	@Test
	@DisplayName("발송 복구 직후 새 선점 전에도 오래된 완료·실패 기록을 차단한다")
	void deliveryRetryInvalidatesOldWorkerImmediately() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		UUID notification = prepareNotification(id);
		NotificationDelivery old = repository.claimNextDelivery(NOW).orElseThrow();
		assertThat(old.getId()).isEqualTo(notification);
		repository.failDelivery(old, null, "NOTIFICATION-006");
		jdbc.update("update notifications set is_read=true where id=?", bytes(old.getId()));
		// when
		admin.retryDelivery(actor, old.getId());
		repository.completeDelivery(old, NOW);
		repository.failDelivery(old, null, "NOTIFICATION-006");
		// then
		assertThat(jdbc.queryForObject("select mock_delivery_status from notifications where id=?", String.class,
				bytes(old.getId()))).isEqualTo("PENDING");
		NotificationDelivery current = repository.claimNextDelivery(NOW).orElseThrow();
		assertThat(current.getAttemptCount()).isEqualTo(2);
		assertThat(current.getCycleAttemptCount()).isEqualTo(1);
		repository.completeDelivery(current, NOW);
		assertThat(jdbc.queryForObject("select mock_delivery_status from notifications where id=?", String.class,
				bytes(old.getId()))).isEqualTo("SENT");
		assertThat(jdbc.queryForObject("select is_read from notifications where id=?", Boolean.class, bytes(old.getId()))).isTrue();
		assertThat(auditCount(old.getId())).isEqualTo(1);
	}

	/** 시작 전 이벤트를 잠근 뒤 최종 실패 작업의 복구를 접수한다. */
	@Test
	@DisplayName("시작 전 진행 예정 이벤트의 최종 실패는 생성 복구를 접수한다")
	void acceptsScheduledEventBeforeStart() {
		// given
		UUID event = event("SCHEDULED");
		UUID start = job(NotificationType.EVENT_START, event);
		jdbc.update("update notification_jobs set status='FAILED', attempt_count=4 where id=?", bytes(start));
		// when
		AdminNotificationJob accepted = admin.retryJob(actor, start).getJob();
		// then
		assertThat(accepted.getStatus()).isEqualTo(NotificationJobStatus.PENDING);
		assertThat(accepted.getAttemptBase()).isEqualTo(4);
		assertThat(accepted.getNextAttemptAt()).isEqualTo(NOW);
		assertThat(auditCount(start)).isEqualTo(1);
	}

	/** 더 먼저 대기 중인 다른 작업이 있어도 fixture는 대상 ID의 알림만 준비한다. */
	@Test
	@DisplayName("알림 fixture는 다른 대기 작업을 처리하지 않고 요청한 작업만 준비한다")
	void preparesOnlyRequestedJob() {
		// given
		UUID earlier = job(NotificationType.RESULT_PUBLISHED, null);
		jdbc.update("update notification_jobs set scheduled_at=? where id=?", utc(NOW.minusSeconds(1)), bytes(earlier));
		UUID target = job(NotificationType.RESULT_PUBLISHED, null);
		// when
		UUID notification = prepareNotification(target);
		// then
		assertThat(notificationId(target)).isEqualTo(notification);
		assertThat(admin.findJob(actor, target).getJob().getStatus()).isEqualTo(NotificationJobStatus.COMPLETED);
		assertThat(admin.findJob(actor, earlier).getJob().getStatus()).isEqualTo(NotificationJobStatus.PENDING);
		assertThat(jdbc.queryForObject("select count(*) from notifications where job_id=?", Long.class, bytes(earlier))).isZero();
	}

	/** 실제 MySQL에서 V021의 두 CHECK 제약이 잘못된 복구 차수를 거절한다. */
	@Test
	@DisplayName("복구 기준 차수는 음수 또는 누적 차수 초과로 저장할 수 없다")
	void enforcesRetryBaselineConstraints() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		UUID notification = prepareNotification(id);
		// when / then
		for (String baseline : List.of("-1", "attempt_count + 1")) {
			assertThatThrownBy(() -> jdbc.update("update notification_jobs set attempt_base=" + baseline + " where id=?", bytes(id)))
					.isInstanceOf(org.springframework.dao.DataAccessException.class)
					.hasStackTraceContaining("chk_notification_attempt_base");
		}
		for (String baseline : List.of("-1", "delivery_attempt_count + 1")) {
			assertThatThrownBy(() -> jdbc.update("update notifications set delivery_attempt_base=" + baseline + " where id=?", bytes(notification)))
					.isInstanceOf(org.springframework.dao.DataAccessException.class)
					.hasStackTraceContaining("chk_delivery_attempt_base");
		}
		assertThat(admin.findJob(actor, id).getJob().getAttemptBase()).isZero();
	}

	/** 진행 중·성공·중지·오래된 시작 안내는 생성 복구를 거절한다 */
	@Test
	@DisplayName("진행 중·성공·중지·오래된 시작 안내는 생성 복구를 거절한다")
	void rejectsNonFinalAndObsoleteJobs() {
		// given
		UUID pending = job(NotificationType.RESULT_PUBLISHED, null);
		assertThatThrownBy(() -> admin.retryJob(actor, pending)).isInstanceOf(BusinessException.class);
		jdbc.update("update notification_jobs set status='COMPLETED' where id=?", bytes(pending));
		assertThatThrownBy(() -> admin.retryJob(actor, pending)).isInstanceOf(BusinessException.class);
		jdbc.update("update notification_jobs set status='FAILED', payload=json_set(payload,'$.generationStoppedBy','change') where id=?", bytes(pending));
		assertThatThrownBy(() -> admin.retryJob(actor, pending)).isInstanceOf(BusinessException.class);
		for (String status : List.of("SCHEDULED", "CANCELED", "OPEN")) {
			UUID event = event(status);
			UUID start = job(NotificationType.EVENT_START, event);
			jdbc.update("update notification_jobs set status='FAILED' where id=?", bytes(start));
			if (status.equals("SCHEDULED")) clock.set(NOW.plusSeconds(600));
			// when / then
			assertThatThrownBy(() -> admin.retryJob(actor, start)).isInstanceOf(BusinessException.class);
		}
	}

	/** 조회는 기간 상한 제외·페이지·필터를 적용하고 읽음 상태를 변경하지 않는다 */
	@Test
	@DisplayName("조회는 기간 상한 제외·페이지·필터를 적용하고 읽음 상태를 변경하지 않는다")
	void filtersPagesAndPreservesReadState() {
		// given
		UUID first = job(NotificationType.RESULT_PUBLISHED, null);
		UUID second = job(NotificationType.RESULT_PUBLISHED, null);
		UUID third = job(NotificationType.RESULT_PUBLISHED, null);
		jdbc.update("update notification_jobs set created_at=? where id=?", utc(NOW.plusSeconds(1)), bytes(second));
		jdbc.update("update notification_jobs set created_at=? where id=?", utc(NOW.plusSeconds(2)), bytes(third));
		// when / then
		PageResult<AdminNotificationJobResult> result = admin.findJobs(actor, new NotificationJobQuery(1, 1, null, NotificationType.RESULT_PUBLISHED,
				null, NOW, NOW.plusSeconds(2)));
		assertThat(result.getTotalElements()).isEqualTo(2);
		assertThat(result.getItems()).extracting(value -> value.getJob().getId()).containsExactly(second);
		assertThat(admin.findJobs(actor, new NotificationJobQuery(3, 1, null, null, null, NOW, NOW.plusSeconds(2)))
				.getItems()).isEmpty();
		UUID notification = prepareNotification(first);
		assertThat(admin.findDeliveries(actor, new NotificationDeliveryQuery(1, 20, recipient, null, first, null, null, null))
				.getItems()).hasSize(1);
		assertThat(jdbc.queryForObject("select is_read from notifications where id=?", Boolean.class, bytes(notification))).isFalse();
	}


	/** 대용량 JSON 상세를 정렬에서 분리해도 페이지·필터·동률 순서를 유지한다. */
	@Test
	@DisplayName("큰 수신자 JSON 작업도 필터와 최신순 페이지 및 상세 정보를 유지한다")
	void pagesLargePayloadsWithoutSortingDetails() {
		// given
		UUID eventId = event("SCHEDULED");
		UUID publicationId = UUID.randomUUID();
		List<UUID> recipients = new ArrayList<>();
		for (int index = 0; index < 10_000; index++) {
			recipients.add(UUID.randomUUID());
		}
		List<UUID> expected = new ArrayList<>();
		for (int index = 0; index < 3; index++) {
			UUID id = jobs.register(new NotificationJobRequest("admin-large:" + UUID.randomUUID(),
					NotificationType.RESULT_PUBLISHED, eventId, publicationId, "제목", "내용", null, NOW, recipients));
			jobIds.add(id);
			expected.add(id);
		}
		// 모든 생성 시각이 같으므로 BINARY UUID 내림차순과 같은 문자열 순서로 기대값을 만든다.
		expected.sort(Comparator.comparing(UUID::toString).reversed());
		job(NotificationType.RESULT_CHANGED, eventId);
		job(NotificationType.RESULT_PUBLISHED, null);
		UUID completed = job(NotificationType.RESULT_PUBLISHED, eventId);
		jdbc.update("update notification_jobs set status='COMPLETED' where id=?", bytes(completed));
		UUID outside = job(NotificationType.RESULT_PUBLISHED, eventId);
		jdbc.update("update notification_jobs set created_at=? where id=?", utc(NOW.plusSeconds(1)), bytes(outside));

		// when
		PageResult<AdminNotificationJobResult> first = admin.findJobs(actor,
				new NotificationJobQuery(1, 2, NotificationJobStatus.PENDING, NotificationType.RESULT_PUBLISHED,
						eventId, NOW, NOW.plusSeconds(1)));
		PageResult<AdminNotificationJobResult> second = admin.findJobs(actor,
				new NotificationJobQuery(2, 2, NotificationJobStatus.PENDING, NotificationType.RESULT_PUBLISHED,
						eventId, NOW, NOW.plusSeconds(1)));

		// then
		PageResult<AdminNotificationJobResult> unfiltered = admin.findJobs(actor,
				new NotificationJobQuery(1, 20, null, null, null, null, null));
		assertThat(unfiltered.getTotalElements()).isEqualTo(7);
		assertThat(unfiltered.getItems()).hasSize(7);
		assertThat(first.getTotalElements()).isEqualTo(3);
		assertThat(second.getTotalElements()).isEqualTo(3);
		assertThat(first.getItems()).extracting(value -> value.getJob().getId()).containsExactlyElementsOf(expected.subList(0, 2));
		assertThat(second.getItems()).extracting(value -> value.getJob().getId()).containsExactly(expected.get(2));
		assertThat(first.getItems()).allSatisfy(value -> {
			assertThat(value.getJob().getPublicationId()).isEqualTo(publicationId);
			assertThat(value.getJob().getEventId()).isEqualTo(eventId);
		});
		assertThat(admin.findJobs(actor, new NotificationJobQuery(3, 2, NotificationJobStatus.PENDING,
				NotificationType.RESULT_PUBLISHED, eventId, NOW, NOW.plusSeconds(1))).getItems()).isEmpty();
	}

	/** 감사 저장소 실패가 실제 Service 트랜잭션의 작업 갱신을 롤백한다. */
	@Test
	@DisplayName("감사 저장 실패는 생성 복구 접수와 기준 차수 변경을 롤백한다")
	void rollsBackRecoveryWhenAuditStorageFails() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		jdbc.update("update notification_jobs set status='FAILED', attempt_count=4, attempt_base=2 where id=?", bytes(id));
		doThrow(new DataAccessResourceFailureException("audit storage unavailable"))
				.when(auditRepository).append(any());
		// when / then
		assertThatThrownBy(() -> admin.retryJob(actor, id))
				.isInstanceOf(DataAccessResourceFailureException.class).hasMessage("audit storage unavailable");
		AdminNotificationJob unchanged = admin.findJob(actor, id).getJob();
		assertThat(unchanged.getStatus()).isEqualTo(NotificationJobStatus.FAILED);
		assertThat(unchanged.getAttemptCount()).isEqualTo(4);
		assertThat(unchanged.getAttemptBase()).isEqualTo(2);
		assertThat(unchanged.getNextAttemptAt()).isNull();
		assertThat(auditCount(id)).isZero();
	}

	/** 감사 기록과 복구 접수는 호출자 롤백에 함께 취소된다 */
	@Test
	@DisplayName("감사 기록과 복구 접수는 호출자 롤백에 함께 취소된다")
	void rollsBackRecoveryAndAudit() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		jdbc.update("update notification_jobs set status='FAILED', attempt_count=4 where id=?", bytes(id));
		// when / then
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			admin.retryJob(actor, id);
			throw new IllegalStateException("rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(admin.findJob(actor, id).getJob().getStatus()).isEqualTo(NotificationJobStatus.FAILED);
		assertThat(admin.findJob(actor, id).getJob().getAttemptBase()).isZero();
		assertThat(auditCount(id)).isZero();
	}

	/** 같은 최종 실패의 동시 복구는 하나만 접수하고 감사 기록도 한 건이다 */
	@Test
	@DisplayName("같은 최종 실패의 동시 복구는 하나만 접수하고 감사 기록도 한 건이다")
	void acceptsOnlyOneConcurrentRecovery() throws Exception {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		jdbc.update("update notification_jobs set status='FAILED', attempt_count=4 where id=?", bytes(id));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch gate = new CountDownLatch(1);
		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> recoverAtGate(id, ready, gate));
			Future<Boolean> second = executor.submit(() -> recoverAtGate(id, ready, gate));
			boolean bothReady = ready.await(10, TimeUnit.SECONDS);
			gate.countDown();
			// then
			assertThat(bothReady).isTrue();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		}
		assertThat(auditCount(id)).isEqualTo(1);
	}

	/** 나노초 기간 필터에서도 마이크로초 저장값의 하한 포함·상한 제외를 유지한다 */
	@Test
	@DisplayName("나노초 기간 필터에서도 마이크로초 저장값의 하한 포함·상한 제외를 유지한다")
	void preservesSubMicrosecondBounds() {
		// given
		UUID id = job(NotificationType.RESULT_PUBLISHED, null);
		// when / then
		assertThat(admin.findJobs(actor, new NotificationJobQuery(1, 20, null, null, null,
				NOW.plusNanos(1), NOW.plusSeconds(1))).getItems()).isEmpty();
		assertThat(admin.findJobs(actor, new NotificationJobQuery(1, 20, null, null, null,
				NOW, NOW.plusNanos(1))).getItems()).extracting(value -> value.getJob().getId()).containsExactly(id);
	}

	private boolean recoverAtGate(UUID id, CountDownLatch ready, CountDownLatch gate) throws InterruptedException {
		ready.countDown();
		if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
		try {
			admin.retryJob(actor, id);
			return true;
		} catch (BusinessException failure) {
			assertThat(failure.getErrorCode().getCode()).isEqualTo("NOTIFICATION-010");
			return false;
		}
	}

	private UUID job(NotificationType type, UUID event) {
		UUID id = jobs.register(new NotificationJobRequest("admin-test:" + UUID.randomUUID(), type, event, null,
				"제목", "내용", null, NOW, List.of(recipient)));
		jobIds.add(id);
		return id;
	}

	/** 전역 생성 대기열을 처리하지 않고 지정한 작업의 사용자 알림만 준비한다. */
	private UUID prepareNotification(UUID id) {
		jdbc.update("""
			update notification_jobs set status='PROCESSING', attempt_count=1, lease_until=? where id=?
			""", utc(NOW.plus(NotificationRetryPolicy.LEASE)), bytes(id));
		NotificationJob claim = new NotificationJob(id, 1);
		NotificationJobRequest request = repository.findRequest(id);
		assertThat(repository.createNotification(claim, request, recipient, NOW)).isTrue();
		repository.completeJob(claim, NOW);
		return notificationId(id);
	}

	private UUID notificationId(UUID job) {
		return jdbc.queryForObject("select id from notifications where job_id=?", (row, index) -> UuidBinary.fromBytes(row.getBytes("id")), bytes(job));
	}

	private long auditCount(UUID target) {
		return jdbc.queryForObject("select count(*) from audit_logs where target_id=?", Long.class, bytes(target));
	}

	private UUID user(String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id,name,role,status,created_at,updated_at) values (?,'알림 운영 테스트',?,'ACTIVE',?,?)",
				bytes(id), role, utc(NOW), utc(NOW));
		return id;
	}

	private UUID event(String status) {
		UUID id = UUID.randomUUID();
		events.add(id);
		jdbc.update("""
			insert into events (id,title,description,event_type,starts_at,ends_at,status,created_at,updated_at,membership_rule)
			values (?,'시작 안내 테스트','설명','NO_TICKET',?,?,?,?,?,'vip')
			""", bytes(id), utc(NOW.plusSeconds(600)), utc(NOW.plusSeconds(3600)), status, utc(NOW), utc(NOW));
		return id;
	}

	private static byte[] bytes(UUID id) { return UuidBinary.toBytes(id); }
	private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
