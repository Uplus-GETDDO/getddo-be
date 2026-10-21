package com.getddo.db.drawing;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawExecutionState;
import com.getddo.core.drawing.domain.DrawFailure;
import com.getddo.core.drawing.domain.DrawFailureCode;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.repository.DrawFailureRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.drawing.service.DrawConfirmationService;
import com.getddo.core.drawing.service.DrawExecutionControlService;
import com.getddo.core.drawing.service.DrawExecutionService;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.core.drawing.service.DrawingSelectionService;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.service.EventStatusChangeService;
import com.getddo.db.drawing.entity.DrawRunEntity;
import com.getddo.db.drawing.repository.DrawResultJpaRepository;
import com.getddo.db.drawing.repository.DrawRunJpaRepository;
import com.getddo.db.ticket.MutableClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.getddo.core.drawing.exception.DrawingErrorCode.NOT_EXECUTABLE;
import static com.getddo.db.common.util.UuidBinary.toBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = DrawSnapshotIntegrationTest.TestApplication.class,
		properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class DrawExecutionIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	@Autowired private DrawExecutionService executions;
	@Autowired private DrawExecutionControlService control;
	@Autowired private DrawSnapshotService snapshots;
	@Autowired private DrawConfirmationService confirmation;
	@Autowired private DrawSnapshotRepository inputs;
	@Autowired private DrawFailureRepository failures;
	@Autowired private DrawRunJpaRepository runs;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager manager;
	@Autowired private MutableClock clock;
	@MockitoSpyBean private DrawResultJpaRepository results;
	@MockitoSpyBean private DrawingSelectionService selection;
	@MockitoSpyBean private EventStatusChangeService eventStatuses;
	private UUID eventId, prizeId, adminId;

	/** 임시 MySQL에 최초 실행용 관리자·이벤트·경품을 준비하고 고정 시각과 대역 호출 이력을 초기화한다. */
	@BeforeEach void setUp() {
		clock.set(NOW);
		eventId = UUID.randomUUID(); prizeId = UUID.randomUUID(); adminId = user("ADMIN");
		jdbc.update("""
			insert into events (id, title, description, image_key, event_type, weighting_enabled,
			starts_at, ends_at, status, membership_rule, created_at, updated_at)
			values (?, '공통 실행 검증', '설명', 'test.png', 'NO_TICKET', false, ?, ?, 'CLOSED', 'vip', ?, ?)
			""", toBytes(eventId), utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(300)), utc(NOW), utc(NOW));
		jdbc.update("""
			insert into event_prizes (id, event_id, prize_rank, name, winner_count, created_at, updated_at)
			values (?, ?, 1, '경품', 3, ?, ?)
			""", toBytes(prizeId), toBytes(eventId), utc(NOW), utc(NOW));
	}

	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"SCHEDULED", "OPEN", "CLOSED"})
	@DisplayName("마감 1분 경계와 취소·삭제·등록 여부를 조회에서 구분한다")
	void pollsOnlyUnregisteredEligibleEvents(EventStatus initialStatus) {
		// given
		jdbc.update("update events set status = ? where id = ?", initialStatus.name(), toBytes(eventId));
		jdbc.update("update events set ends_at = ? where id = ?", utc(NOW.minusSeconds(600)), toBytes(eventId));
		clock.set(NOW.minusSeconds(540).minusNanos(1000));
		// when / then
		assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
		assertThatThrownBy(() -> snapshots.registerInitial(eventId)).isInstanceOf(BusinessException.class);
		clock.set(NOW.minusSeconds(540));
		assertThat(snapshots.findInitialDue()).contains(eventId);
		jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(eventId));
		assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
		assertThatThrownBy(() -> snapshots.registerInitial(eventId)).isInstanceOf(BusinessException.class);
		jdbc.update("update events set status = ?, deleted_at = ? where id = ?", initialStatus.name(), utc(NOW), toBytes(eventId));
		assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
		assertThatThrownBy(() -> snapshots.registerInitial(eventId)).isInstanceOf(BusinessException.class);
		jdbc.update("update events set deleted_at = null where id = ?", toBytes(eventId));
		UUID runId = snapshots.registerInitial(eventId);
		assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
		assertThat(control.findDue()).contains(runId);
	}

	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"SCHEDULED", "OPEN", "CLOSED"})
	@DisplayName("동시 최초 등록은 하나의 실행을 만들고 등록 후 중단은 같은 실행으로 복구한다")
	void concurrentRegistrationAndRecovery(EventStatus initialStatus) throws Exception {
		// given
		jdbc.update("update events set status = ? where id = ?", initialStatus.name(), toBytes(eventId));
		participant(user("USER"));
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<UUID> first = pool.submit(() -> { start.await(); return snapshots.registerInitial(eventId); });
			Future<UUID> second = pool.submit(() -> { start.await(); return snapshots.registerInitial(eventId); });
			// when
			start.countDown();
			UUID runId = first.get(15, TimeUnit.SECONDS);
			// then
			assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(runId);
			assertThat(control.findDue()).contains(runId);
			assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
			assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, toBytes(eventId)))
					.isEqualTo("DRAW_CONFIRMED");
			List<DrawResult> stored = confirmation.confirm(runId).getResults();
			assertThat(executions.execute(runId).getConfirmation().getResults()).extracting(DrawResult::getId)
					.containsExactlyElementsOf(stored.stream().map(DrawResult::getId).toList());
			assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
			assertThat(control.findDue()).doesNotContain(runId);
			assertThat(jdbc.queryForObject("select count(*) from draw_runs where event_id = ?", Long.class, toBytes(eventId)))
					.isEqualTo(1);
			assertThat(jdbc.queryForObject("select count(*) from draw_publications where event_id = ?", Long.class, toBytes(eventId)))
					.isZero();
			assertThat(jdbc.queryForObject("select count(*) from notification_jobs where event_id = ?", Long.class, toBytes(eventId)))
					.isZero();
		}
	}

	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"SCHEDULED", "OPEN", "CLOSED"})
	@DisplayName("응모자 없음 종료는 이벤트와 함께 커밋되고 다시 선정하지 않는다")
	void noEntriesTerminatesEventOnce(EventStatus initialStatus) {
		// given
		jdbc.update("update events set status = ? where id = ?", initialStatus.name(), toBytes(eventId));
		UUID runId = snapshots.registerInitial(eventId);
		// when
		assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.NO_ENTRIES);
		// then
		assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, toBytes(eventId)))
				.isEqualTo("NO_ENTRANTS");
		assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.NO_ENTRIES);
		assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
		assertThat(control.findDue()).doesNotContain(runId);
		assertThat(countResults(runId)).isZero();
		verifyNoInteractions(selection);
	}

	@ParameterizedTest
	@CsvSource({"CLOSED,DRAW_CONFIRMED,true", "CLOSED,NO_ENTRANTS,false",
			"SCHEDULED,DRAW_CONFIRMED,true", "SCHEDULED,NO_ENTRANTS,false"})
	@DisplayName("최초 확정·응모자 없음 상태 변경 실패는 저장을 롤백하고 운영자 대기를 남긴다")
	void stateChangeFailureRollsBackInitialTermination(EventStatus initialStatus, EventStatus terminalStatus, boolean hasEntrants) {
		// given
		jdbc.update("update events set status = ? where id = ?", initialStatus.name(), toBytes(eventId));
		if (hasEntrants) participant(user("USER"));
		UUID runId = snapshots.registerInitial(eventId);
		new TransactionTemplate(manager).executeWithoutResult(status ->
			doThrow(new IllegalStateException()).when(eventStatuses).changeStatus(eventId,
					initialStatus, terminalStatus));
		// when
		assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.FAILED);
		// then
		assertThat(countResults(runId)).isZero();
		if (!hasEntrants) assertThat(runs.findById(runId).orElseThrow().getSnapshotFixedAt()).isNull();
		assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, toBytes(eventId)))
				.isEqualTo(initialStatus.name());
		assertThat(runs.findById(runId).orElseThrow().getRetryAt()).isNull();
		assertThat(snapshots.findInitialDue()).doesNotContain(eventId);
		assertThat(control.findDue()).doesNotContain(runId);
	}

	@Test @DisplayName("폴링은 과거 누락 이벤트를 마감 순으로 최대 20건 조회한다")
	void boundsPollingBatchWithoutLosingOlderEvents() {
		// given
		List<UUID> expected = new ArrayList<>();
		for (int index = 0; index < 21; index++) {
			UUID id = UUID.randomUUID();
			expected.add(id);
			jdbc.update("""
				insert into events (id, title, description, image_key, event_type, weighting_enabled,
				starts_at, ends_at, status, membership_rule, created_at, updated_at)
				values (?, '누락 탐색', '설명', 'test.png', 'NO_TICKET', false, ?, ?, 'CLOSED', 'vip', ?, ?)
				""", toBytes(id), utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(900 - index)), utc(NOW), utc(NOW));
		}
		clock.set(NOW.minusSeconds(400));
		try {
			// when / then
			assertThat(snapshots.findInitialDue()).containsExactlyElementsOf(expected.subList(0, 20));
			jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(expected.getFirst()));
			assertThat(snapshots.findInitialDue()).containsExactlyElementsOf(expected.subList(1, 21));
		} finally {
			for (UUID id : expected) jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(id));
		}
	}

	/** 선정 롤백 뒤 실패·예약이 별도 확정되고 같은 고정 입력으로 재시도한다. */
	@Test @DisplayName("선정 롤백 뒤 실패·예약이 별도 확정되고 같은 고정 입력으로 재시도한다")
	void recordsFailureAndReusesCommittedSnapshot() {
		// given
		participant(user("USER"));
		UUID runId = snapshots.registerInitial(eventId);
		doThrow(new TransientDataAccessResourceException("temporary diagnostic")).when(results).saveAllAndFlush(anyList());
		// when
		DrawExecutionResult failed = executions.execute(runId);
		// then
		assertThat(failed.getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(failures.count(runId)).isEqualTo(1);
		assertThat(failures.findLatest(runId).orElseThrow().getFailureCode()).isEqualTo("TRANSIENT_STORAGE");
		assertThat(countResults(runId)).isZero();
		DrawSnapshot fixed = inputs.findRegistered(runId).orElseThrow();
		assertThat(fixed.fixedAt()).isNotNull();
		assertThat(fixed.candidates()).hasSize(1);
		jdbc.update("update event_participants set used_ticket_count = 7 where event_id = ?", toBytes(eventId));
		reset(results);
		clock.set(NOW.plusSeconds(9));
		assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(failures.count(runId)).isEqualTo(1);
		clock.set(NOW.plusSeconds(10));
		DrawExecutionResult confirmed = executions.execute(runId);
		assertThat(confirmed.getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		DrawSnapshot after = inputs.findRegistered(runId).orElseThrow();
		assertThat(after.fixedAt()).isEqualTo(fixed.fixedAt());
		assertThat(after.rules()).isEqualTo(fixed.rules());
		assertThat(after.candidates()).isEqualTo(fixed.candidates());
		assertThat(failures.count(runId)).isEqualTo(1);
	}

	/** 네 번의 실제 실패 뒤 자동 대기를 유지하고 운영자만 같은 실행을 재개한다. */
	@Test @DisplayName("네 번의 실제 실패 뒤 자동 대기를 유지하고 운영자만 같은 실행을 재개한다")
	void exhaustsPersistedRetriesAndResumesSameRun() {
		// given
		participant(user("USER"));
		UUID runId = snapshots.registerInitial(eventId);
		doThrow(new TransientDataAccessResourceException("temporary diagnostic")).when(results).saveAllAndFlush(anyList());
		// when
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.FAILED);
			assertThat(failures.count(runId)).isEqualTo(attempt);
			Instant retryAt = runs.findById(runId).orElseThrow().getRetryAt();
			if (attempt < 4) { assertThat(retryAt).isNotNull(); clock.set(retryAt); }
			else assertThat(retryAt).isNull();
		}
		// then
		DrawSnapshot fixed = inputs.findRegistered(runId).orElseThrow();
		reset(results);
		clock.set(NOW.plusSeconds(200));
		assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(failures.count(runId)).isEqualTo(4);
		DrawExecutionResult resumed = executions.resumeInitial(runId, adminId);
		assertThat(resumed.getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		assertThat(resumed.getRunId()).isEqualTo(runId);
		assertThat(inputs.findRegistered(runId).orElseThrow().candidates()).isEqualTo(fixed.candidates());
		assertThat(failures.count(runId)).isEqualTo(4);
		assertThat(jdbc.queryForObject("select count(*) from draw_runs where event_id = ?", Long.class, toBytes(eventId))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = 'DRAW_RESUME' and actor_id = ?",
				Long.class, toBytes(runId), toBytes(adminId))).isEqualTo(1);
	}

	/** 입력 확정 전 정합성 실패는 등록 실행과 실패만 보존하고 자동으로 우회하지 않는다. */
	@Test @DisplayName("입력 확정 전 정합성 실패는 등록 실행과 실패만 보존하고 자동으로 우회하지 않는다")
	void preservesRegistrationWhenPreparationFails() {
		// given
		participant(user("USER"));
		jdbc.update("update event_participants set used_ticket_count = 7 where event_id = ?", toBytes(eventId));
		UUID runId = snapshots.registerInitial(eventId);
		// when
		DrawExecutionResult failed = executions.execute(runId);
		// then
		assertThat(failed.getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(failures.findLatest(runId).orElseThrow().getFailureCode()).isEqualTo("INVALID_EVIDENCE");
		assertThat(runs.findById(runId).orElseThrow().getSnapshotFixedAt()).isNull();
		assertThat(runs.findById(runId).orElseThrow().getRetryAt()).isNull();
		assertThat(inputs.findRegistered(runId).orElseThrow().candidates()).isEmpty();
		assertThat(executions.execute(runId).getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(failures.count(runId)).isEqualTo(1);
		assertThat(countResults(runId)).isZero();
	}

	/** 만료 시도 복구 뒤 409를 반환해도 실제 MySQL에 실패와 운영자 대기가 남는다. */
	@Test @DisplayName("만료 시도 복구 뒤 409를 반환해도 실제 MySQL에 실패와 운영자 대기가 남는다")
	void commitsInterruptedFailureBeforeManualRejection() {
		// given
		UUID runId = snapshots.registerInitial(eventId);
		DrawExecutionControlService.Claim first = control.claim(runId, null, false, false);
		jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(eventId));
		clock.set(NOW.plusSeconds(301));
		// when / then
		assertThatThrownBy(() -> executions.resumeInitial(runId, adminId)).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.getErrorCode()).isSameAs(NOT_EXECUTABLE));
		assertThat(failures.count(runId)).isEqualTo(1);
		assertThat(failures.findLatest(runId).orElseThrow().getId()).isEqualTo(first.getState().getAttemptId());
		assertThat(failures.findLatest(runId).orElseThrow().getFailureCode()).isEqualTo("INTERRUPTED");
		DrawRunEntity stored = runs.findById(runId).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(stored.getRetryAt()).isNull();
		assertThat(control.findDue()).doesNotContain(runId);
		assertThat(countResults(runId)).isZero();
	}

	/** 같은 실패 ID는 한 번만 저장하고 다른 실패는 별도 행으로 집계한다. */
	@Test @DisplayName("같은 실패 ID는 한 번만 저장하고 다른 실패는 별도 행으로 집계한다")
	void preservesIdempotentFailureHistory() {
		// given
		UUID runId = snapshots.registerInitial(eventId);
		DrawFailure first = new DrawFailure(UUID.randomUUID(), runId, "INVALID_RUN", NOW, null);
		DrawFailure second = new DrawFailure(UUID.randomUUID(), runId, "INVALID_EVIDENCE", NOW.plusSeconds(1), null);
		TransactionTemplate tx = new TransactionTemplate(manager);
		// when
		tx.executeWithoutResult(status -> {
			inputs.lockEvent(eventId).orElseThrow(); failures.append(first); failures.append(first); failures.append(second);
		});
		// then
		assertThat(failures.count(runId)).isEqualTo(2);
		assertThat(failures.findLatest(runId).orElseThrow().getId()).isEqualTo(second.getId());
	}

	/** 같은 실패 저장의 동시 요청은 한 행과 한 번의 예약만 확정한다. */
	@Test @DisplayName("같은 실패 저장의 동시 요청은 한 행과 한 번의 예약만 확정한다")
	void concurrentFailureStorageIsIdempotent() throws Exception {
		// given
		UUID runId = snapshots.registerInitial(eventId);
		DrawExecutionControlService.Claim owned = control.claim(runId, null, false, false);
		UUID token = owned.getState().getAttemptId();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DrawExecutionState> first = pool.submit(() -> {
				start.await(); return control.recordFailure(runId, token, DrawFailureCode.TRANSIENT_STORAGE, NOW);
			});
			Future<DrawExecutionState> second = pool.submit(() -> {
				start.await(); return control.recordFailure(runId, token, DrawFailureCode.TRANSIENT_STORAGE, NOW);
			});
			// when
			start.countDown();
			assertThat(first.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(DrawRunStatus.FAILED);
			assertThat(second.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(DrawRunStatus.FAILED);
			// then
			assertThat(failures.count(runId)).isEqualTo(1);
			assertThat(runs.findById(runId).orElseThrow().getRetryAt()).isEqualTo(NOW.plusSeconds(10));
		} finally { pool.shutdownNow(); }
	}

	/** 같은 등록 실행의 동시 요청도 선정·저장 결과를 한 번만 확정한다. */
	@Test @DisplayName("같은 등록 실행의 동시 요청도 선정·저장 결과를 한 번만 확정한다")
	void concurrentExecutionConfirmsOnce() throws Exception {
		// given
		participant(user("USER"));
		UUID runId = snapshots.registerInitial(eventId);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DrawExecutionResult> first = pool.submit(() -> { start.await(); return executions.execute(runId); });
			Future<DrawExecutionResult> second = pool.submit(() -> { start.await(); return executions.execute(runId); });
			// when
			start.countDown();
			assertThat(first.get(20, TimeUnit.SECONDS).getStatus()).isIn(DrawRunStatus.PREPARING, DrawRunStatus.RUNNING, DrawRunStatus.READY, DrawRunStatus.CONFIRMED);
			assertThat(second.get(20, TimeUnit.SECONDS).getStatus()).isIn(DrawRunStatus.PREPARING, DrawRunStatus.RUNNING, DrawRunStatus.READY, DrawRunStatus.CONFIRMED);
			// then
			assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
			assertThat(countResults(runId)).isEqualTo(3);
			verify(selection, times(1)).select(anyList(), anyList(), anyBoolean());
		} finally { pool.shutdownNow(); }
	}

	/** 재추첨은 실제 취소 자리만 채우고 유지 결과와 확정 재요청을 보존한다. */
	@Test @DisplayName("재추첨은 실제 취소 자리만 채우고 유지 결과와 확정 재요청을 보존한다")
	void confirmsReplacementWithoutChangingRetainedAwards() {
		// given
		for (int index = 0; index < 4; index++) participant(user("USER"));
		DrawSnapshot original = snapshots.prepareInitial(eventId);
		DrawConfirmation initial = confirmation.confirm(original.runId());
		UUID replacementId = replacement(original, initial);
		// when
		DrawExecutionResult result = executions.execute(replacementId);
		// then
		assertThat(result.getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		assertThat(result.getConfirmation().getResults()).hasSize(1);
		assertThat(result.getConfirmation().getResults().getFirst().getSelection().getSlotNumber()).isEqualTo(3);
		assertThat(result.getConfirmation().getResults().getFirst().getSelection().getResultType()).isEqualTo(DrawSelectionResultType.SELECTED);
		assertThat(countResults(original.runId())).isEqualTo(3);
		clearInvocations(selection);
		DrawExecutionResult replay = executions.execute(replacementId);
		assertThat(replay.getConfirmation().getResults().stream().map(DrawResult::getId).toList())
				.isEqualTo(result.getConfirmation().getResults().stream().map(DrawResult::getId).toList());
		verifyNoInteractions(selection);
	}

	/** 낙첨 후보 없는 재추첨도 빈 자리만 UNFILLED로 확정한다. */
	@Test @DisplayName("낙첨 후보 없는 재추첨도 빈 자리만 UNFILLED로 확정한다")
	void confirmsUnfilledReplacementWithNoCandidates() {
		// given
		for (int index = 0; index < 3; index++) participant(user("USER"));
		DrawSnapshot original = snapshots.prepareInitial(eventId);
		DrawConfirmation initial = confirmation.confirm(original.runId());
		UUID replacementId = replacement(original, initial);
		clearInvocations(selection);
		// when
		DrawExecutionResult result = executions.execute(replacementId);
		// then
		assertThat(result.getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		DrawSelection unfilled = result.getConfirmation().getResults().getFirst().getSelection();
		assertThat(unfilled.getSlotNumber()).isEqualTo(3);
		assertThat(unfilled.getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
		assertThat(unfilled.getUserId()).isNull();
		verifyNoInteractions(selection);
	}

	/** GD-138의 등록·취소·입력 준비를 테스트 SQL로 대역한다. 실제 GD-138 서비스 검증은 아니다. */
	private UUID replacement(DrawSnapshot original, DrawConfirmation initial) {
		clock.set(NOW.plusSeconds(1));
		TransactionTemplate tx = new TransactionTemplate(manager);
		return tx.execute(status -> {
			inputs.lockEvent(eventId).orElseThrow();
			DrawRunEntity run = runs.saveAndFlush(new DrawRunEntity(eventId, 1, DrawType.REPLACEMENT));
			DrawResult canceled = initial.getResults().stream().filter(result -> result.getSelection().getSlotNumber() == 3).findFirst().orElseThrow();
			jdbc.update("insert into award_cancellations (id, draw_result_id, draw_run_id, canceled_by, reason, canceled_at) values (?, ?, ?, ?, '검증 취소', ?)",
					toBytes(UUID.randomUUID()), toBytes(canceled.getId()), toBytes(run.getId()), toBytes(adminId), utc(NOW.plusSeconds(1)));
			Set<UUID> previousWinners = initial.getResults().stream().map(result -> result.getSelection().getUserId()).collect(Collectors.toSet());
			List<DrawSnapshot.Candidate> remaining = original.candidates().stream().filter(candidate -> !previousWinners.contains(candidate.userId())).toList();
			List<DrawSnapshot.RetainedAward> retained = initial.getResults().stream().filter(result -> !result.getId().equals(canceled.getId()))
					.map(result -> new DrawSnapshot.RetainedAward(result.getId(), result.getSelection().getUserId(), prizeId, result.getSelection().getSlotNumber())).toList();
			DrawSnapshot.Rules old = original.rules();
			DrawSnapshot.Rules rules = new DrawSnapshot.Rules(old.supportsPlatinum() ? 4 : 2, old.weightingEnabled(), old.eventType(), old.membershipRule(),
					old.bronzeWeight(), old.silverWeight(), old.goldWeight(), old.platinumWeight(), old.prizes(), original.runId(),
					List.of(new DrawSnapshot.Slot(prizeId, 1, 3)), retained);
			inputs.saveRegisteredReplacement(new DrawSnapshot(run.getId(), eventId, DrawRunStatus.READY, NOW.plusSeconds(1),
					DrawSnapshotService.ALGORITHM_VERSION, rules, remaining, DrawType.REPLACEMENT));
			jdbc.update("update events set status = 'REDRAWING' where id = ?", toBytes(eventId));
			return run.getId();
		});
	}
	/** 실행·권한 검증에 사용할 역할별 활성 사용자를 임시 DB에 등록한다. */
	private UUID user(String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, name, role, status, membership, created_at, updated_at) values (?, '실행 검증', ?, 'ACTIVE', 'VIP', ?, ?)",
				toBytes(id), role, utc(NOW), utc(NOW)); return id;
	}
	/** 응모권을 사용하지 않는 유효 응모자의 참여·접수 근거를 임시 DB에 등록한다. */
	private void participant(UUID userId) {
		UUID participantId = UUID.randomUUID();
		jdbc.update("insert into event_participants (id, event_id, user_id, used_ticket_count, created_at) values (?, ?, ?, 0, ?)",
				toBytes(participantId), toBytes(eventId), toBytes(userId), utc(NOW.minusSeconds(301)));
		jdbc.update("insert into event_entries (id, event_id, participant_id, user_id, requested_ticket_count, deducted_ticket_count, created_at) values (?, (select event_id from event_participants where id = ?), ?, ?, 0, 0, ?)",
				toBytes(UUID.randomUUID()), toBytes(participantId), toBytes(participantId), toBytes(userId), utc(NOW.minusSeconds(301)));
	}
	/** 같은 실행의 결과가 중복 확정되거나 부분 저장되지 않았는지 확인할 행 수를 조회한다. */
	private long countResults(UUID runId) { return jdbc.queryForObject("select count(*) from draw_results where draw_run_id = ?", Long.class, toBytes(runId)); }
	/** 고정 Instant를 JDBC 저장에 사용할 UTC 시각으로 변환한다. */
	private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
