package com.getddo.db.notification;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.exception.NotificationProcessingErrorCode;
import com.getddo.core.notification.exception.NotificationProcessingException;
import com.getddo.core.notification.repository.NotificationJobRepository;
import com.getddo.core.notification.service.MockNotificationSender;
import com.getddo.core.notification.service.NotificationBatchTransactionService;
import com.getddo.core.notification.service.NotificationJobService;
import com.getddo.core.notification.service.NotificationService;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.db.common.config.JpaAuditingConfig;
import com.getddo.db.support.MySqlTestConfiguration;
import com.getddo.db.ticket.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = NotificationJobIntegrationTest.TestApplication.class,
		properties = "spring.jpa.hibernate.ddl-auto=validate")
class NotificationJobIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000302");
	private static final UUID MISSING = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

	@Autowired private NotificationJobService service;
	@Autowired private NotificationService notificationService;
	@Autowired private NotificationBatchTransactionService batches;
	@MockitoSpyBean private NotificationJobRepository repository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MutableClock clock;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private MockNotificationSender sender;
	private String prefix;
	private final List<UUID> additionalUsers = new ArrayList<>();

	/** 각 시나리오의 시계·사용자·발송 결과를 초기화해 테스트 간 영향을 차단한다. */
	@BeforeEach
	void setUp() {
		clock.set(NOW);
		prefix = "gd68-test-" + UUID.randomUUID() + "-";
		for (UUID id : List.of(USER, OTHER)) {
			jdbc.update("""
				insert into users (id, name, role, status, membership, created_at, updated_at)
				values (?, '알림 테스트', 'USER', 'ACTIVE', 'VIP', ?, ?)
				""", bytes(id), utc(NOW), utc(NOW));
		}
		when(sender.send(any())).thenReturn(MockDeliveryStatus.SENT);
	}

	/** 각 시나리오에서 만든 알림·작업·사용자를 정리해 다음 테스트와 격리한다. */
	@AfterEach
	void clean() {
		List<byte[]> jobs = jdbc.query("select id from notification_jobs where occurrence_key like ?",
				(row, index) -> row.getBytes("id"), prefix + "%");
		for (byte[] id : jobs) {
			jdbc.update("delete from notifications where job_id = ?", id);
			jdbc.update("delete from notification_jobs where id = ?", id);
		}
		jdbc.update("delete from users where id in (?, ?)", bytes(USER), bytes(OTHER));
		for (UUID id : additionalUsers) {
			jdbc.update("delete from users where id = ?", bytes(id));
		}
	}

	/** 검증 시나리오: 같은 발생 건과 수신자 순서 변경은 같은 작업이며 다른 문구는 충돌로 거절한다. */
	@Test
	@DisplayName("같은 발생 건과 수신자 순서 변경은 같은 작업이며 다른 문구는 충돌로 거절한다")
	void idempotentRegistrationAndConflictingInput() {
		// given
		NotificationJobRequest request = request("same", NOW, List.of(USER, OTHER));
		UUID id = service.register(request);

		// when
		UUID replay = service.register(request("same", NOW, List.of(OTHER, USER, USER)));

		// then
		assertThat(replay).isEqualTo(id);
		assertThat(id.version()).isEqualTo(7);
		assertThat(count("select count(*) from notification_jobs where id = ?", bytes(id))).isEqualTo(1);
		assertThat(repository.findRequest(id)).isEqualTo(request);
		assertThatThrownBy(() -> service.register(new NotificationJobRequest(request.getOccurrenceKey(),
				request.getType(), null, null, "다른 제목", request.getBody(), null, NOW, request.getRecipientIds())))
				.isInstanceOf(NotificationProcessingException.class)
				.extracting(error -> ((NotificationProcessingException) error).getErrorCode())
				.isEqualTo(NotificationProcessingErrorCode.OCCURRENCE_CONFLICT);
		assertThat(repository.findRequest(id)).isEqualTo(request);
	}

	@Test
	@DisplayName("발표 ID는 삭제된 컬럼 대신 payload에 보존하며 같은 키의 다른 발표는 거절한다")
	void preservesPublicationInPayloadAndRejectsChangedPublication() {
		UUID publicationId = UUID.randomUUID();
		NotificationJobRequest request = new NotificationJobRequest(prefix + "publication",
				NotificationType.RESULT_PUBLISHED, null, publicationId, "결과 발표", "내용", null,
				NOW, List.of(USER));

		UUID id = service.register(request);

		assertThat(repository.findRequest(id)).isEqualTo(request);
		assertThat(service.register(request)).isEqualTo(id);
		assertThat(jdbc.queryForObject("""
			select json_unquote(json_extract(payload, '$.publicationId'))
			from notification_jobs where id = ?
			""", String.class, bytes(id))).isEqualTo(publicationId.toString());
		assertThatThrownBy(() -> service.register(new NotificationJobRequest(request.getOccurrenceKey(),
				request.getType(), null, UUID.randomUUID(), request.getTitle(), request.getBody(), null,
				NOW, request.getRecipientIds())))
				.isInstanceOf(NotificationProcessingException.class)
				.extracting(error -> ((NotificationProcessingException) error).getErrorCode())
				.isEqualTo(NotificationProcessingErrorCode.OCCURRENCE_CONFLICT);
		assertThat(service.processNextJob()).isTrue();
		assertThat(count("select count(*) from notifications where job_id = ? and user_id = ?",
				bytes(id), bytes(USER))).isEqualTo(1);
	}

	@Test
	@DisplayName("발표 ID 필드가 없는 기존 payload도 읽고 처리한다")
	void processesPayloadWithoutPublicationField() {
		NotificationJobRequest request = request("old-payload", NOW, List.of(USER));
		UUID id = service.register(request);
		jdbc.update("update notification_jobs set payload = json_remove(payload, '$.publicationId') where id = ?",
				bytes(id));

		assertThat(repository.findRequest(id)).isEqualTo(request);
		assertThat(service.register(request)).isEqualTo(id);
		assertThat(service.processNextJob()).isTrue();
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
	}

	@Test
	@DisplayName("새 스키마에서 생성한 알림도 본인 조회·개별 읽음·전체 읽음과 소유권 검사를 유지한다")
	void generatedNotificationsRemainQueryableAndReadableByOwner() {
		UUID firstJob = service.register(request("read-first", NOW, List.of(USER, OTHER)));
		service.register(request("read-second", NOW, List.of(USER)));
		assertThat(service.processNextJob()).isTrue();
		assertThat(service.processNextJob()).isTrue();
		User user = new User(USER, "알림 테스트", UserRole.USER, UserStatus.ACTIVE, Membership.VIP,
				null, null, null, null, NOW, NOW);

		var unread = notificationService.findMine(user, null, 20, false);
		assertThat(unread.getTotalElements()).isEqualTo(2);
		assertThat(unread.getItems()).hasSize(2);
		assertThat(unread.getItems()).allMatch(notification -> !notification.isRead());
		assertThatThrownBy(() -> notificationService.markRead(user, notificationId(firstJob, OTHER)))
				.isInstanceOf(BusinessException.class);
		assertThat(notificationService.markRead(user, notificationId(firstJob, USER)))
				.isEqualTo(notificationId(firstJob, USER));
		assertThat(notificationService.markAllRead(user)).isEqualTo(1);
		assertThat(notificationService.markAllRead(user)).isZero();
		assertThat(notificationService.findMine(user, null, 20, false).getItems()).isEmpty();
		assertThat(count("select count(*) from notifications where user_id = ? and is_read = false",
				bytes(OTHER))).isEqualTo(1);
	}

	/** 검증 시나리오: 업무 트랜잭션이 롤백되면 발생 건의 작업도 남지 않는다. */
	@Test
	@DisplayName("업무 트랜잭션이 롤백되면 발생 건의 작업도 남지 않는다")
	void registrationParticipatesInOriginalTransaction() {
		// given
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		// when
		UUID id = transaction.execute(status -> {
			UUID result = service.register(request("rollback", NOW, List.of(USER)));
			status.setRollbackOnly();
			return result;
		});

		// then
		assertThat(count("select count(*) from notification_jobs where id = ?", bytes(id))).isZero();
		assertThat(service.processNextJob()).isFalse();
	}

	/** 검증 시나리오: 이전 스냅샷 뒤 다른 트랜잭션이 등록한 작업도 동일 요청은 재사용하고 다른 입력은 충돌로 거절한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("이전 스냅샷 뒤 다른 트랜잭션이 등록한 작업도 동일 요청은 재사용하고 다른 입력은 충돌로 거절한다")
	void duplicateRegistrationReadsCurrentRowAfterEarlierSnapshot(boolean conflicting) {
		// given
		NotificationJobRequest original = request("snapshot", NOW, List.of(USER, OTHER));
		NotificationJobRequest candidate = conflicting
				? request("snapshot", NOW, List.of(USER)) : original;
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		TransactionTemplate separate = new TransactionTemplate(transactionManager);
		separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		UUID[] registeredId = new UUID[1];
		Runnable registration = () -> transaction.executeWithoutResult(status -> {
			assertThat(count("select count(*) from notification_jobs where occurrence_key = ?",
					original.getOccurrenceKey())).isZero();
			registeredId[0] = separate.execute(inner -> service.register(original));
			// 이전 스냅샷에서는 다른 트랜잭션이 커밋한 작업이 아직 보이지 않는다.
			assertThat(count("select count(*) from notification_jobs where occurrence_key = ?",
					original.getOccurrenceKey())).isZero();
			assertThat(service.register(candidate)).isEqualTo(registeredId[0]);
		});

		// when / then
		if (conflicting) {
			assertThatThrownBy(registration::run).isInstanceOf(NotificationProcessingException.class)
					.extracting(error -> ((NotificationProcessingException) error).getErrorCode())
					.isEqualTo(NotificationProcessingErrorCode.OCCURRENCE_CONFLICT);
		} else {
			registration.run();
		}
		assertThat(repository.findRequest(registeredId[0])).isEqualTo(original);
		assertThat(count("select count(*) from notification_jobs where occurrence_key = ?",
				original.getOccurrenceKey())).isEqualTo(1);
	}

	/** 검증 시나리오: 예약 시각 전에는 생성하지 않으며 생성·모의 발송은 읽음 상태를 변경하지 않는다. */
	@Test
	@DisplayName("예약 시각 전에는 생성하지 않으며 생성·모의 발송은 읽음 상태를 변경하지 않는다")
	void respectsScheduledBoundaryAndSeparatesDeliveryFromRead() {
		// given
		Instant scheduled = NOW.plusSeconds(600);
		UUID id = service.register(request("schedule", scheduled, List.of(USER)));
		clock.set(scheduled.minusNanos(1_000));

		// when / then
		assertThat(service.processNextJob()).isFalse();
		clock.set(scheduled);
		assertThat(service.processNextJob()).isTrue();
		assertThat(jobStatus(id)).isEqualTo("COMPLETED");
		assertThat(service.processNextDelivery()).isTrue();
		assertThat(count("select count(*) from notifications where job_id = ? and is_read = false and mock_delivery_status = 'SENT'",
				bytes(id))).isEqualTo(1);
		assertThat(notificationId(id, USER).version()).isEqualTo(7);
		assertThat(service.processNextJob()).isFalse();
		assertThat(service.processNextDelivery()).isFalse();
	}

	/** 검증 시나리오: 부분 생성 후 재시도는 기존 알림 ID·읽음·발송 결과를 보존하고 누락 사용자만 추가한다. */
	@Test
	@DisplayName("부분 생성 후 재시도는 기존 알림 ID·읽음·발송 결과를 보존하고 누락 사용자만 추가한다")
	void retriesPartialGenerationWithoutChangingExistingNotifications() {
		// given
		NotificationJobRequest request = request("partial", NOW, List.of(USER, OTHER));
		UUID id = service.register(request);
		NotificationJob job = repository.claimNextJob(NOW).orElseThrow();
		assertThat(repository.createNotification(job, request, USER, NOW)).isTrue();
		UUID original = notificationId(id, USER);
		jdbc.update("update notifications set is_read = true, mock_delivery_status = 'SENT' where id = ?", bytes(original));
		repository.failJob(job, NOW.plusSeconds(10), "NOTIFICATION-005");

		// when / then
		clock.set(NOW.plusSeconds(10).minusNanos(1_000));
		assertThat(service.processNextJob()).isFalse();
		clock.set(NOW.plusSeconds(10));
		assertThat(service.processNextJob()).isTrue();
		assertThat(jobStatus(id)).isEqualTo("COMPLETED");
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(2);
		assertThat(notificationId(id, USER)).isEqualTo(original);
		assertThat(count("select count(*) from notifications where id = ? and is_read = true and mock_delivery_status = 'SENT'",
				bytes(original))).isEqualTo(1);
	}

	/** 검증 시나리오: 다음 묶음의 FK 위반은 앞서 확정한 100건과 원본 업무를 롤백하지 않는다. */
	@Test
	@DisplayName("다음 묶음의 FK 위반은 최종 실패로 기록하며 앞선 100건과 확정 업무를 보존한다")
	void permanentGenerationFailureDoesNotUndoCommittedBusiness() {
		// given
		List<UUID> recipients = new ArrayList<>(List.of(USER));
		for (int index = 0; index < 99; index++) {
			UUID userId = new UUID(0, 10_000 + index);
			additionalUsers.add(userId);
			recipients.add(userId);
			jdbc.update("""
				insert into users (id, name, role, status, membership, created_at, updated_at)
				values (?, '묶음 생성', 'USER', 'ACTIVE', 'VIP', ?, ?)
				""", bytes(userId), utc(NOW), utc(NOW));
		}
		recipients.add(MISSING);
		UUID id = new TransactionTemplate(transactionManager).execute(status -> {
			jdbc.update("update users set name = '업무 확정' where id = ?", bytes(USER));
			return service.register(request("invalid-user", NOW, recipients));
		});

		// when
		assertThat(service.processNextJob()).isTrue();

		// then
		assertThat(jobStatus(id)).isEqualTo("FAILED");
		assertThat(count("select attempt_count from notification_jobs where id = ?", bytes(id))).isEqualTo(1);
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(100);
		assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, bytes(USER))).isEqualTo("업무 확정");
		assertThat(jdbc.queryForObject("select last_error from notification_jobs where id = ?", String.class, bytes(id)))
				.isEqualTo("NOTIFICATION-006");
		clock.set(NOW.plusSeconds(1000));
		assertThat(service.processNextJob()).isFalse();
	}

	/** 같은 묶음의 일부 INSERT가 실패하면 원본 업무와 분리해 해당 묶음을 롤백한다. */
	@Test
	@DisplayName("같은 묶음의 FK 실패는 해당 묶음만 롤백하고 확정 업무는 보존한다")
	void rollsBackEntireFailedGenerationChunk() {
		// given
		UUID id = new TransactionTemplate(transactionManager).execute(status -> {
			jdbc.update("update users set name = '묶음 전 업무 확정' where id = ?", bytes(USER));
			return service.register(request("failed-chunk", NOW, List.of(USER, MISSING)));
		});

		// when
		assertThat(service.processNextJob()).isTrue();

		// then
		assertThat(jobStatus(id)).isEqualTo("FAILED");
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isZero();
		assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, bytes(USER)))
				.isEqualTo("묶음 전 업무 확정");
	}

	/** 첫 트랜잭션의 실제 선점 잠금을 유지한 상태에서 다음 선점의 비대기·중복 방지·후속 처리를 검증한다. */
	@Test
	@DisplayName("동시 선점은 최대 100건씩 중복 없이 확보하고 총 200건 전체를 이어 처리한다")
	void concurrentDeliveryBatchesDoNotOverlap() throws Exception {
		// given
		for (int index = 0; index < 200; index++) {
			service.register(request("batch-delivery-" + index, NOW, List.of(USER)));
			assertThat(service.processNextJob()).isTrue();
		}
		CountDownLatch firstLocked = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicBoolean holdFirst = new AtomicBoolean(true);
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<NotificationDelivery> deliveries = (List<NotificationDelivery>) invocation.callRealMethod();
			if (holdFirst.compareAndSet(true, false)) {
				assertThat(deliveries).isNotEmpty();
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
				// 호출한 Service의 REQUIRES_NEW는 아직 반환하지 않아 실제 행 잠금이 유지된다.
				firstLocked.countDown();
				if (!releaseFirst.await(30, TimeUnit.SECONDS)) {
					throw new IllegalStateException("첫 발송 선점 트랜잭션 해제 대기 시간 초과");
				}
			}
			return deliveries;
		}).when(repository).claimNextDeliveries(NOW, 100);

		// when
		try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<List<NotificationDelivery>> first = executor.submit(() -> batches.claimNextDeliveries(NOW, 100));
			List<NotificationDelivery> right;
			try {
				assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
				Future<List<NotificationDelivery>> second = executor.submit(() -> batches.claimNextDeliveries(NOW, 100));
				right = second.get(10, TimeUnit.SECONDS);
				assertThat(first).isNotDone();
			} finally {
				releaseFirst.countDown();
			}
			List<NotificationDelivery> left = first.get(15, TimeUnit.SECONDS);

			// then
			assertThat(left).hasSize(100);
			assertThat(right).hasSizeLessThanOrEqualTo(100);
			List<NotificationDelivery> claimed = new ArrayList<>(left);
			claimed.addAll(right);
			assertThat(claimed).isNotEmpty();
			assertThat(claimed.stream().map(NotificationDelivery::getId).distinct().count())
					.isEqualTo(claimed.size());
			// InnoDB가 조회 중 잠근 행은 커밋 뒤 다음 호출에서 다시 후보가 될 수 있다.
			while (claimed.size() < 200) {
				List<NotificationDelivery> remaining = batches.claimNextDeliveries(NOW, 100);
				assertThat(remaining).isNotEmpty().hasSizeLessThanOrEqualTo(100);
				claimed.addAll(remaining);
				assertThat(claimed.stream().map(NotificationDelivery::getId).distinct().count())
						.isEqualTo(claimed.size());
			}
			assertThat(claimed).hasSize(200).allMatch(delivery -> delivery.getAttemptCount() == 1);
			assertThat(count("select count(*) from notifications n join notification_jobs j on j.id = n.job_id"
					+ " where j.occurrence_key like ? and n.delivery_attempt_count = 1", prefix + "batch-delivery-%"))
					.isEqualTo(200);
			assertThat(batches.claimNextDeliveries(NOW, 100)).isEmpty();
		}
	}

	/** 성공·실패 혼합 저장에서도 읽음과 새 선점·수동 복구 구간의 결과를 보존한다. */
	@Test
	@DisplayName("묶음 결과는 읽음을 보존하고 이전 상태·선점·복구 기준 차수의 덮어쓰기를 차단한다")
	void preservesReadStateAndFencesBatchedResults() {
		UUID job = service.register(request("result-batch", NOW, List.of(USER, OTHER)));
		assertThat(service.processNextJob()).isTrue();
		List<NotificationDelivery> claimed = batches.claimNextDeliveries(NOW, 100);
		assertThat(claimed).hasSize(2);
		NotificationDelivery sent = claimed.getFirst();
		NotificationDelivery failed = claimed.getLast();
		jdbc.update("update notifications set is_read = true where id = ?", bytes(sent.getId()));
		batches.recordDeliveryResults(List.of(NotificationDeliveryResult.sent(sent, NOW),
				NotificationDeliveryResult.failed(failed, NOW.plusSeconds(10), "NOTIFICATION-005")), NOW.plusSeconds(60));
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT'",
				bytes(job))).isEqualTo(1);
		clock.set(NOW.plusSeconds(10));
		NotificationDelivery current = batches.claimNextDeliveries(clock.instant(), 100).getFirst();
		assertThat(current.getAttemptCount()).isEqualTo(2);
		// 새 복구 구간의 같은 누적 차수도 이전 기준 차수의 결과와 구별한다.
		jdbc.update("update notifications set delivery_attempt_base = 1 where id = ?", bytes(current.getId()));
		batches.recordDeliveryResults(List.of(NotificationDeliveryResult.failed(sent, null, "NOTIFICATION-006"),
				NotificationDeliveryResult.sent(failed, clock.instant()), NotificationDeliveryResult.sent(current, clock.instant())), NOW.plusSeconds(60));
		assertThat(count("select count(*) from notifications where id = ? and mock_delivery_status = 'SENT' and is_read",
				bytes(sent.getId()))).isEqualTo(1);
		assertThat(count("select count(*) from notifications where id = ? and mock_delivery_status = 'PENDING'"
				+ " and delivery_attempt_count = 2 and delivery_attempt_base = 1", bytes(current.getId()))).isEqualTo(1);
		NotificationDelivery recovered = new NotificationDelivery(current.getId(), current.getUserId(), current.getTitle(),
				current.getBody(), current.getLinkUrl(), 2, 1);
		batches.recordDeliveryResults(List.of(NotificationDeliveryResult.sent(recovered, clock.instant())), NOW.plusSeconds(60));
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT'",
				bytes(job))).isEqualTo(2);
		assertThat(timestamp("select mock_sent_at from notifications where id = ?", bytes(sent.getId()))).isEqualTo(NOW);
	}

	/** JDBC 쓰기가 끝난 뒤 오류가 나도 Service 경계가 전체 결과를 롤백하는지 확인한다. */
	@Test
	@DisplayName("묶음 결과 기록 오류는 전체 롤백하고 같은 결과를 재저장할 수 있다")
	void rollsBackFailedResultBatchBeforeRetry() {
		UUID job = service.register(request("result-rollback", NOW, List.of(USER, OTHER)));
		assertThat(service.processNextJob()).isTrue();
		List<NotificationDeliveryResult> results = batches.claimNextDeliveries(NOW, 100).stream()
				.map(delivery -> NotificationDeliveryResult.sent(delivery, NOW)).toList();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new TransientDataAccessResourceException("synthetic result write failure");
		}).when(repository).recordDeliveryResults(any(), anyInt());
		assertThatThrownBy(() -> batches.recordDeliveryResults(results, NOW.plusSeconds(60)))
				.isInstanceOf(TransientDataAccessResourceException.class);
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'PENDING'"
				+ " and mock_sent_at is null", bytes(job))).isEqualTo(2);
		doAnswer(invocation -> invocation.callRealMethod()).when(repository).recordDeliveryResults(any(), anyInt());
		batches.recordDeliveryResults(results, NOW.plusSeconds(60));
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT'",
				bytes(job))).isEqualTo(2);
	}

	/** beforeCommit의 deadline 거절이 실제 MySQL 결과 쓰기도 롤백하는지 확인한다. */
	@Test
	@DisplayName("결과 쓰기 후 임대가 만료되면 실제 DB의 발송 상태도 롤백한다")
	void rollsBackResultsWhenDeadlineExpiresBeforeCommit() {
		UUID job = service.register(request("result-commit-timeout", NOW, List.of(USER, OTHER)));
		assertThat(service.processNextJob()).isTrue();
		List<NotificationDeliveryResult> results = batches.claimNextDeliveries(NOW, 100).stream()
				.map(delivery -> NotificationDeliveryResult.sent(delivery, NOW)).toList();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			clock.set(NOW.plusSeconds(60));
			return null;
		}).when(repository).recordDeliveryResults(any(), anyInt());

		assertThatThrownBy(() -> batches.recordDeliveryResults(results, NOW.plusSeconds(60)))
				.isInstanceOf(QueryTimeoutException.class);
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'PENDING'"
				+ " and mock_sent_at is null", bytes(job))).isEqualTo(2);
	}

	/** 실제 잠긴 행의 UPDATE가 임대 예산의 JDBC timeout으로 취소되는지 CI의 MySQL에서 검증한다. */
	@Test
	@DisplayName("행 잠금이 풀리지 않아도 결과 배치는 SQL 제한 시간에 취소되고 기존 상태를 보존한다")
	void cancelsBlockedResultBatchWithinRemainingLease() throws Exception {
		UUID job = service.register(request("result-timeout", NOW, List.of(USER)));
		assertThat(service.processNextJob()).isTrue();
		NotificationDelivery delivery = batches.claimNextDeliveries(NOW, 100).getFirst();
		List<NotificationDeliveryResult> results = List.of(NotificationDeliveryResult.sent(delivery, NOW));
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				jdbc.queryForObject("select delivery_attempt_count from notifications where id = ? for update",
						Integer.class, bytes(delivery.getId()));
				locked.countDown();
				try {
					if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("잠금 해제 대기 시간 초과");
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("잠금 대기 중단", failure);
				}
			}));
			try {
				assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
				Future<?> recording = executor.submit(() -> batches.recordDeliveryResults(results, NOW.plusSeconds(2)));
				assertThatThrownBy(() -> recording.get(8, TimeUnit.SECONDS))
						.isInstanceOf(java.util.concurrent.ExecutionException.class)
						.hasCauseInstanceOf(QueryTimeoutException.class);
				assertThat(blocker).isNotDone();
			} finally {
				release.countDown();
			}
			blocker.get(10, TimeUnit.SECONDS);
		}
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'PENDING'"
				+ " and mock_sent_at is null", bytes(job))).isEqualTo(1);
		batches.recordDeliveryResults(results, NOW.plusSeconds(60));
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT'",
				bytes(job))).isEqualTo(1);
	}

	/** 검증 시나리오: 만료된 선점은 복구하고 이전 처리자의 생성·완료·실패 저장을 차단한다. */
	@Test
	@DisplayName("만료된 선점은 복구하고 이전 처리자의 생성·완료·실패 저장을 차단한다")
	void recoversExpiredJobAndFencesOldWorker() {
		// given
		NotificationJobRequest request = request("lease", NOW, List.of(USER));
		UUID id = service.register(request);
		NotificationJob old = repository.claimNextJob(NOW).orElseThrow();
		clock.set(NOW.plusSeconds(60));
		NotificationJob current = repository.claimNextJob(clock.instant()).orElseThrow();

		// when / then
		assertThat(current.getAttemptCount()).isEqualTo(2);
		assertThat(repository.createNotification(old, request, USER, clock.instant())).isFalse();
		repository.completeJob(old, clock.instant());
		repository.failJob(old, null, "NOTIFICATION-006");
		assertThat(jobStatus(id)).isEqualTo("PROCESSING");
		assertThat(repository.createNotification(current, request, USER, clock.instant())).isTrue();
		repository.completeJob(current, clock.instant());
		assertThat(jobStatus(id)).isEqualTo("COMPLETED");
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
	}

	/** 검증 시나리오: 서비스 실행 중 마지막 생성 이후 제한 시각을 지나도 완료를 확정하고 알림을 중복 생성하지 않는다. */
	@ParameterizedTest
	@ValueSource(ints = {59, 60, 61})
	@DisplayName("서비스 실행 중 마지막 생성 이후 제한 시각을 지나도 완료를 확정하고 알림을 중복 생성하지 않는다")
	void completesJobThroughServiceAcrossLeaseBoundary(int elapsedSeconds) {
		// given
		UUID id = service.register(request("service-slow-completion", NOW, List.of(USER)));
		doAnswer(invocation -> {
			// 실제 DB 생성과 선점 연장이 끝난 뒤 서비스의 완료 기록 전에 시각을 진행시킨다.
			boolean created = (boolean) invocation.callRealMethod();
			if (created) {
				clock.set(NOW.plusSeconds(elapsedSeconds));
			}
			return created;
		}).when(repository).createNotifications(any(), any(), any(), any());

		// when
		assertThat(service.processNextJob()).isTrue();

		// then
		assertThat(clock.instant()).isEqualTo(NOW.plusSeconds(elapsedSeconds));
		assertThat(jobStatus(id)).isEqualTo("COMPLETED");
		assertThat(timestamp("select completed_at from notification_jobs where id = ?", bytes(id))).isEqualTo(clock.instant());
		assertThat(count("select attempt_count from notification_jobs where id = ?", bytes(id))).isEqualTo(1);
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
		assertThat(service.processNextJob()).isFalse();
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
	}

	/** 검증 시나리오: 첫·마지막 생성 시도도 재선점 전이면 제한 시각 이후 완료를 확정하고 재처리하지 않는다. */
	@ParameterizedTest
	@CsvSource({"1, 60", "1, 61", "4, 60", "4, 61"})
	@DisplayName("첫·마지막 생성 시도도 재선점 전이면 제한 시각 이후 완료를 확정하고 재처리하지 않는다")
	void completesExpiredJobBeforeReclaim(int attemptCount, int elapsedSeconds) {
		// given
		NotificationJobRequest request = request("slow-completion", NOW, List.of(USER));
		UUID id = service.register(request);
		for (int attempt = 1; attempt < attemptCount; attempt++) {
			repository.claimNextJob(clock.instant()).orElseThrow();
			clock.set(clock.instant().plusSeconds(60));
		}
		NotificationJob job = repository.claimNextJob(clock.instant()).orElseThrow();
		assertThat(repository.createNotification(job, request, USER, clock.instant())).isTrue();
		clock.set(clock.instant().plusSeconds(elapsedSeconds));

		// when
		repository.completeJob(job, clock.instant());

		// then
		assertThat(jobStatus(id)).isEqualTo("COMPLETED");
		assertThat(timestamp("select completed_at from notification_jobs where id = ?", bytes(id))).isEqualTo(clock.instant());
		assertThat(timestamp("select lease_until from notification_jobs where id = ?", bytes(id))).isNull();
		assertThat(count("select attempt_count from notification_jobs where id = ?", bytes(id))).isEqualTo(attemptCount);
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
		assertThat(service.processNextJob()).isFalse();
	}

	/** 검증 시나리오: 계속 중단된 생성 작업도 선점 4회 후에는 무한 반복하지 않고 최종 실패로 남긴다. */
	@Test
	@DisplayName("계속 중단된 생성 작업도 선점 4회 후에는 무한 반복하지 않고 최종 실패로 남긴다")
	void stopsAfterFourAbandonedClaims() {
		// given
		UUID id = service.register(request("abandoned", NOW, List.of(USER)));

		// when
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(repository.claimNextJob(clock.instant()).orElseThrow().getAttemptCount()).isEqualTo(attempt);
			clock.set(clock.instant().plusSeconds(60));
		}

		// then
		assertThat(repository.claimNextJob(clock.instant())).isEmpty();
		assertThat(jobStatus(id)).isEqualTo("FAILED");
		assertThat(count("select attempt_count from notification_jobs where id = ?", bytes(id))).isEqualTo(4);
	}

	/** 검증 시나리오: 소진된 생성 선점은 기존 오류를 보존하고 기록이 없을 때만 기본 오류를 남긴다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"NOTIFICATION-006"})
	@DisplayName("소진된 생성 선점은 기존 오류를 보존하고 기록이 없을 때만 기본 오류를 남긴다")
	void preservesGenerationErrorOnExhaustion(String previousError) {
		// given
		UUID id = service.register(request("job-error-preservation", NOW, List.of(USER)));
		for (int attempt = 1; attempt <= 4; attempt++) {
			NotificationJob job = repository.claimNextJob(clock.instant()).orElseThrow();
			if (attempt < 4) {
				repository.failJob(job, clock.instant(), previousError);
			}
		}
		clock.set(NOW.plusSeconds(60));

		// when
		assertThat(repository.claimNextJob(clock.instant())).isEmpty();

		// then
		assertThat(jobStatus(id)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select last_error from notification_jobs where id = ?", String.class, bytes(id)))
				.isEqualTo(previousError == null ? NotificationProcessingErrorCode.TEMPORARY_FAILURE.getCode() : previousError);
	}

	/** 검증 시나리오: 소진된 발송 선점은 기존 오류를 보존하고 기록이 없을 때만 기본 오류를 남긴다. */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"NOTIFICATION-006"})
	@DisplayName("소진된 발송 선점은 기존 오류를 보존하고 기록이 없을 때만 기본 오류를 남긴다")
	void preservesDeliveryErrorOnExhaustion(String previousError) {
		// given
		UUID id = service.register(request("delivery-error-preservation", NOW, List.of(USER)));
		service.processNextJob();
		UUID notification = notificationId(id, USER);
		for (int attempt = 1; attempt <= 4; attempt++) {
			NotificationDelivery delivery = repository.claimNextDelivery(clock.instant()).orElseThrow();
			if (attempt < 4) {
				repository.failDelivery(delivery, clock.instant(), previousError);
			}
		}
		clock.set(NOW.plusSeconds(60));

		// when
		assertThat(repository.claimNextDelivery(clock.instant())).isEmpty();

		// then
		assertThat(count("select count(*) from notifications where id = ? and mock_delivery_status = 'FAILED' and is_read = false",
				bytes(notification))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select last_delivery_error from notifications where id = ?", String.class, bytes(notification)))
				.isEqualTo(previousError == null ? NotificationProcessingErrorCode.TEMPORARY_FAILURE.getCode() : previousError);
	}

	/** 검증 시나리오: 소진된 생성 선점을 최종 실패로 남기고 같은 호출에서 다음 작업을 처리한다. */
	@Test
	@DisplayName("소진된 생성 선점을 최종 실패로 남기고 같은 호출에서 다음 작업을 처리한다")
	void processesJobAfterExhaustedClaim() {
		// given
		UUID exhausted = service.register(request("exhausted-job", NOW, List.of(USER)));
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(repository.claimNextJob(clock.instant()).orElseThrow().getAttemptCount()).isEqualTo(attempt);
			clock.set(clock.instant().plusSeconds(60));
		}
		UUID next = service.register(request("next-job", clock.instant(), List.of(USER)));

		// when
		assertThat(service.processNextJob()).isTrue();

		// then
		assertThat(jobStatus(exhausted)).isEqualTo("FAILED");
		assertThat(jobStatus(next)).isEqualTo("COMPLETED");
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(next))).isEqualTo(1);
		assertThat(service.processNextJob()).isFalse();
	}

	/** 검증 시나리오: 소진된 발송 선점을 최종 실패로 남기고 같은 호출에서 다음 발송을 처리한다. */
	@Test
	@DisplayName("소진된 발송 선점을 최종 실패로 남기고 같은 호출에서 다음 발송을 처리한다")
	void processesDeliveryAfterExhaustedClaim() {
		// given
		UUID exhausted = service.register(request("exhausted-delivery", NOW, List.of(USER)));
		service.processNextJob();
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(repository.claimNextDelivery(clock.instant()).orElseThrow().getAttemptCount()).isEqualTo(attempt);
			clock.set(clock.instant().plusSeconds(60));
		}
		UUID next = service.register(request("next-delivery", clock.instant(), List.of(USER)));
		service.processNextJob();

		// when
		assertThat(service.processNextDelivery()).isTrue();

		// then
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'FAILED' and next_delivery_attempt_at is null",
				bytes(exhausted))).isEqualTo(1);
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT' and is_read = false",
				bytes(next))).isEqualTo(1);
		assertThat(service.processNextDelivery()).isFalse();
	}

	/** 검증 시나리오: 모의 발송만 10초·30초·60초 재시도하며 4번째 실패 이후 읽음·생성 결과를 유지한다. */
	@Test
	@DisplayName("모의 발송만 10초·30초·60초 재시도하며 4번째 실패 이후 읽음·생성 결과를 유지한다")
	void exhaustsOnlyDeliveryRetriesWithoutChangingReadState() {
		// given
		UUID id = service.register(request("delivery-fail", NOW, List.of(USER)));
		service.processNextJob();
		UUID notification = notificationId(id, USER);
		jdbc.update("update notifications set is_read = true where id = ?", bytes(notification));
		when(sender.send(any())).thenThrow(new NotificationProcessingException(NotificationProcessingErrorCode.TEMPORARY_FAILURE));
		int[] delays = {10, 30, 60};

		// when / then
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(service.processNextDelivery()).isTrue();
			assertThat(count("select delivery_attempt_count from notifications where id = ?", bytes(notification))).isEqualTo(attempt);
			if (attempt < 4) {
				Instant next = clock.instant().plusSeconds(delays[attempt - 1]);
				assertThat(timestamp("select next_delivery_attempt_at from notifications where id = ?", bytes(notification))).isEqualTo(next);
				assertThat(service.processNextDelivery()).isFalse();
				clock.set(next);
			}
		}
		assertThat(timestamp("select next_delivery_attempt_at from notifications where id = ?", bytes(notification))).isNull();
		assertThat(jobStatus(id)).isEqualTo("COMPLETED");
		assertThat(count("select attempt_count from notification_jobs where id = ?", bytes(id))).isEqualTo(1);
		assertThat(count("select count(*) from notifications where id = ? and is_read = true and mock_delivery_status = 'FAILED'",
				bytes(notification))).isEqualTo(1);
		clock.set(clock.instant().plusSeconds(1000));
		assertThat(service.processNextDelivery()).isFalse();
	}

	/** 검증 시나리오: 발송 실패 후 성공해도 같은 알림만 갱신하고 미읽음을 유지한다. */
	@Test
	@DisplayName("발송 실패 후 성공해도 같은 알림만 갱신하고 미읽음을 유지한다")
	void succeedsAfterDeliveryRetryWithoutDuplicatingNotification() {
		// given
		UUID id = service.register(request("delivery-retry", NOW, List.of(USER)));
		service.processNextJob();
		UUID notification = notificationId(id, USER);
		when(sender.send(any())).thenReturn(MockDeliveryStatus.FAILED, MockDeliveryStatus.SENT);

		// when
		service.processNextDelivery();
		clock.set(NOW.plusSeconds(10));
		service.processNextDelivery();

		// then
		assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
		assertThat(count("select count(*) from notifications where id = ? and is_read = false and mock_delivery_status = 'SENT'",
				bytes(notification))).isEqualTo(1);
		assertThat(count("select delivery_attempt_count from notifications where id = ?", bytes(notification))).isEqualTo(2);
	}

	/** 검증 시나리오: 발송 제한 시각 이후라도 재선점 전 성공은 저장하고 다시 발송하지 않는다. */
	@ParameterizedTest
	@ValueSource(ints = {60, 61})
	@DisplayName("발송 제한 시각 이후라도 재선점 전 성공은 저장하고 다시 발송하지 않는다")
	void completesExpiredDeliveryBeforeReclaim(int elapsedSeconds) {
		// given
		UUID id = service.register(request("slow-delivery", NOW, List.of(USER)));
		service.processNextJob();
		UUID notification = notificationId(id, USER);
		when(sender.send(any())).thenAnswer(invocation -> {
			clock.set(NOW.plusSeconds(elapsedSeconds));
			return MockDeliveryStatus.SENT;
		});

		// when
		assertThat(service.processNextDelivery()).isTrue();

		// then
		assertThat(count("select count(*) from notifications where id = ? and mock_delivery_status = 'SENT' and is_read = false",
				bytes(notification))).isEqualTo(1);
		assertThat(timestamp("select mock_sent_at from notifications where id = ?", bytes(notification)))
				.isEqualTo(clock.instant());
		assertThat(timestamp("select next_delivery_attempt_at from notifications where id = ?", bytes(notification))).isNull();
		assertThat(count("select delivery_attempt_count from notifications where id = ?", bytes(notification))).isEqualTo(1);
		assertThat(service.processNextDelivery()).isFalse();
	}

	/** 검증 시나리오: 발송 선점도 장애 후 복구하며 이전 발송 차수의 늦은 결과 저장을 차단한다. */
	@Test
	@DisplayName("발송 선점도 장애 후 복구하며 이전 발송 차수의 늦은 결과 저장을 차단한다")
	void recoversDeliveryAndFencesOldAttempt() {
		// given
		UUID id = service.register(request("delivery-lease", NOW, List.of(USER)));
		service.processNextJob();
		NotificationDelivery old = repository.claimNextDelivery(NOW).orElseThrow();
		clock.set(NOW.plusSeconds(60));
		NotificationDelivery current = repository.claimNextDelivery(clock.instant()).orElseThrow();

		// when / then
		repository.completeDelivery(old, clock.instant());
		repository.failDelivery(old, null, "NOTIFICATION-006");
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'PENDING'", bytes(id))).isEqualTo(1);
		repository.completeDelivery(current, clock.instant());
		assertThat(count("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT' and is_read = false", bytes(id))).isEqualTo(1);
	}

	/** 검증 시나리오: 동일 발생 건의 동시 등록과 두 처리자의 실행은 작업·사용자 알림을 한 건씩만 만든다. */
	@Test
	@DisplayName("동일 발생 건의 동시 등록과 두 처리자의 실행은 작업·사용자 알림을 한 건씩만 만든다")
	void concurrentRegistrationAndWorkersRemainIdempotent() throws Exception {
		// given
		NotificationJobRequest request = request("concurrent", NOW, List.of(USER));
		CountDownLatch start = new CountDownLatch(1);
		List<Future<UUID>> tasks = new ArrayList<>();

		// when
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int count = 0; count < 8; count++) {
				tasks.add(executor.submit(() -> {
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return service.register(request);
				}));
			}
			start.countDown();
			UUID id = tasks.getFirst().get(15, TimeUnit.SECONDS);
			for (Future<UUID> task : tasks) {
				assertThat(task.get(15, TimeUnit.SECONDS)).isEqualTo(id);
			}
			Future<Boolean> first = executor.submit(service::processNextJob);
			Future<Boolean> second = executor.submit(service::processNextJob);
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
			assertThat(jobStatus(id)).isEqualTo("COMPLETED");
			assertThat(count("select count(*) from notifications where job_id = ?", bytes(id))).isEqualTo(1);
			assertThat(count("select attempt_count from notification_jobs where id = ?", bytes(id))).isEqualTo(1);
		}
	}

	/** 검증 시나리오: 다른 트랜잭션이 잠근 작업은 기다리지 않고 다음 작업을 선점한다. */
	@Test
	@DisplayName("다른 트랜잭션이 잠근 작업은 기다리지 않고 다음 작업을 선점한다")
	void skipsLockedJob() throws Exception {
		// given
		UUID first = service.register(request("locked", NOW, List.of(USER)));
		UUID next = service.register(request("unlocked", NOW, List.of(OTHER)));
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		// when / then
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> lock = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				jdbc.queryForList("select id from notification_jobs where id = ? for update", bytes(first));
				locked.countDown();
				try {
					if (!release.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("잠금 해제 대기 시간 초과");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return null;
			}));
			try {
				assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(repository.claimNextJob(NOW).orElseThrow().getId()).isEqualTo(next);
			} finally {
				release.countDown();
			}
			lock.get(15, TimeUnit.SECONDS);
		}
	}

	/** 검증 시나리오: 문구의 따옴표·줄바꿈과 null 링크는 JSON 저장 후에도 보존한다. */
	@Test
	@DisplayName("문구의 따옴표·줄바꿈과 null 링크는 JSON 저장 후에도 보존한다")
	void preservesEscapedTextAndNullableLinks() {
		// given / when
		NotificationJobRequest request = new NotificationJobRequest(prefix + "json", NotificationType.RESULT_CHANGED,
				null, null, "제목 \"따옴표\"", "내용\n다음 줄\\경로", null, NOW, List.of(USER));
		UUID id = service.register(request);

		// then
		assertThat(repository.findRequest(id)).isEqualTo(request);
		service.processNextJob();
		assertThat(jdbc.queryForObject("select body from notifications where job_id = ?", String.class, bytes(id))).isEqualTo(request.getBody());
		assertThat(jdbc.queryForObject("select link_url from notifications where job_id = ?", String.class, bytes(id))).isNull();
	}

	/** 검증 시나리오: 손상된 기존 작업 입력은 무한 선점하지 않고 입력 오류로 최종 실패 기록한다. */
	@Test
	@DisplayName("손상된 기존 작업 입력은 무한 선점하지 않고 입력 오류로 최종 실패 기록한다")
	void recordsMalformedStoredPayloadAsFinalFailure() {
		// given
		UUID id = service.register(request("corrupt", NOW, List.of(USER)));
		jdbc.update("update notification_jobs set payload = '{}' where id = ?", bytes(id));

		// when / then
		assertThat(service.processNextJob()).isTrue();
		assertThat(jobStatus(id)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select last_error from notification_jobs where id = ?", String.class, bytes(id))).isEqualTo("NOTIFICATION-003");
		assertThat(service.processNextJob()).isFalse();
	}

	/** 각 시나리오에서 발생 키와 수신 대상을 지정할 유효한 알림 작업 입력을 만든다. */
	private NotificationJobRequest request(String suffix, Instant scheduled, List<UUID> users) {
		return new NotificationJobRequest(prefix + suffix, NotificationType.RESULT_CHANGED,
				null, null, "제목", "내용", null, scheduled, users);
	}

	/** 테스트 작업의 최종 상태를 실제 DB에서 확인한다. */
	private String jobStatus(UUID id) {
		return jdbc.queryForObject("select status from notification_jobs where id = ?", String.class, bytes(id));
	}

	/** 작업과 수신자에 연결된 알림 ID를 찾아 후속 상태 검증에 사용한다. */
	private UUID notificationId(UUID job, UUID user) {
		return jdbc.queryForObject("select id from notifications where job_id = ? and user_id = ?",
				(row, index) -> {
					ByteBuffer value = ByteBuffer.wrap(row.getBytes("id"));
					return new UUID(value.getLong(), value.getLong());
				}, bytes(job), bytes(user));
	}

	/** 검증 대상 행의 수 또는 정수 값을 실제 테스트 DB에서 조회한다. */
	private long count(String sql, Object... parameters) {
		return jdbc.queryForObject(sql, Long.class, parameters);
	}

	/** 테스트 DB에 저장된 시각을 UTC로 읽어 선점·재시도 경계를 비교한다. */
	private Instant timestamp(String sql, Object... parameters) {
		LocalDateTime value = jdbc.queryForObject(sql, LocalDateTime.class, parameters);
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}

	/** DB 시각 비교에 사용할 UTC 날짜·시각을 만든다. */
	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/** DB의 BINARY(16) 식별자와 비교할 수 있도록 UUID를 바이트 배열로 변환한다. */
	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@ComponentScan(basePackages = {"com.getddo.core.notification", "com.getddo.db.notification",
			"com.getddo.core.audit", "com.getddo.db.audit.mapper"})
	@EntityScan(basePackages = {"com.getddo.db.notification", "com.getddo.db.audit"})
	@EnableJpaRepositories(basePackages = {"com.getddo.db.notification", "com.getddo.db.audit"})
	@Import({JpaAuditingConfig.class, MySqlTestConfiguration.class,
			com.getddo.db.audit.repository.AuditLogRepositoryImpl.class})
	static class TestApplication {
		/** 테스트가 선점과 재시도 경계 시각을 직접 제어할 수 있는 시계를 제공한다. */
		@Bean
		MutableClock clock() {
			return new MutableClock(NOW);
		}

		/** 업무 시각 조회가 테스트 시계와 같은 값을 사용하도록 연결한다. */
		@Bean
		TimeProvider timeProvider(Clock clock) {
			return new TimeProvider(clock);
		}
	}
}
