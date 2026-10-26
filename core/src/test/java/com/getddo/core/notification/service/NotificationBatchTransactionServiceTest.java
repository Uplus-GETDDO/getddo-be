package com.getddo.core.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.notification.domain.NotificationDelivery;
import com.getddo.core.notification.domain.NotificationDeliveryResult;
import com.getddo.core.notification.repository.NotificationJobRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 결과 기록의 절대 임대 예산과 트랜잭션 종료 경계를 DB 없이 검증한다. */
class NotificationBatchTransactionServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
	private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	private final NotificationJobRepository repository = mock(NotificationJobRepository.class);
	private final RecordingTransactionManager manager = new RecordingTransactionManager();
	private final List<NotificationDeliveryResult> results = List.of(NotificationDeliveryResult.sent(
			new NotificationDelivery(UUID.randomUUID(), UUID.randomUUID(), "제목", "내용", null, 1), NOW));
	private NotificationBatchTransactionService service;

	/** 실행 중의 시간 경과를 제어하는 업무 시계와 실제 Spring 동기화 생명주기를 준비한다. */
	@BeforeEach
	void setUp() {
		Clock clock = mock(Clock.class);
		when(clock.instant()).thenAnswer(invocation -> now.get());
		service = new NotificationBatchTransactionService(repository, manager, new TimeProvider(clock));
	}

	/** 남은 초를 내림하고 내부 5초 상한을 적용하는지 검증한다. */
	@ParameterizedTest
	@CsvSource({"60000,5", "3900,3", "1000,1"})
	@DisplayName("새 결과 트랜잭션과 SQL은 남은 임대와 5초 중 작은 제한을 사용한다")
	void boundsTransactionAndSqlTimeout(long remainingMillis, int timeout) {
		service.recordDeliveryResults(results, NOW.plusMillis(remainingMillis));

		assertThat(manager.definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		assertThat(manager.definition.getTimeout()).isEqualTo(timeout);
		verify(repository).recordDeliveryResults(results, timeout);
		assertThat(manager.commits).isEqualTo(1);
		assertThat(manager.rollbacks).isZero();
	}

	/** 연결 획득에 사용한 시간도 원래 deadline에서 차감한다. */
	@Test
	@DisplayName("트랜잭션 획득 후 남은 임대 시간으로 SQL 제한을 다시 계산한다")
	void recalculatesAfterTransactionAcquisition() {
		manager.onBegin = () -> now.set(NOW.plusMillis(2_100));
		service.recordDeliveryResults(results, NOW.plusSeconds(4));

		assertThat(manager.definition.getTimeout()).isEqualTo(4);
		verify(repository).recordDeliveryResults(results, 1);
	}

	/** 1초 미만·이미 지난 deadline에는 새 트랜잭션도 만들지 않는다. */
	@ParameterizedTest
	@ValueSource(longs = {-1000, 0, 999})
	@DisplayName("남은 임대가 1초 미만이면 트랜잭션 생성 전에 거절한다")
	void rejectsInsufficientTimeBeforeStarting(long remainingMillis) {
		assertThatThrownBy(() -> service.recordDeliveryResults(results, NOW.plusMillis(remainingMillis)))
				.isInstanceOf(TransactionTimedOutException.class);
		assertThat(manager.definition).isNull();
		verifyNoInteractions(repository);
	}

	/** 트랜잭션 획득 중 시간이 소진되면 SQL을 실행하지 않고 새 트랜잭션을 롤백한다. */
	@Test
	@DisplayName("트랜잭션 획득 중 임대가 끝나면 SQL 없이 롤백한다")
	void rejectsExpiredDeadlineAfterAcquisition() {
		manager.onBegin = () -> now.set(NOW.plusSeconds(60));
		assertThatThrownBy(() -> service.recordDeliveryResults(results, NOW.plusSeconds(60)))
				.isInstanceOf(TransactionTimedOutException.class);
		verifyNoInteractions(repository);
		assertThat(manager.rollbacks).isEqualTo(1);
	}

	/** SQL 이후 만료도 commit 직전 검사로 결과 저장을 롤백한다. */
	@Test
	@DisplayName("결과 쓰기 후 선점 시간이 끝났으면 커밋하지 않는다")
	void rollsBackWhenDeadlineExpiresBeforeCommit() {
		doAnswer(invocation -> { now.set(NOW.plusSeconds(60)); return null; })
				.when(repository).recordDeliveryResults(any(), anyInt());
		assertThatThrownBy(() -> service.recordDeliveryResults(results, NOW.plusSeconds(60)))
				.isInstanceOf(QueryTimeoutException.class);
		assertThat(manager.commits).isZero();
		assertThat(manager.rollbacks).isEqualTo(1);
	}

	/** 묶음 기록 오류는 이전과 같이 전체 롤백하며 같은 메서드 안에서 재시도하지 않는다. */
	@Test
	@DisplayName("DB 기록 오류는 한 번 롤백하고 호출자에게 전달한다")
	void rollsBackFailedBatch() {
		doThrow(new TransientDataAccessResourceException("synthetic write failure"))
				.when(repository).recordDeliveryResults(results, 5);
		assertThatThrownBy(() -> service.recordDeliveryResults(results, NOW.plusSeconds(60)))
				.isInstanceOf(TransientDataAccessResourceException.class);
		verify(repository).recordDeliveryResults(results, 5);
		assertThat(manager.commits).isZero();
		assertThat(manager.rollbacks).isEqualTo(1);
	}

	/** 실제 DB 대신 Spring의 begin·동기화·commit·rollback 순서만 실행하는 대역이다. */
	private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
		private TransactionDefinition definition;
		private Runnable onBegin = () -> { };
		private int commits;
		private int rollbacks;

		/** 독립 트랜잭션의 상태를 만든다. */
		@Override protected Object doGetTransaction() { return new Object(); }
		/** 전달된 제한을 보존하고 연결 획득 시간을 재현한다. */
		@Override protected void doBegin(Object transaction, TransactionDefinition definition) {
			this.definition = definition;
			onBegin.run();
		}
		/** 실제 Spring beforeCommit이 통과한 호출만 센다. */
		@Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
		/** 실패한 신규 트랜잭션 종료를 센다. */
		@Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
	}
}
