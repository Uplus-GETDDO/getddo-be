package com.getddo.db.drawing;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawPublicationConfirmation;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.domain.DrawResultConfirmation;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.service.DrawConfirmationService;
import com.getddo.core.drawing.service.DrawPublicationService;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.core.drawing.service.DrawResultConfirmationService;
import com.getddo.db.drawing.repository.DrawPublicationRepositoryImpl;
import com.getddo.db.drawing.repository.DrawPublicationResultJpaRepository;
import com.getddo.db.drawing.repository.DrawPublicationJpaRepository;
import com.getddo.db.drawing.repository.DrawResultConfirmationJpaRepository;
import com.getddo.db.drawing.entity.DrawPublicationEntity;
import com.getddo.db.drawing.entity.DrawResultConfirmationEntity;
import com.getddo.db.ticket.LockWaitProbe;
import com.getddo.db.ticket.MutableClock;

import static com.getddo.db.common.util.UuidBinary.toBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 최초 결과는 실제 GD-84·85 서비스로 준비·확정한다. 취소는 저장 이력 검증용 SQL fixture다. */
@SpringBootTest(classes = DrawSnapshotIntegrationTest.TestApplication.class,
		properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class DrawPublicationIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	@Autowired private DrawPublicationService service;
	@Autowired private DrawResultConfirmationService adminPublications;
	@Autowired private DrawSnapshotService snapshots;
	@Autowired private DrawConfirmationService confirmation;
	@Autowired private MutableClock clock;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MySQLContainer mysql;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager entityManager;
	@Autowired private DrawPublicationJpaRepository versions;
	@Autowired private DrawResultConfirmationJpaRepository confirmations;
	@MockitoSpyBean private DrawPublicationRepositoryImpl repository;
	@MockitoSpyBean private DrawPublicationResultJpaRepository entries;
	private UUID eventId, userId, prizeId;
	private DrawSnapshot snapshot;
	private DrawConfirmation confirmed;

	/** MySQL 저장 fixture와 공개 서비스를 준비한다. 전체 취소·실행 연결 검증과 구분한다. */
	@BeforeEach
	void setUp() {
		clock.set(NOW);
		userId = UUID.randomUUID(); eventId = UUID.randomUUID(); prizeId = UUID.randomUUID();
		jdbc.update("""
			insert into users (id, name, role, status, membership, created_at, updated_at)
			values (?, '공개 테스트', 'ADMIN', 'ACTIVE', 'VIP', ?, ?)
			""", toBytes(userId), utc(NOW), utc(NOW));
		jdbc.update("""
			insert into events (id, title, description, image_key, event_type, weighting_enabled,
			starts_at, ends_at, status, membership_rule, created_at, updated_at)
			values (?, '공개 테스트', '설명', 'test.png', 'NO_TICKET', false, ?, ?, 'CLOSED', 'vip', ?, ?)
			""", toBytes(eventId), utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(300)), utc(NOW), utc(NOW));
		jdbc.update("""
			insert into event_prizes (id, event_id, prize_rank, name, winner_count, created_at, updated_at)
			values (?, ?, 1, '경품', 2, ?, ?)
			""", toBytes(prizeId), toBytes(eventId), utc(NOW), utc(NOW));
		UUID participantId = UUID.randomUUID();
		jdbc.update("insert into event_participants (id, event_id, user_id, used_ticket_count, created_at) values (?, ?, ?, 0, ?)",
				toBytes(participantId), toBytes(eventId), toBytes(userId), utc(NOW.minusSeconds(301)));
		jdbc.update("""
			insert into event_entries (id, event_id, participant_id, user_id, requested_ticket_count, deducted_ticket_count, created_at)
			values (?, (select event_id from event_participants where id = ?), ?, ?, 0, 0, ?)
			""", toBytes(UUID.randomUUID()), toBytes(participantId), toBytes(participantId), toBytes(userId), utc(NOW.minusSeconds(301)));
		snapshot = snapshots.prepareInitial(eventId);
		confirmed = confirmation.confirm(snapshot.runId());
	}

	/** 실제 MySQL에 확인을 저장하고 새 영속성 컨텍스트에서 DB 시각을 다시 읽는다. */
	@ParameterizedTest
	@CsvSource({
			"2026-10-23T01:12:00.123456000Z, 2026-10-23T01:12:00.123456Z",
			"2026-10-23T01:12:00.123456100Z, 2026-10-23T01:12:00.123456Z",
			"2026-10-23T01:12:00.123456800Z, 2026-10-23T01:12:00.123456Z",
			"2026-10-23T01:12:00.999999800Z, 2026-10-23T01:12:00.999999Z"
	})
	@DisplayName("MySQL 저장·재조회 후 확인 시각은 입력 시각을 넘지 않고 마이크로초로 보존된다")
	void storesAndReloadsMicrosecondConfirmation(String requestedTime, String expectedTime) {
		// given
		Instant requestedAt = Instant.parse(requestedTime);
		Instant expectedAt = Instant.parse(expectedTime);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		// when: 저장 트랜잭션을 끝낸 다음 별도 트랜잭션에서 실제 DB 값을 읽는다.
		DrawResultConfirmation saved = transaction.execute(status ->
				repository.saveConfirmation(snapshot.runId(), userId, requestedAt));
		assertThat(saved).isNotNull();
		DrawResultConfirmation reloaded = transaction.execute(status -> {
			entityManager.clear();
			return confirmations.findById(saved.getId()).orElseThrow().toDomain();
		});

		// then
		assertThat(reloaded).isEqualTo(saved);
		assertThat(reloaded.getRunId()).isEqualTo(snapshot.runId());
		assertThat(reloaded.getConfirmedBy()).isEqualTo(userId);
		assertThat(reloaded.getConfirmedAt()).isEqualTo(expectedAt).isBeforeOrEqualTo(requestedAt);
		assertThat(confirmationCount()).isEqualTo(1);
	}

	/** 실제 최초 확정 결과를 공개하고 JPA 왕복·DB 시각·반복 공개를 보존한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("실제 최초 확정 결과를 공개하고 JPA 왕복·DB 시각·반복 공개를 보존한다")
	void publishesAndReplaysStoredVersion() {
		// given
		clock.set(NOW.plusNanos(123456789));
		UUID selectedId = selectedId();
		UUID unfilledId = confirmed.getResults().stream()
				.filter(result -> result.getSelection().getResultType() == DrawSelectionResultType.UNFILLED)
				.findFirst().orElseThrow().getId();
		assertThat(service.wasPublished(selectedId)).isFalse();
		// when
		DrawPublication first = service.publishInitial(eventId).orElseThrow();
		clock.set(NOW.plusSeconds(90));
		DrawPublication replay = service.publishInitial(eventId).orElseThrow();
		// then
		assertThat(first.getId().version()).isEqualTo(7);
		assertThat(first.getPublishedAt().getNano() % 1000).isZero();
		assertThat(first.getConfirmedBy()).isNull();
		assertThat(first.getEntries()).containsExactly(new DrawPublication.Entry(selectedId, snapshot.runId(), null));
		assertThat(replay).isEqualTo(first);
		assertThat(service.history(eventId)).containsExactly(first);
		assertThat(service.wasPublished(selectedId)).isTrue();
		assertThat(service.wasPublished(unfilledId)).isFalse();
		assertThat(count()).isEqualTo(1);
	}

	/** 목록 저장 실패는 공개 버전도 롤백하고 같은 최초 공개를 재시도한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("목록 저장 실패는 공개 버전도 롤백하고 같은 최초 공개를 재시도한다")
	void rollsBackVersionWhenEntriesFail() {
		// given
		doThrow(new IllegalStateException("공개 목록 저장 실패")).when(entries).saveAllAndFlush(any());
		// when / then
		assertThatThrownBy(() -> service.publishInitial(eventId))
				.isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class);
		assertThat(count()).isZero();
		assertThat(service.wasPublished(selectedId())).isFalse();
		// Spring Data 인터페이스 spy의 실제 위임을 복원한다. 추상 메서드에 callRealMethod를 지정하지 않는다.
		reset(entries);
		assertThat(service.publishInitial(eventId).orElseThrow().getRevision()).isEqualTo(1);
		assertThat(count()).isEqualTo(1);
	}

	/** 공개 후 취소되어도 실제 공개 기록은 true이고 원본 버전은 보존된다는 시나리오를 검증한다. */
	@Test
	@DisplayName("공개 후 취소되어도 실제 공개 기록은 true이고 원본 버전은 보존된다")
	void preservesHistoryAfterCancellation() {
		// given
		DrawPublication first = service.publishInitial(eventId).orElseThrow();
		// when
		cancelSelected();
		// then
		assertThat(service.wasPublished(selectedId())).isTrue();
		assertThat(service.history(eventId)).containsExactly(first);
		assertThat(service.publishInitial(eventId)).contains(first);
		assertThat(count()).isEqualTo(1);
	}

	/** 최초 공개 전 취소 기록은 공개 이력으로 계산하지 않고 전체 최초 공개를 지연한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("최초 공개 전 취소 기록은 공개 이력으로 계산하지 않고 전체 최초 공개를 지연한다")
	void delaysPublicationForCancellationFixture() {
		// given
		cancelSelected();
		// when / then
		assertThat(service.publishInitial(eventId)).isEmpty();
		assertThat(service.wasPublished(selectedId())).isFalse();
		assertThat(count()).isZero();
	}

	/** 재추첨 확인·미충원 갱신 저장 fixture에서 확인 시각·공개 시각·이전 공개를 구분한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("재추첨 확인·미충원 갱신 저장 fixture에서 확인 시각·공개 시각·이전 공개를 구분한다")
	void mapsConfirmationAndUpdateHistoryFixture() {
		// given: GD-87·138의 실제 등록·재추첨·확인 서비스 연결을 검증하는 테스트가 아니다.
		DrawPublication first = service.publishInitial(eventId).orElseThrow();
		cancelSelected();
		UUID replacementRun = UUID.randomUUID();
		jdbc.update("""
			insert into draw_runs (id, event_id, run_number, draw_type, status, algorithm_version,
			rules_snapshot, snapshot_fixed_at, confirmed_at, created_at)
			select ?, event_id, 1, 'REPLACEMENT', 'CONFIRMED', algorithm_version,
			rules_snapshot, snapshot_fixed_at, confirmed_at, created_at from draw_runs where id = ?
			""", toBytes(replacementRun), toBytes(snapshot.runId()));
		Instant checkedAt = NOW.plusSeconds(1), publishedAt = NOW.plusSeconds(2);
		DrawResultConfirmation checked = new TransactionTemplate(transactionManager).execute(status -> {
			DrawResultConfirmationEntity saved = confirmations.saveAndFlush(
					new DrawResultConfirmationEntity(replacementRun, userId, checkedAt));
			UUID confirmationId = saved.getId();
			entityManager.detach(saved);
			return confirmations.findById(confirmationId).orElseThrow().toDomain();
		});
		// 저장 fixture를 배치 조회해 JDBC IN 구문과 JPA 확인 일괄 조회의 실제 매핑도 검증한다.
		List<DrawPublicationRun> replacement = repository.findReplacementRuns(eventId);
		assertThat(replacement).hasSize(1);
		assertThat(replacement.getFirst().getRunId()).isEqualTo(replacementRun);
		assertThat(replacement.getFirst().getConfirmation()).isEqualTo(checked);
		assertThat(replacement.getFirst().getResults()).isEmpty();
		// when: 보충 후보 없음으로 공개 목록이 비는 버전의 저장 매핑을 확인한다.
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			versions.saveAndFlush(new DrawPublicationEntity(new DrawPublication(UUID.randomUUID(), eventId,
					replacementRun, 2, DrawPublicationType.UPDATE, "미충원 저장 fixture", userId,
					checkedAt, publishedAt, List.of())));
		});
		// then
		assertThat(checked.getId().version()).isEqualTo(7);
		assertThat(confirmations.findByRunId(replacementRun).orElseThrow().toDomain()).isEqualTo(checked);
		List<DrawPublication> history = service.history(eventId);
		assertThat(history).hasSize(2);
		assertThat(history.getFirst()).isEqualTo(first);
		assertThat(history.getLast().getConfirmedAt()).isEqualTo(checkedAt);
		assertThat(history.getLast().getPublishedAt()).isEqualTo(publishedAt);
		assertThat(history.getLast().getReason()).isEqualTo("미충원 저장 fixture");
		assertThat(service.wasPublished(selectedId())).isTrue();
		assertThat(service.changes(eventId).getLast().getChanges()).containsExactly(
				new DrawPublicationChange.SlotChange(prizeId, 1, selectedId(), userId, null, null));
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				confirmations.saveAndFlush(new DrawResultConfirmationEntity(replacementRun, userId, checkedAt))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(confirmations.findByRunId(replacementRun).orElseThrow().toDomain()).isEqualTo(checked);
	}

	/** 실제 이벤트 잠금 경합에서도 동시 최초 공개는 동일한 한 버전만 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("실제 이벤트 잠금 경합에서도 동시 최초 공개는 동일한 한 버전만 반환한다")
	void concurrentRequestsPublishOnce() throws Exception {
		// given
		CountDownLatch saved = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(invocation -> {
			DrawPublication publication = (DrawPublication) invocation.callRealMethod();
			saved.countDown();
			if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("공개 트랜잭션 해제 대기 실패");
			return publication;
		}).when(repository).saveInitial(eq(eventId), any(), anyList(), any());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// when
			Future<DrawPublication> first = executor.submit(() -> service.publishInitial(eventId).orElseThrow());
			assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
			Future<DrawPublication> second = executor.submit(() -> service.publishInitial(eventId).orElseThrow());
			new LockWaitProbe(mysql).awaitLockWaits(1);
			release.countDown();
			// then
			assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(first.get(30, TimeUnit.SECONDS));
			assertThat(count()).isEqualTo(1);
			verify(repository, times(1)).saveInitial(eq(eventId), any(), anyList(), any());
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	/** 실제 DB에서 최초 발표와 재시도가 확인·공개·알림을 각각 한 건만 남기는지 검증한다. */
	@Test
	@DisplayName("정상 최초 명단은 관리자 발표 요청 한 번으로 확인·상태·공개·알림을 함께 저장한다")
	void adminPublishesInitialAndReplayDoesNotDuplicateAnything() {
		// given
		assertThat(count()).isZero();
		assertThat(confirmationCount()).isZero();
		// when
		DrawPublicationConfirmation first = adminPublications.confirmBeforeInitial(snapshot.runId(), userId);
		clock.set(NOW.plusSeconds(60));
		DrawPublicationConfirmation replay = adminPublications.confirmBeforeInitial(snapshot.runId(), userId);
		// then
		assertThat(first.getPublication().getConfirmedBy()).isEqualTo(userId);
		assertThat(first.getPublication().getConfirmedAt()).isEqualTo(NOW);
		assertThat(replay.getPublication()).isEqualTo(first.getPublication());
		assertThat(replay.getConfirmation()).isEqualTo(first.getConfirmation());
		assertThat(count()).isEqualTo(1);
		assertThat(confirmationCount()).isEqualTo(1);
		assertThat(jobCount()).isEqualTo(1);
		assertThat(eventStatus()).isEqualTo("PUBLISHED");
		assertThat(service.changes(eventId).getFirst().getExecutions()).singleElement()
				.satisfies(evidence -> assertThat(evidence.getRunId()).isEqualTo(snapshot.runId()));
	}

	/** 공개 근거 저장 실패가 신규 확인과 공개를 함께 롤백하며 재시도 가능한지 검증한다. */
	@Test
	@DisplayName("공개 근거 저장 실패 시 신규 확인·공개·알림은 남지 않고 상태도 확정 상태를 유지한다")
	void adminPublicationRollsBackConfirmationAndPublicationTogether() {
		// given
		doThrow(new IllegalStateException("실행 근거 저장 실패"))
				.when(repository).saveExecutionEvidence(any(), eq(eventId), anyList());
		// when / then
		assertThatThrownBy(() -> adminPublications.confirmBeforeInitial(snapshot.runId(), userId))
				.isInstanceOf(RuntimeException.class);
		assertThat(count()).isZero();
		assertThat(confirmationCount()).isZero();
		assertThat(jobCount()).isZero();
		assertThat(eventStatus()).isEqualTo("DRAW_CONFIRMED");
		reset(repository);
		assertThat(adminPublications.confirmBeforeInitial(snapshot.runId(), userId).getPublication()).isNotNull();
	}

	/** GD-196 상태 전환이 실패하면 같은 요청에서 저장한 공개와 확인도 롤백되는지 검증한다. */
	@Test
	@DisplayName("상태 변경 계약 실패도 같은 요청의 확인·공개·알림을 모두 롤백한다")
	void statusConflictRollsBackNewPublication() {
		// given: 저장 후 상태를 바꿔 실제 GD-196 예상 상태 불일치를 발생시킨다.
		doAnswer(invocation -> {
			invocation.callRealMethod();
			jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(eventId));
			return null;
		}).when(repository).saveExecutionEvidence(any(), eq(eventId), anyList());
		// when / then
		assertThatThrownBy(() -> adminPublications.confirmBeforeInitial(snapshot.runId(), userId))
				.isInstanceOf(BusinessException.class);
		assertThat(count()).isZero();
		assertThat(confirmationCount()).isZero();
		assertThat(jobCount()).isZero();
		assertThat(eventStatus()).isEqualTo("DRAW_CONFIRMED");
	}

	/** 취소 이벤트의 발표 요청이 새 확인·공개·알림을 남기지 않는지 검증한다. */
	@Test
	@DisplayName("취소 이벤트는 최초 발표 요청에서도 확인·공개·알림을 만들지 않는다")
	void rejectsCanceledEventBeforeSavingAdminConfirmation() {
		// given
		jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(eventId));
		// when / then
		assertThatThrownBy(() -> adminPublications.confirmBeforeInitial(snapshot.runId(), userId))
				.isInstanceOf(BusinessException.class);
		assertThat(count()).isZero();
		assertThat(confirmationCount()).isZero();
		assertThat(jobCount()).isZero();
	}

	/** 독립 요청의 실제 이벤트 잠금 경합 후 같은 공개 결과를 반환하는지 검증한다. */
	@Test
	@DisplayName("실제 이벤트 잠금에서 동시 발표 요청은 한 확인·한 공개·한 알림만 생성한다")
	void concurrentAdminPublicationUsesSameCommittedResult() throws Exception {
		// given
		CountDownLatch saved = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(invocation -> {
			DrawPublication publication = (DrawPublication) invocation.callRealMethod();
			saved.countDown();
			if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("발표 저장 대기 실패");
			return publication;
		}).when(repository).saveReviewedInitial(eq(eventId), any(), any(), any(), anyList(), any());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// when
			Future<DrawPublicationConfirmation> first = executor.submit(() -> adminPublications.confirmBeforeInitial(snapshot.runId(), userId));
			assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
			Future<DrawPublicationConfirmation> second = executor.submit(() -> adminPublications.confirmBeforeInitial(snapshot.runId(), userId));
			new LockWaitProbe(mysql).awaitLockWaits(1);
			release.countDown();
			// then
			assertThat(second.get(30, TimeUnit.SECONDS).getPublication()).isEqualTo(first.get(30, TimeUnit.SECONDS).getPublication());
			assertThat(count()).isEqualTo(1);
			assertThat(confirmationCount()).isEqualTo(1);
			assertThat(jobCount()).isEqualTo(1);
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	private long confirmationCount() {
		return jdbc.queryForObject("select count(*) from draw_result_confirmations c join draw_runs r on r.id = c.draw_run_id where r.event_id = ?", Long.class, toBytes(eventId));
	}

	private long jobCount() {
		return jdbc.queryForObject("select count(*) from notification_jobs where event_id = ?", Long.class, toBytes(eventId));
	}

	private String eventStatus() {
		return jdbc.queryForObject("select status from events where id = ?", String.class, toBytes(eventId));
	}

	/** 저장 fixture에서 선정 결과 식별자를 조회한다. */
	private UUID selectedId() {
		return confirmed.getResults().stream().filter(result -> result.getSelection().getResultType() == DrawSelectionResultType.SELECTED)
				.findFirst().orElseThrow().getId();
	}
	/** 실제 GD-138 서비스 연결 없이 과거 공개 이력 보존만 검증하는 fixture다. */
	private void cancelSelected() {
		jdbc.update("""
			insert into award_cancellations (id, draw_result_id, canceled_by, reason, canceled_at)
			values (?, ?, ?, '관리자 취소 저장 fixture', ?)
			""", toBytes(UUID.randomUUID()), toBytes(selectedId()), toBytes(userId), utc(NOW));
	}
	/** 저장 fixture 이벤트의 실제 공개 버전 수를 조회한다. */
	private long count() {
		return jdbc.queryForObject("select count(*) from draw_publications where event_id = ?", Long.class, toBytes(eventId));
	}
	/** 시험용 Instant를 DB fixture에 사용할 UTC LocalDateTime으로 변환한다. */
	private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
