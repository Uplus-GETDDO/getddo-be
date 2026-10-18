package com.getddo.db.drawing;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.exception.ErrorCode;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.drawing.domain.AwardCancellation;
import com.getddo.core.drawing.domain.AwardCancellationReceipt;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawFailure;
import com.getddo.core.drawing.domain.DrawHistoricalAward;
import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.repository.DrawFailureRepository;
import com.getddo.core.drawing.repository.DrawInspectionRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.drawing.service.AwardCancellationRegistrationService;
import com.getddo.core.drawing.service.AwardCancellationService;
import com.getddo.core.drawing.service.DrawConfirmationService;
import com.getddo.core.drawing.service.DrawInspectionService;
import com.getddo.core.drawing.service.DrawPublicationService;
import com.getddo.core.drawing.service.DrawResultConfirmationService;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.core.drawing.service.DrawingSelectionService;
import com.getddo.core.notification.service.MockNotificationSender;
import com.getddo.core.notification.service.NotificationJobService;
import com.getddo.core.user.repository.UserRepository;
import com.getddo.db.audit.repository.AuditLogRepositoryImpl;
import com.getddo.db.drawing.entity.DrawResultEntity;
import com.getddo.db.drawing.repository.AwardCancellationRepositoryImpl;
import com.getddo.db.drawing.repository.DrawPublicationRepositoryImpl;
import com.getddo.db.drawing.repository.DrawResultJpaRepository;
import com.getddo.db.drawing.repository.ReplacementDrawRepositoryImpl;
import com.getddo.db.notification.repository.NotificationJobRepositoryImpl;
import com.getddo.db.ticket.LockWaitProbe;
import com.getddo.db.ticket.MutableClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.mysql.MySQLContainer;
import static com.getddo.core.drawing.exception.AwardCancellationErrorCode.KEY_CONFLICT;
import static com.getddo.core.drawing.exception.AwardCancellationErrorCode.NOT_CANCELABLE;
import static com.getddo.core.drawing.exception.AwardCancellationErrorCode.NOT_FOUND;
import static com.getddo.core.drawing.exception.DrawingErrorCode.ADMIN_REQUIRED;
import static com.getddo.db.common.util.UuidBinary.fromBytes;
import static com.getddo.db.common.util.UuidBinary.toBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 취소·등록·준비·GD-87 실제 실행을 연결한다. 응모·보상 출처만 SQL 테스트 데이터다. */
@SpringBootTest(classes = DrawSnapshotIntegrationTest.TestApplication.class,
		properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class AwardCancellationFlowIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	@Autowired private AwardCancellationService service;
	@Autowired private AwardCancellationRegistrationService registration;
	@Autowired private DrawSnapshotService snapshotService;
	@Autowired private DrawConfirmationService confirmation;
	@Autowired private DrawSnapshotRepository snapshots;
	@Autowired private DrawFailureRepository failures;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MutableClock clock;
	@Autowired private MySQLContainer mysql;
	@Autowired private DrawPublicationService publications;
	@Autowired private DrawResultConfirmationService resultConfirmations;
	@Autowired private DrawInspectionService inspections;
	@Autowired private DrawInspectionRepository inspectionRecords;
	@Autowired private UserRepository users;
	@Autowired private NotificationJobService jobs;
	@MockitoSpyBean private NotificationJobRepositoryImpl jobRecords;
	@MockitoSpyBean private MockNotificationSender sender;
	@MockitoSpyBean private ReplacementDrawRepositoryImpl replacements;
	@MockitoSpyBean private AwardCancellationRepositoryImpl cancellations;
	@MockitoSpyBean private DrawResultJpaRepository results;
	@MockitoSpyBean private DrawingSelectionService selection;
	@MockitoSpyBean private AuditLogRepositoryImpl auditRecords;
	@MockitoSpyBean private DrawPublicationRepositoryImpl publicationRecords;
	private UUID actor;
	private Fixture fixture;

	@BeforeEach void setUp() {
		clock.set(NOW);
		actor = user("ADMIN");
		fixture = event(5, true);
		clearInvocations(selection);
	}

	@Test @DisplayName("실제 취소·재추첨은 유지 배정·최초 가중치·응모권을 보존하고 같은 키에 결과를 재반환한다")
	void executesAndReplaysActualReplacement() {
		// given
		DrawResult target = target(fixture);
		UUID key = UUID.randomUUID();
		List<Map<String, Object>> tickets = ticketRows(fixture.eventId());
		long histories = historyCount(fixture.eventId());
		Set<UUID> winners = fixture.confirmed().getResults().stream().filter(result -> result.getSelection().getUserId() != null)
				.map(result -> result.getSelection().getUserId()).collect(Collectors.toSet());
		// when
		AwardCancellationReceipt first = service.cancel(actor, target.getId(), key, "관리자 사유");
		// then
		assertThat(first.execution().getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		UUID run = first.cancellation().replacementRunId();
		DrawSnapshot fixed = snapshots.findRegistered(run).orElseThrow();
		assertThat(fixed.rules().version()).isEqualTo(2);
		assertThat(fixed.rules().originalRunId()).isEqualTo(fixture.original().runId());
		assertThat(fixed.candidates()).containsExactlyInAnyOrderElementsOf(fixture.original().candidates().stream()
				.filter(candidate -> !winners.contains(candidate.userId())).toList());
		assertThat(first.execution().getConfirmation().getResults()).hasSize(1);
		DrawSelection replacement = first.execution().getConfirmation().getResults().getFirst().getSelection();
		assertThat(replacement.getPrizeId()).isEqualTo(target.getSelection().getPrizeId());
		assertThat(replacement.getSlotNumber()).isEqualTo(target.getSelection().getSlotNumber());
		assertThat(winners).doesNotContain(replacement.getUserId());
		assertThat(confirmation.confirm(fixture.original().runId())).usingRecursiveComparison().isEqualTo(fixture.confirmed());
		List<UUID> retained = fixture.confirmed().getResults().stream().filter(result -> !result.getId().equals(target.getId()))
				.map(DrawResult::getId).toList();
		assertThat(replacements.findCurrentResults(fixture.eventId(), fixture.original()).stream().map(DrawResult::getId).toList())
				.containsAll(retained).hasSize(3);
		AwardCancellationReceipt replay = service.cancel(actor, target.getId(), key, " 관리자 사유 ");
		assertThat(replay).usingRecursiveComparison().isEqualTo(first);
		assertThat(service.startOrResume(actor, first.cancellation().id())).usingRecursiveComparison().isEqualTo(first.execution());
		assertThat(runCount(fixture.eventId())).isEqualTo(2);
		assertThat(cancellationCount(fixture.eventId())).isEqualTo(1);
		assertThat(ticketRows(fixture.eventId())).isEqualTo(tickets);
		assertThat(historyCount(fixture.eventId())).isEqualTo(histories);
		assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, toBytes(fixture.eventId()))).isEqualTo("REDRAWING");
		assertThat(jdbc.queryForObject("select count(*) from draw_publications where event_id = ?", Long.class, toBytes(fixture.eventId()))).isZero();
		assertThat(auditActor("AWARD_CANCEL", target.getId())).isEqualTo(actor);
		assertThat(auditActor("DRAW_EXECUTE", run)).isEqualTo(actor);
		verify(selection, times(1)).select(anyList(), anyList(), anyBoolean());
		assertCode(() -> service.cancel(actor, target.getId(), key, "다른 사유"), KEY_CONFLICT);
		assertCode(() -> service.cancel(actor, target.getId(), UUID.randomUUID(), "사유"), NOT_CANCELABLE);
	}

	@Test @DisplayName("재추첨 당첨의 후속 취소도 최초 후보를 재사용하고 과거 실행 결과는 그대로 반환한다")
	void cancelsReplacementWinnerAcrossGenerations() {
		// given
		DrawResult originalTarget = target(fixture);
		AwardCancellationReceipt first = service.cancel(actor, originalTarget.getId(), UUID.randomUUID(), "첫 취소");
		DrawResult firstReplacement = first.execution().getConfirmation().getResults().getFirst();
		// when
		AwardCancellationReceipt second = service.cancel(actor, firstReplacement.getId(), UUID.randomUUID(), "후속 취소");
		DrawResult secondReplacement = second.execution().getConfirmation().getResults().getFirst();
		AwardCancellationReceipt exhausted = service.cancel(actor, secondReplacement.getId(), UUID.randomUUID(), "후보 소진 취소");
		// then
		assertThat(second.execution().getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		assertThat(secondReplacement.getSelection().getUserId()).isNotEqualTo(firstReplacement.getSelection().getUserId());
		assertThat(exhausted.execution().getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		assertThat(exhausted.execution().getConfirmation().getResults().getFirst().getSelection().getResultType())
				.isEqualTo(DrawSelectionResultType.UNFILLED);
		assertThat(service.startOrResume(actor, first.cancellation().id())).usingRecursiveComparison().isEqualTo(first.execution());
		assertThat(service.startOrResume(actor, second.cancellation().id())).usingRecursiveComparison().isEqualTo(second.execution());
		assertThat(runCount(fixture.eventId())).isEqualTo(4);
		assertThat(cancellationCount(fixture.eventId())).isEqualTo(3);
		assertThat(replacements.findCurrentResults(fixture.eventId(), fixture.original())).hasSize(2);
		assertThat(jdbc.queryForList("select run_number from draw_runs where event_id = ? order by run_number", Integer.class,
				toBytes(fixture.eventId()))).containsExactly(0, 1, 2, 3);
	}

	@Test @DisplayName("실행 등록 flush 뒤 실패하면 취소·실행·감사가 남지 않는다")
	void rollsBackRegistrationFailure() {
		// given
		doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("registration failure injection"); })
				.when(replacements).register(fixture.eventId());
		// when / then
		assertThatThrownBy(() -> service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "사유")).isInstanceOf(RuntimeException.class);
		assertThat(runCount(fixture.eventId())).isEqualTo(1);
		assertThat(cancellationCount(fixture.eventId())).isZero();
		assertThat(auditCount("AWARD_CANCEL", target(fixture).getId())).isZero();
		verifyNoInteractions(selection);
	}

	@Test @DisplayName("취소 flush 뒤 감사 실패도 취소·등록·이벤트 상태를 함께 롤백한다")
	void rollsBackCancellationAndAuditFailure() {
		// given
		String statusBeforeCancellation = jdbc.queryForObject("select status from events where id = ?",
				String.class, toBytes(fixture.eventId()));
		assertThat(statusBeforeCancellation).isEqualTo("DRAW_CONFIRMED");
		doThrow(new IllegalStateException("audit failure injection")).when(auditRecords).append(any());
		// when / then
		assertThatThrownBy(() -> service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "사유")).isInstanceOf(RuntimeException.class);
		assertThat(runCount(fixture.eventId())).isEqualTo(1);
		assertThat(cancellationCount(fixture.eventId())).isZero();
		assertThat(jdbc.queryForObject("select status from events where id = ?", String.class, toBytes(fixture.eventId()))).isEqualTo(statusBeforeCancellation);
		verifyNoInteractions(selection);
	}

	@Test @DisplayName("선정 실패는 부분 결과를 롤백하고 동일 실행·고정 입력을 다른 관리자가 재개한다")
	void resumesFailureWithoutNewCancellationOrInput() {
		// given
		UUID key = UUID.randomUUID();
		doAnswer(invocation -> { invocation.callRealMethod(); throw new TransientDataAccessResourceException("failure injection"); })
				.when(results).saveAllAndFlush(anyList());
		// when
		AwardCancellationReceipt failed = service.cancel(actor, target(fixture).getId(), key, "취소 근거");
		// then
		UUID run = failed.cancellation().replacementRunId();
		assertThat(failed.execution().getStatus()).isEqualTo(DrawRunStatus.FAILED);
		assertThat(failures.count(run)).isEqualTo(1);
		assertThat(resultCount(run)).isZero();
		DrawSnapshot fixed = snapshots.findRegistered(run).orElseThrow();
		assertThat(fixed.fixedAt()).isNotNull();
		assertThat(service.cancel(actor, target(fixture).getId(), key, "취소 근거").execution().getStatus()).isEqualTo(DrawRunStatus.FAILED);
		reset(results);
		jdbc.update("update event_participants set used_ticket_count = 99 where event_id = ?", toBytes(fixture.eventId()));
		UUID resumer = user("ADMIN");
		DrawExecutionResult resumed = service.startOrResume(resumer, failed.cancellation().id());
		assertThat(resumed.getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		DrawSnapshot after = snapshots.findRegistered(run).orElseThrow();
		assertThat(after.candidates()).isEqualTo(fixed.candidates());
		assertThat(after.rules()).isEqualTo(fixed.rules());
		assertThat(after.fixedAt()).isEqualTo(fixed.fixedAt());
		assertThat(runCount(fixture.eventId())).isEqualTo(2);
		assertThat(cancellationCount(fixture.eventId())).isEqualTo(1);
		assertThat(auditActor("AWARD_CANCEL", target(fixture).getId())).isEqualTo(actor);
		assertThat(auditActor("DRAW_RESUME", run)).isEqualTo(resumer);
		assertThat(service.startOrResume(resumer, failed.cancellation().id())).usingRecursiveComparison().isEqualTo(resumed);
	}

	@Test @DisplayName("미확정 재추첨이 있는 동안 새 취소는 거절하고 기존 취소의 시작은 같은 실행을 사용한다")
	void rejectsNewCancellationDuringPendingReplacement() {
		// given
		DrawResult target = target(fixture);
		UUID key = UUID.randomUUID();
		AwardCancellation registered = registration.register(actor, target.getId(), key, "사유");
		DrawResult other = fixture.confirmed().getResults().stream().filter(result -> !result.getId().equals(target.getId())).findFirst().orElseThrow();
		// when / then
		assertCode(() -> service.cancel(actor, other.getId(), UUID.randomUUID(), "다른 취소"), NOT_CANCELABLE);
		assertThat(registration.register(actor, target.getId(), key, "사유")).isEqualTo(registered);
		DrawExecutionResult started = service.startOrResume(actor, registered.id());
		assertThat(started.getRunId()).isEqualTo(registered.replacementRunId());
		assertThat(started.getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		assertThat(runCount(fixture.eventId())).isEqualTo(2);
		assertThat(cancellationCount(fixture.eventId())).isEqualTo(1);
	}

	@Test @DisplayName("후보가 없으면 실제 취소 자리만 UNFILLED로 확정하고 기존 미충원 자리를 추가로 추첨하지 않는다")
	void confirmsOnlyVacantSlotWithoutCandidates() {
		// given
		Fixture insufficient = event(1, false);
		DrawResult target = insufficient.confirmed().getResults().getFirst();
		clearInvocations(selection);
		// when
		AwardCancellationReceipt receipt = service.cancel(actor, target.getId(), UUID.randomUUID(), "사유");
		// then
		assertThat(receipt.execution().getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
		List<DrawResult> stored = receipt.execution().getConfirmation().getResults();
		assertThat(stored).hasSize(1);
		assertThat(stored.getFirst().getSelection().getSlotNumber()).isEqualTo(target.getSelection().getSlotNumber());
		assertThat(stored.getFirst().getSelection().getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
		verifyNoInteractions(selection);
		DrawResult oldUnfilled = insufficient.confirmed().getResults().getLast();
		assertCode(() -> service.cancel(actor, oldUnfilled.getId(), UUID.randomUUID(), "사유"), NOT_CANCELABLE);
	}

	@Test @DisplayName("동시 AD05 등록이 실제 이벤트 잠금에서 대기하고 취소·실행을 한 번만 등록한다")
	void concurrentRegistrationUsesOneCancellationAndRun() throws Exception {
		// given
		UUID key = UUID.randomUUID();
		CountDownLatch registering = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(invocation -> {
			Object registered = invocation.callRealMethod();
			registering.countDown();
			if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
			return registered;
		}).when(replacements).register(fixture.eventId());
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<AwardCancellationReceipt> first = executor.submit(() -> service.cancel(actor, target(fixture).getId(), key, "사유"));
			try {
				assertThat(registering.await(20, TimeUnit.SECONDS)).isTrue();
				// when
				Future<AwardCancellationReceipt> second = executor.submit(() -> service.cancel(actor, target(fixture).getId(), key, "사유"));
				new LockWaitProbe(mysql).awaitLockWaits(1);
				assertThat(second.isDone()).isFalse();
				release.countDown();
				// then
				AwardCancellationReceipt one = first.get(30, TimeUnit.SECONDS), two = second.get(30, TimeUnit.SECONDS);
				assertThat(one.cancellation()).isEqualTo(two.cancellation());
				assertThat(service.startOrResume(actor, one.cancellation().id()).getStatus()).isEqualTo(DrawRunStatus.CONFIRMED);
			} finally { release.countDown(); }
		}
		assertThat(runCount(fixture.eventId())).isEqualTo(2);
		assertThat(cancellationCount(fixture.eventId())).isEqualTo(1);
		verify(selection, times(1)).select(anyList(), anyList(), anyBoolean());
	}

	@Test @DisplayName("AD05 자동 시작·재요청과 AD09가 실제 이벤트 잠금에서 경합해 결과를 한 번만 확정한다")
	void concurrentAutoStartReplayAndAd09SelectOnce() throws Exception {
		// given
		UUID key = UUID.randomUUID();
		CountDownLatch selecting = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(invocation -> { selecting.countDown(); if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
			return invocation.callRealMethod(); }).when(selection).select(anyList(), anyList(), anyBoolean());
		try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
			Future<AwardCancellationReceipt> first = executor.submit(() -> service.cancel(actor, target(fixture).getId(), key, "사유"));
			try {
				assertThat(selecting.await(20, TimeUnit.SECONDS)).isTrue();
				AwardCancellation connected = cancellations.findByRequestKey(key).orElseThrow();
				// when
				Future<DrawExecutionResult> ad09 = executor.submit(() -> service.startOrResume(actor, connected.id()));
				Future<AwardCancellationReceipt> replay = executor.submit(() -> service.cancel(actor, target(fixture).getId(), key, "사유"));
				new LockWaitProbe(mysql).awaitLockWaits(2);
				assertThat(ad09.isDone()).isFalse();
				release.countDown();
				// then
				AwardCancellationReceipt confirmed = first.get(30, TimeUnit.SECONDS);
				assertThat(ad09.get(30, TimeUnit.SECONDS)).usingRecursiveComparison().isEqualTo(confirmed.execution());
				assertThat(replay.get(30, TimeUnit.SECONDS)).usingRecursiveComparison().isEqualTo(confirmed);
			} finally { release.countDown(); }
		}
		assertThat(runCount(fixture.eventId())).isEqualTo(2);
		assertThat(cancellationCount(fixture.eventId())).isEqualTo(1);
		verify(selection, times(1)).select(anyList(), anyList(), anyBoolean());
	}

	@Test @DisplayName("같은 전역 키로 다른 이벤트를 동시 취소하면 한 등록만 남고 다른 등록은 롤백된다")
	void concurrentGlobalKeyCollisionRollsBackOtherEvent() throws Exception {
		// given
		Fixture other = event(4, false);
		UUID key = UUID.randomUUID();
		CountDownLatch storing = new CountDownLatch(2), release = new CountDownLatch(1);
		doAnswer(invocation -> { storing.countDown(); if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
			return invocation.callRealMethod(); }).when(cancellations).save(any());
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Object> first = executor.submit(() -> cancelOrConflict(target(fixture).getId(), key));
			Future<Object> second = executor.submit(() -> cancelOrConflict(target(other).getId(), key));
			try {
				assertThat(storing.await(20, TimeUnit.SECONDS)).isTrue();
				release.countDown();
				// when / then
				List<Object> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
				assertThat(outcomes.stream().filter(AwardCancellationReceipt.class::isInstance)).hasSize(1);
				assertThat(outcomes).contains(KEY_CONFLICT);
			} finally { release.countDown(); }
		}
		assertThat(runCount(fixture.eventId()) + runCount(other.eventId())).isEqualTo(3);
		assertThat(cancellationCount(fixture.eventId()) + cancellationCount(other.eventId())).isEqualTo(1);
	}

	@Test @DisplayName("실제 비관리자·비활성 관리자·없는 대상·취소 이벤트는 취소와 실행을 만들지 않는다")
	void rejectsUnauthorizedAndInvalidTargets() {
		// given / when / then
		assertCode(() -> service.cancel(user("USER"), target(fixture).getId(), UUID.randomUUID(), "사유"), ADMIN_REQUIRED);
		UUID inactive = user("ADMIN");
		jdbc.update("update users set status = 'INACTIVE' where id = ?", toBytes(inactive));
		assertCode(() -> service.cancel(inactive, target(fixture).getId(), UUID.randomUUID(), "사유"), ADMIN_REQUIRED);
		assertCode(() -> service.cancel(actor, UUID.randomUUID(), UUID.randomUUID(), "사유"), NOT_FOUND);
		assertCode(() -> service.startOrResume(actor, UUID.randomUUID()), NOT_FOUND);
		jdbc.update("update events set status = 'CANCELED' where id = ?", toBytes(fixture.eventId()));
		assertCode(() -> service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "사유"), NOT_CANCELABLE);
		assertThat(runCount(fixture.eventId())).isEqualTo(1);
		assertThat(cancellationCount(fixture.eventId())).isZero();
	}

	@Test @DisplayName("JPA가 역순으로 반환해도 유지 결과를 실행·선정 순서대로 복원한다")
	void preservesResultOrderWhenJpaReturnsReverseOrder() {
		List<UUID> expected = fixture.confirmed().getResults().stream()
				.filter(result -> result.getSelection().getResultType() == DrawSelectionResultType.SELECTED)
				.sorted(Comparator.comparingInt(result -> result.getSelection().getSelectionOrder()))
				.map(DrawResult::getId).toList();
		List<DrawResultEntity> reversed = new ArrayList<>(results.findAllById(expected));
		Collections.reverse(reversed);
		doReturn(reversed).when(results).findAllById(any());
		assertThat(replacements.findCurrentResults(fixture.eventId(), fixture.original()))
				.extracting(DrawResult::getId).containsExactlyElementsOf(expected);
	}

	@Test @DisplayName("미연결 과거 취소는 조회 가능하지만 AD05·AD09는 취소 오류로 거절하고 실행을 만들지 않는다")
	void readsLegacyHistoryAndRejectsExecutionInService() {
		UUID id = UUID.randomUUID();
		UUID win = target(fixture).getId();
		jdbc.update("""
			insert into award_cancellations (id, draw_result_id, draw_run_id, canceled_by, reason, canceled_at)
			values (?, ?, null, ?, '과거 사유', ?)
			""", toBytes(id), toBytes(win), toBytes(actor), utc(NOW));
		assertThat(cancellations.findById(id).orElseThrow().replacementRunId()).isNull();
		assertCode(() -> service.cancel(actor, win, UUID.randomUUID(), "새 사유"), NOT_CANCELABLE);
		assertCode(() -> service.startOrResume(actor, id), NOT_CANCELABLE);
		assertThat(runCount(fixture.eventId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from award_cancellations where draw_result_id = ?",
				Integer.class, toBytes(win))).isEqualTo(1);
		verifyNoInteractions(selection);
	}

	/** 다음 동작을 검증한다: 실제 조회·취소·재추첨·검증·확인·공개·알림 연결은 재요청에 중복되지 않는다. */
	@Test @DisplayName("실제 조회·취소·재추첨·검증·확인·공개·알림 연결은 재요청에 중복되지 않는다")
	void integratesAdministrationPublicationAndNotification() {
		// given
		DrawPublication initial = publications.publishInitial(fixture.eventId()).orElseThrow();
		DrawResult target = target(fixture);
		List<Map<String, Object>> tickets = ticketRows(fixture.eventId());
		long histories = historyCount(fixture.eventId());
		// when
		AwardCancellationReceipt canceled = service.cancel(actor, target.getId(), UUID.randomUUID(), "운영자 확인");
		UUID runId = canceled.cancellation().replacementRunId();
		assertThat(inspections.verify(users.findById(actor).orElseThrow(), runId).isPassed()).isTrue();
		DrawPublication updated = resultConfirmations.confirmForPublication(runId, actor);
		DrawPublication replay = resultConfirmations.confirmForPublication(runId, actor);
		// then
		assertThat(updated.getRevision()).isEqualTo(2);
		assertThat(replay).isEqualTo(updated);
		assertThat(updated.getEntries()).hasSize(3);
		assertThat(publications.history(fixture.eventId())).containsExactly(initial, updated);
		DrawPublicationChange change = publications.changes(fixture.eventId()).getLast();
		assertThat(change.getExecutions()).hasSize(1);
		assertThat(change.getExecutions().getFirst().getRunId()).isEqualTo(runId);
		assertThat(change.getExecutions().getFirst().getConfirmedBy()).isEqualTo(actor);
		assertThat(change.getExecutions().getFirst().getCancellations().getFirst().getReason()).isEqualTo("운영자 확인");
		assertThat(inspections.detail(users.findById(actor).orElseThrow(), runId).getExecution().getCancellations()).hasSize(1);
		assertThat(inspections.executionAudits(users.findById(actor).orElseThrow(), runId,
				new PageQuery(1, 1)).getTotalElements()).isGreaterThanOrEqualTo(1);
		assertThat(jobCount(fixture.eventId())).isEqualTo(2);
		UUID initialJob = jobId(initial), updateJob = jobId(updated);
		assertThat(jobRecords.findRequest(initialJob).getRecipientIds()).hasSize(5);
		Set<UUID> changed = Set.of(target.getSelection().getUserId(),
				canceled.execution().getConfirmation().getResults().getFirst().getSelection().getUserId());
		assertThat(jobRecords.findRequest(updateJob).getRecipientIds()).containsExactlyInAnyOrderElementsOf(changed);
		processJobs();
		assertThat(jdbc.queryForObject("select count(*) from notifications where job_id = ?", Long.class, toBytes(initialJob))).isEqualTo(5);
		assertThat(jdbc.queryForObject("select count(*) from notifications where job_id = ?", Long.class, toBytes(updateJob))).isEqualTo(2);
		assertThat(ticketRows(fixture.eventId())).isEqualTo(tickets);
		assertThat(historyCount(fixture.eventId())).isEqualTo(histories);
	}

	/** 다음 동작을 검증한다: 여러 실행의 미충원 결과와 서로 다른 확인자를 공개 근거에 보존한다. */
	@Test @DisplayName("여러 실행의 미충원 결과와 서로 다른 확인자를 공개 근거에 보존한다")
	void preservesMultipleRunEvidenceIncludingUnfilled() {
		// given
		Fixture small = event(3, false);
		List<DrawResult> targets = small.confirmed().getResults().stream().filter(value -> value.getSelection().getUserId() != null).limit(2).toList();
		UUID otherAdmin = user("ADMIN");
		// when
		AwardCancellationReceipt first = service.cancel(actor, targets.getFirst().getId(), UUID.randomUUID(), "첫 번째 사유");
		clock.set(NOW.plusSeconds(1));
		AwardCancellationReceipt second = service.cancel(otherAdmin, targets.getLast().getId(), UUID.randomUUID(), "두 번째 사유");
		assertThat(inspections.verify(users.findById(actor).orElseThrow(), first.cancellation().replacementRunId()).isPassed()).isTrue();
		assertThat(inspections.verify(users.findById(actor).orElseThrow(), second.cancellation().replacementRunId()).isPassed()).isTrue();
		assertThatThrownBy(() -> resultConfirmations.confirmBeforeInitial(first.cancellation().replacementRunId(), actor))
				.isInstanceOf(BusinessException.class);
		// 구버전에서 이미 저장한 확인 근거는 새 단일 발표에서도 원래 확인자를 보존한다.
		jdbc.update("insert into draw_result_confirmations (id, draw_run_id, confirmed_by, confirmed_at) values (?, ?, ?, ?)",
				toBytes(UUID.randomUUID()), toBytes(first.cancellation().replacementRunId()), toBytes(actor), utc(NOW.plusSeconds(1)));
		DrawPublication version = resultConfirmations.confirmBeforeInitial(second.cancellation().replacementRunId(), otherAdmin).getPublication();
		// then
		assertThat(first.execution().getConfirmation().getResults().getFirst().getSelection().getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
		assertThat(second.execution().getConfirmation().getResults().getFirst().getSelection().getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
		assertThat(version.getEntries()).hasSize(1);
		List<DrawPublication.Execution> evidence = publications.changes(small.eventId()).getFirst().getExecutions();
		assertThat(evidence).hasSize(3);
		DrawInspectionView.Detail unfilledDetail = inspections.detail(users.findById(actor).orElseThrow(), first.cancellation().replacementRunId());
		assertThat(unfilledDetail.getResults()).singleElement().satisfies(result -> {
			assertThat(result.getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
			assertThat(result.getUserId()).isNull();
			assertThat(result.getCandidateId()).isNull();
			assertThat(result.getSelectionOrder()).isNull();
			assertThat(result.isWasPublished()).isFalse();
		});
		DrawInspectionView.Run nextRun = inspections.run(users.findById(actor).orElseThrow(), second.cancellation().replacementRunId());
		List<DrawHistoricalAward> historical = inspectionRecords.findReplacementReferences(nextRun).getHistoricalAwards();
		assertThat(historical).extracting(DrawHistoricalAward::getId).doesNotHaveDuplicates();
		assertThat(historical).filteredOn(award -> award.getRunId().equals(first.cancellation().replacementRunId()))
				.singleElement().satisfies(award -> {
					assertThat(award.getResultType()).isEqualTo(DrawSelectionResultType.UNFILLED);
					assertThat(award.getCandidateId()).isNull();
					assertThat(award.getUserId()).isNull();
					assertThat(award.getCanceledAt()).isNull();
				});
		assertThat(evidence.stream().map(DrawPublication.Execution::getConfirmedBy)).containsExactly(otherAdmin, actor, otherAdmin);
		assertThat(evidence.stream().flatMap(value -> value.getCancellations().stream()).map(DrawPublication.Cancellation::getReason))
				.containsExactly("첫 번째 사유", "두 번째 사유");
	}

	/** 다음 동작을 검증한다: 실패 이력의 페이징과 예약·운영자 대기 요약을 실제 실패 후 조회한다. */
	@Test @DisplayName("실패 이력의 페이징과 예약·운영자 대기 요약을 실제 실패 후 조회한다")
	void queriesFailureHistoryAfterRealExecutionFailure() {
		// given
		doThrow(new TransientDataAccessResourceException("diagnostic only")).when(selection).select(anyList(), anyList(), anyBoolean());
		// when
		AwardCancellationReceipt failed = service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "실패 검증");
		UUID runId = failed.cancellation().replacementRunId();
		// then
		DrawInspectionView.ExecutionSummary summary = inspections.detail(users.findById(actor).orElseThrow(), runId).getExecution();
		assertThat(summary.getFailureCount()).isEqualTo(1);
		assertThat(summary.getLatestFailure().getFailureCode()).isEqualTo("TRANSIENT_STORAGE");
		assertThat(summary.getRetryAt()).isNotNull();
		assertThat(summary.isWaitingForOperator()).isFalse();
		PageResult<DrawFailure> page = inspections.failures(users.findById(actor).orElseThrow(), runId,
				new PageQuery(1, 1));
		assertThat(page.getItems()).hasSize(1);
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(inspections.failures(users.findById(actor).orElseThrow(), runId,
				new PageQuery(2, 1)).getItems()).isEmpty();
		assertThat(page.getItems().getFirst().getTraceId()).isNull();
		assertThat(page.getItems().getFirst().getFailureCode()).doesNotContain("diagnostic");
	}

	/** 다음 동작을 검증한다: 알림 작업 등록 실패는 공개 버전과 새 확인을 모두 롤백하고 재시도는 한 번만 저장한다. */
	@Test @DisplayName("알림 작업 등록 실패는 공개 버전과 새 확인을 모두 롤백하고 재시도는 한 번만 저장한다")
	void rollsBackPublicationAndConfirmationWhenDurableRegistrationFails() {
		// given
		DrawPublication initial = publications.publishInitial(fixture.eventId()).orElseThrow();
		UUID runId = service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "등록 장애").cancellation().replacementRunId();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new TransientDataAccessResourceException("job registration failure after insert");
		}).when(jobRecords).register(any(), any());
		// when / then
		assertThatThrownBy(() -> resultConfirmations.confirmForPublication(runId, actor)).isInstanceOf(TransientDataAccessResourceException.class);
		assertThat(publications.history(fixture.eventId())).containsExactly(initial);
		assertThat(jdbc.queryForObject("select count(*) from draw_result_confirmations where draw_run_id = ?", Long.class, toBytes(runId))).isZero();
		assertThat(jobCount(fixture.eventId())).isEqualTo(1);
		doCallRealMethod().when(jobRecords).register(any(), any());
		DrawPublication updated = resultConfirmations.confirmForPublication(runId, actor);
		assertThat(resultConfirmations.confirmForPublication(runId, actor)).isEqualTo(updated);
		assertThat(jobCount(fixture.eventId())).isEqualTo(2);
	}

	/** 다음 동작을 검증한다: 커밋 뒤 모의 발송 실패는 공개 버전을 되돌리지 않고 기존 작업으로 재시도한다. */
	@Test @DisplayName("커밋 뒤 모의 발송 실패는 공개 버전을 되돌리지 않고 기존 작업으로 재시도한다")
	void deliveryFailureDoesNotRevertPublishedVersion() {
		// given
		DrawPublication published = publications.publishInitial(fixture.eventId()).orElseThrow();
		processJobs();
		doThrow(new IllegalStateException("sender unavailable")).when(sender).send(any());
		// when
		assertThat(jobs.processNextDelivery()).isTrue();
		// then
		assertThat(publications.history(fixture.eventId())).containsExactly(published);
		assertThat(jobCount(fixture.eventId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from notifications where job_id = ?", Long.class, toBytes(jobId(published)))).isEqualTo(5);
		doCallRealMethod().when(sender).send(any());
		clock.set(NOW.plusSeconds(600));
		for (int index = 0; index < 500 && jobs.processNextDelivery(); index++) { }
		assertThat(jdbc.queryForObject("select count(*) from notifications where job_id = ? and mock_delivery_status = 'SENT'", Long.class, toBytes(jobId(published)))).isEqualTo(5);
	}

	/** 다음 동작을 검증한다: 같은 실행의 동시 관리자 확인은 확인·공개·알림 작업을 각각 한 번만 저장한다. */
	@Test @DisplayName("같은 실행의 동시 관리자 확인은 확인·공개·알림 작업을 각각 한 번만 저장한다")
	void simultaneousConfirmationsReuseDurableRecords() throws Exception {
		// given
		publications.publishInitial(fixture.eventId()).orElseThrow();
		UUID runId = service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "동시 확인").cancellation().replacementRunId();
		CountDownLatch storing = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(invocation -> {
			Object stored = invocation.callRealMethod();
			storing.countDown();
			if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("공개 잠금 해제 대기 실패");
			return stored;
		}).when(publicationRecords).savePublication(eq(fixture.eventId()), eq(runId), anyInt(), any(), any(), anyList(), any());
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<DrawPublication> first = executor.submit(() -> resultConfirmations.confirmForPublication(runId, actor));
			try {
				assertThat(storing.await(20, TimeUnit.SECONDS)).isTrue();
				// when
				Future<DrawPublication> second = executor.submit(() -> resultConfirmations.confirmForPublication(runId, actor));
				new LockWaitProbe(mysql).awaitLockWaits(1);
				assertThat(second.isDone()).isFalse();
				release.countDown();
				DrawPublication version = first.get(30, TimeUnit.SECONDS);
				// then
				assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(version);
				assertThat(publications.history(fixture.eventId())).hasSize(2);
				assertThat(jobCount(fixture.eventId())).isEqualTo(2);
				assertThat(jdbc.queryForObject("select count(*) from draw_result_confirmations where draw_run_id = ?", Long.class, toBytes(runId))).isEqualTo(1);
				assertThat(jdbc.queryForObject("select count(*) from draw_publication_executions where publication_id = ?", Long.class, toBytes(version.getId()))).isEqualTo(1);
			} finally { release.countDown(); }
		}
	}

	/** 다음 동작을 검증한다: 영구 실패는 자동 예약 없이 운영자 대기 상태로 조회되며 오류 원문을 저장하지 않는다. */
	@Test @DisplayName("영구 실패는 자동 예약 없이 운영자 대기 상태로 조회되며 오류 원문을 저장하지 않는다")
	void summarizesOperatorWaitingForPermanentFailure() {
		// given
		doThrow(new IllegalStateException("private implementation detail")).when(selection).select(anyList(), anyList(), anyBoolean());
		// when
		UUID runId = service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "운영자 대기").cancellation().replacementRunId();
		// then
		DrawInspectionView.ExecutionSummary summary = inspections.detail(users.findById(actor).orElseThrow(), runId).getExecution();
		assertThat(summary.getFailureCount()).isEqualTo(1);
		assertThat(summary.getLatestFailure().getFailureCode()).isEqualTo("INTERNAL_ERROR");
		assertThat(summary.getRetryAt()).isNull();
		assertThat(summary.isWaitingForOperator()).isTrue();
	}

	/** 다음 동작을 검증한다: 재추첨 당첨을 다시 취소해도 과거 검증과 공개 확인 근거는 불변이며 최신 자리만 바꾼다. */
	@Test @DisplayName("재추첨 당첨을 다시 취소해도 과거 검증과 공개 확인 근거는 불변이며 최신 자리만 바꾼다")
	void preservesHistoricalVerificationAndPublishedEvidenceAcrossGenerations() {
		// given
		publications.publishInitial(fixture.eventId()).orElseThrow();
		AwardCancellationReceipt first = service.cancel(actor, target(fixture).getId(), UUID.randomUUID(), "첫 교체");
		UUID firstRun = first.cancellation().replacementRunId();
		DrawPublication firstVersion = resultConfirmations.confirmForPublication(firstRun, actor);
		DrawPublicationChange firstEvidence = publications.changes(fixture.eventId()).getLast();
		DrawResult newWinner = first.execution().getConfirmation().getResults().getFirst();
		clock.set(NOW.plusSeconds(1));
		// when
		AwardCancellationReceipt second = service.cancel(actor, newWinner.getId(), UUID.randomUUID(), "재추첨 당첨 취소");
		UUID secondRun = second.cancellation().replacementRunId();
		DrawPublication latest = resultConfirmations.confirmForPublication(secondRun, actor);
		// then
		assertThat(inspections.verify(users.findById(actor).orElseThrow(), firstRun).isPassed()).isTrue();
		assertThat(inspections.verify(users.findById(actor).orElseThrow(), secondRun).isPassed()).isTrue();
		assertThat(latest.getRevision()).isEqualTo(3);
		assertThat(latest.getEntries()).hasSize(3);
		assertThat(latest.getEntries().stream().map(DrawPublication.Entry::getResultId)).doesNotContain(newWinner.getId());
		assertThat(publications.changes(fixture.eventId()).get(1)).usingRecursiveComparison().isEqualTo(firstEvidence);
		assertThat(publications.history(fixture.eventId()).get(1)).isEqualTo(firstVersion);
		assertThat(publications.changes(fixture.eventId()).getLast().getExecutions().stream().map(DrawPublication.Execution::getRunId))
				.containsExactly(secondRun);
		assertThat(jobCount(fixture.eventId())).isEqualTo(3);
		DrawInspectionView.Detail previousDetail = inspections.detail(users.findById(actor).orElseThrow(), firstRun);
		assertThat(previousDetail.getResults()).filteredOn(result -> result.getId().equals(newWinner.getId()))
				.singleElement().satisfies(result -> {
					assertThat(result.isCanceled()).isTrue();
					assertThat(result.isWasPublished()).isTrue();
				});
		assertThat(previousDetail.getExecution().getCancellations()).extracting(DrawInspectionView.Cancellation::getId)
				.containsExactly(first.cancellation().id(), second.cancellation().id());
		assertThat(inspections.detail(users.findById(actor).orElseThrow(), secondRun).getExecution().getCancellations())
				.extracting(DrawInspectionView.Cancellation::getId).containsExactly(second.cancellation().id());
	}

	/** 이벤트의 실제 알림 작업 행 수로 공개 버전별 중복 등록 여부를 확인한다. */
	private long jobCount(UUID eventId) { return jdbc.queryForObject("select count(*) from notification_jobs where event_id = ?", Long.class, toBytes(eventId)); }
	/** 공개 ID 기반 발생 키로 실제 등록된 알림 작업의 식별자를 읽는다. */
	private UUID jobId(DrawPublication version) {
		return fromBytes(jdbc.queryForObject("select id from notification_jobs where occurrence_key = ?", byte[].class, "draw-publication:" + version.getId()));
	}
	/** 기존 생성 처리기를 작업이 없거나 시험 반복 상한에 이를 때까지 호출한다. */
	private void processJobs() { for (int index = 0; index < 500 && jobs.processNextJob(); index++) { } }

	private Object cancelOrConflict(UUID win, UUID key) {
		try { return service.cancel(actor, win, key, "사유"); }
		catch (BusinessException exception) { return exception.getErrorCode(); }
	}
	private Fixture event(int entrants, boolean weighted) {
		UUID event = UUID.randomUUID(), firstPrize = UUID.randomUUID();
		jdbc.update("""
			insert into events (id, title, description, image_key, event_type, weighting_enabled, starts_at, ends_at,
			status, membership_rule, created_at, updated_at) values (?, '취소 연동', '설명', 'test.png', ?, ?, ?, ?, 'CLOSED', 'vip', ?, ?)
			""", toBytes(event), weighted ? "TICKET" : "NO_TICKET", weighted, utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(300)), utc(NOW), utc(NOW));
		for (int rank = 1; rank <= 2; rank++) jdbc.update("""
			insert into event_prizes (id, event_id, prize_rank, name, winner_count, created_at, updated_at)
			values (?, ?, ?, '경품', ?, ?, ?)
			""", toBytes(rank == 1 ? firstPrize : UUID.randomUUID()), toBytes(event), rank, rank == 1 ? 2 : 1, utc(NOW), utc(NOW));
		for (int index = 0; index < entrants; index++) {
			UUID user = user("USER"), participant = UUID.randomUUID(), entry = UUID.randomUUID();
			int count = weighted ? 1 : 0;
			jdbc.update("insert into event_participants (id, event_id, user_id, used_ticket_count, created_at) values (?, ?, ?, ?, ?)",
					toBytes(participant), toBytes(event), toBytes(user), count, utc(NOW.minusSeconds(301)));
			jdbc.update("insert into event_entries (id, event_id, participant_id, user_id, requested_ticket_count, deducted_ticket_count, created_at) values (?, (select event_id from event_participants where id = ?), ?, ?, ?, ?, ?)",
					toBytes(entry), toBytes(participant), toBytes(participant), toBytes(user), count, count, utc(NOW.minusSeconds(301)));
			if (weighted) ticket(user, entry, List.of("GOLD", "SILVER", "BRONZE").get(index % 3));
		}
		DrawSnapshot original = snapshotService.prepareInitial(event);
		return new Fixture(event, original, confirmation.confirm(original.runId()));
	}
	private void ticket(UUID owner, UUID entry, String grade) {
		UUID policy = UUID.randomUUID(), mission = UUID.randomUUID(), submission = UUID.randomUUID(), claim = UUID.randomUUID(), ticket = UUID.randomUUID();
		jdbc.update("insert into reward_policies (id, created_by, reward_type, reward_ticket_count, effective_from, created_at) values (?, ?, 'MISSION', 1, ?, ?)",
				toBytes(policy), toBytes(actor), utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(3600)));
		jdbc.update("""
			insert into missions (id, reward_policy_id, created_by, title, description, mission_type, starts_at, ends_at, status, created_at, updated_at)
			values (?, ?, ?, '연동 출처', '설명', 'QUIZ', ?, ?, 'ACTIVE', ?, ?)
			""", toBytes(mission), toBytes(policy), toBytes(actor), utc(NOW.minusSeconds(3600)), utc(NOW), utc(NOW), utc(NOW));
		jdbc.update("insert into mission_submissions (id, mission_id, reward_policy_id, user_id, is_completed, created_at) values (?, ?, ?, ?, true, ?)",
				toBytes(submission), toBytes(mission), toBytes(policy), toBytes(owner), utc(NOW.minusSeconds(1000)));
		jdbc.update("""
			insert into mission_reward_claims (id, reward_policy_id, mission_id, mission_submission_id, user_id, source_key, ticket_count, created_at)
			values (?, ?, ?, ?, ?, ?, 1, ?)
			""", toBytes(claim), toBytes(policy), toBytes(mission), toBytes(submission), toBytes(owner), claim.toString(), utc(NOW.minusSeconds(1000)));
		jdbc.update("""
			insert into tickets (id, user_id, mission_reward_claim_id, grade, status, expires_at, version, created_at, updated_at)
			values (?, ?, ?, ?, 'SPENT', ?, 2, ?, ?)
			""", toBytes(ticket), toBytes(owner), toBytes(claim), grade, utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(1000)), utc(NOW.minusSeconds(301)));
		jdbc.update("""
			insert into ticket_histories (id, ticket_id, event_entry_id, operation_type, ticket_version, status, expires_at, reason, created_at)
			values (?, ?, ?, 'USE', 2, 'SPENT', ?, '테스트 응모', ?)
			""", toBytes(UUID.randomUUID()), toBytes(ticket), toBytes(entry), utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(301)));
	}
	private UUID user(String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users (id, name, role, status, membership, created_at, updated_at) values (?, '연동 사용자', ?, 'ACTIVE', 'VIP', ?, ?)",
				toBytes(id), role, utc(NOW), utc(NOW));
		return id;
	}
	private DrawResult target(Fixture value) {
		return value.confirmed().getResults().stream().filter(result -> result.getSelection().getRank() == 1 && result.getSelection().getSlotNumber() == 2)
				.findFirst().orElseThrow();
	}
	private long runCount(UUID event) { return jdbc.queryForObject("select count(*) from draw_runs where event_id = ?", Long.class, toBytes(event)); }
	private long cancellationCount(UUID event) { return jdbc.queryForObject("select count(*) from award_cancellations where draw_run_id in (select id from draw_runs where event_id = ?)", Long.class, toBytes(event)); }
	private long resultCount(UUID run) { return jdbc.queryForObject("select count(*) from draw_results where draw_run_id = ?", Long.class, toBytes(run)); }
	private long auditCount(String action, UUID target) { return jdbc.queryForObject("select count(*) from audit_logs where action = ? and target_id = ?", Long.class, action, toBytes(target)); }
	/** 요청 행위와 대상에 저장된 감사 처리자를 읽어 취소자·실행자를 대조한다. */
	private UUID auditActor(String action, UUID target) { return fromBytes(jdbc.queryForObject("select actor_id from audit_logs where action = ? and target_id = ?", byte[].class, action, toBytes(target))); }
	private long historyCount(UUID event) { return jdbc.queryForObject("select count(*) from ticket_histories h join event_entries e on e.id = h.event_entry_id join event_participants p on p.id = e.participant_id where p.event_id = ?", Long.class, toBytes(event)); }
	private List<Map<String, Object>> ticketRows(UUID event) {
		return jdbc.queryForList("select hex(t.id) as id, t.status, t.version, t.grade from tickets t join ticket_histories h on h.ticket_id = t.id join event_entries e on e.id = h.event_entry_id join event_participants p on p.id = e.participant_id where p.event_id = ? order by t.id", toBytes(event));
	}
	private static void assertCode(Runnable operation, ErrorCode code) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
	private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
	private record Fixture(UUID eventId, DrawSnapshot original, DrawConfirmation confirmed) { }
}
