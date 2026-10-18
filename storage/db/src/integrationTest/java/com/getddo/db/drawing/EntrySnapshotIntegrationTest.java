package com.getddo.db.drawing;

import jakarta.persistence.EntityManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.core.entry.domain.EntryCommand;
import com.getddo.core.entry.domain.EntryReceipt;
import com.getddo.core.entry.exception.EntryErrorCode;
import com.getddo.core.entry.exception.EntryException;
import com.getddo.core.entry.service.EntryService;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.user.domain.Membership;
import com.getddo.db.drawing.repository.DrawSnapshotSourceReader;
import com.getddo.db.entry.repository.EntryParticipantRepositoryImpl;
import com.getddo.db.ticket.MutableClock;
import com.getddo.db.support.EntrySnapshotTestApplication;
import com.getddo.db.ticket.TicketGrantSeeds;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** 실제 응모·차감·원장과 스냅샷의 커밋, 롤백 및 이벤트 잠금 경계를 MySQL에서 검증한다. */
@SpringBootTest(classes = EntrySnapshotTestApplication.class,
		properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class EntrySnapshotIntegrationTest {
	private static final Instant NOW = EntrySnapshotTestApplication.INITIAL_TIME;
	private static final Instant END = NOW.plusSeconds(60);
	@Autowired private EntityManager entityManager;
	@Autowired private EntryService entries;
	@Autowired private DrawSnapshotService snapshots;
	@Autowired private MutableClock clock;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MySQLContainer mysql;
	@MockitoSpyBean private EntryParticipantRepositoryImpl participants;
	@MockitoSpyBean private DrawSnapshotSourceReader source;
	private TicketGrantSeeds seeds;
	private UUID user;
	private UUID event;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		seeds = new TicketGrantSeeds(jdbc);
		user = seeds.user();
		event = seeds.event("TICKET", true, 5, "excellent", NOW.minusSeconds(60), END, "OPEN", null);
		jdbc.update("""
			insert into event_prizes (id, event_id, prize_rank, name, winner_count, created_at, updated_at)
			values (?, ?, 1, '연동 검증 경품', 1, ?, ?)
			""", bytes(UUID.randomUUID()), bytes(event), utc(NOW), utc(NOW));
	}

	@AfterEach
	void cleanUp() {
		// 이 테스트가 생성한 실행만 FK 역순으로 정리한다. 다른 테스트의 데이터를 건드리지 않는다.
		jdbc.update("delete c from draw_run_candidates c join draw_runs r on r.id = c.draw_run_id where r.event_id = ?", bytes(event));
		jdbc.update("delete c from draw_candidates c join draw_runs r on r.id = c.draw_run_id where r.event_id = ?", bytes(event));
		jdbc.update("delete from draw_runs where event_id = ?", bytes(event));
		jdbc.update("delete from event_prizes where event_id = ?", bytes(event));
		seeds.cleanUp();
	}

	@Test
	@DisplayName("실제 추가 응모의 장수·등급별 가중치·차감 원장이 일치하고 거절 요청은 집계하지 않는다")
	void commitsAdditionalEntriesAndOnlyAcceptedEvidence() {
		// given
		give(user, "BRONZE", 2);
		give(user, "SILVER", 1);
		give(user, "GOLD", 2);
		UUID first = UUID.randomUUID();
		UUID additional = UUID.randomUUID();
		UUID rejected = UUID.randomUUID();
		UUID rejectedOnlyUser = seeds.user();
		// when
		entries.enter(command(user, first, Map.of(TicketGrade.GOLD, 1L, TicketGrade.SILVER, 1L)));
		entries.enter(command(user, additional, Map.of(TicketGrade.BRONZE, 2L, TicketGrade.GOLD, 1L)));
		assertThatThrownBy(() -> entries.enter(command(user, rejected, Map.of(TicketGrade.GOLD, 1L))))
				.satisfies(failure -> assertError(failure, EntryErrorCode.TICKET_LIMIT_EXCEEDED));
		UUID rejectedOnly = UUID.randomUUID();
		assertThatThrownBy(() -> entries.enter(command(rejectedOnlyUser, rejectedOnly, Map.of(TicketGrade.GOLD, 1L))))
				.isInstanceOf(com.getddo.core.ticket.exception.TicketException.class);
		DrawSnapshot snapshot = prepare();
		// then
		assertThat(snapshot.status()).isEqualTo(DrawRunStatus.READY);
		assertThat(snapshot.candidates()).hasSize(1);
		DrawSnapshot.Candidate candidate = snapshot.candidates().getFirst();
		assertThat(candidate.userId()).isEqualTo(user);
		assertThat(candidate.ticketCount()).isEqualTo(5);
		assertThat(candidate.weight()).isEqualTo(15);
		assertThat(candidate.entryEvidence().bronzeCount()).isEqualTo(2);
		assertThat(candidate.entryEvidence().silverCount()).isEqualTo(1);
		assertThat(candidate.entryEvidence().goldCount()).isEqualTo(2);
		assertThat(candidate.entryEvidence().entries()).extracting(DrawSnapshotSource.Entry::id)
				.containsExactlyInAnyOrder(first, additional);
		List<UUID> evidenceHistories = candidate.entryEvidence().entries().stream()
				.flatMap(entry -> entry.uses().stream()).map(DrawSnapshotSource.Use::historyId).toList();
		assertThat(evidenceHistories).hasSize(5).doesNotHaveDuplicates();
		assertThat(jdbc.query("""
			select h.id from ticket_histories h join event_entries e on e.id = h.event_entry_id
			where e.event_id = ? and e.status = 'ACCEPTED' and h.operation_type = 'USE'
			""", (row, index) -> TicketGrantSeeds.uuid(row.getBytes(1)), bytes(event)))
				.containsExactlyInAnyOrderElementsOf(evidenceHistories);
		assertThat(count("select count(*) from tickets where user_id = ? and status = 'SPENT'", bytes(user))).isEqualTo(5);
		assertThat(count("select used_ticket_count from event_participants where id = ?", bytes(candidate.participantId())))
				.isEqualTo(5);
		assertRejectedWithoutLinks(rejected);
		assertRejectedWithoutLinks(rejectedOnly);
		assertThat(count("select count(*) from event_participants where event_id = ?", bytes(event))).isEqualTo(1);
	}

	/** 실제 플래티넘 차감과 추가 응모를 연결해 네 등급 근거가 JSON 왕복 후에도 보존되는지 검증한다. */
	@Test
	@DisplayName("실제 플래티넘 혼합 응모는 사용 4장·가중치 19를 저장하고 재요청에도 보존한다")
	void platinumEntryPersistsWeightedSnapshot() {
		// given
		give(user, "BRONZE", 1);
		give(user, "SILVER", 1);
		give(user, "GOLD", 1);
		give(user, "PLATINUM", 1);
		UUID first = UUID.randomUUID(), additional = UUID.randomUUID();
		// when
		entries.enter(command(user, first, Map.of(TicketGrade.PLATINUM, 1L, TicketGrade.GOLD, 1L)));
		entries.enter(command(user, additional, Map.of(TicketGrade.SILVER, 1L, TicketGrade.BRONZE, 1L)));
		DrawSnapshot snapshot = prepare();
		// then
		assertThat(snapshot.rules().version()).isEqualTo(3);
		assertThat(snapshot.rules().platinumWeight()).isEqualTo(10);
		assertThat(snapshot.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.ticketCount()).isEqualTo(4);
			assertThat(candidate.weight()).isEqualTo(19);
			assertThat(candidate.entryEvidence().version()).isEqualTo(2);
			assertThat(candidate.entryEvidence().platinumCount()).isEqualTo(1);
			assertThat(candidate.entryEvidence().entries()).extracting(DrawSnapshotSource.Entry::id)
					.containsExactlyInAnyOrder(first, additional);
		});
		jdbc.update("update event_participants set used_ticket_count = 0 where event_id = ?", bytes(event));
		assertThat(snapshots.prepareInitial(event)).isEqualTo(snapshot);
	}

	@ParameterizedTest
	@ValueSource(strings = {"NO_TICKET", "TICKET"})
	@DisplayName("미사용·가중치 미적용 이벤트도 실제 서비스로 접수하고 동일 가중치 1을 확정한다")
	void acceptsUniformWeightEvents(String eventType) {
		// given
		jdbc.update("update events set event_type = ?, weighting_enabled = false where id = ?", eventType, bytes(event));
		UUID entry = UUID.randomUUID();
		boolean usesTickets = eventType.equals("TICKET");
		if (usesTickets) give(user, "BRONZE", 1);
		// when
		entries.enter(command(user, entry, usesTickets ? Map.of(TicketGrade.BRONZE, 1L) : Map.of()));
		DrawSnapshot snapshot = prepare();
		// then
		assertThat(snapshot.status()).isEqualTo(DrawRunStatus.READY);
		assertThat(snapshot.candidates()).hasSize(1);
		DrawSnapshot.Candidate candidate = snapshot.candidates().getFirst();
		assertThat(candidate.ticketCount()).isEqualTo(usesTickets ? 1 : 0);
		assertThat(candidate.weight()).isEqualTo(1);
		assertThat(candidate.entryEvidence().entries()).extracting(DrawSnapshotSource.Entry::id).containsExactly(entry);
		assertThat(candidate.entryEvidence().entries().getFirst().uses()).hasSize(usesTickets ? 1 : 0);
	}

	@Test
	@DisplayName("추가 응모가 차감 후 실패해도 이전 ACCEPTED와 차감 근거는 보존한다")
	void failedAdditionalEntryPreservesPriorCommit() {
		// given
		give(user, "GOLD", 2);
		UUID accepted = UUID.randomUUID();
		UUID failed = UUID.randomUUID();
		entries.enter(command(user, accepted, Map.of(TicketGrade.GOLD, 1L)));
		doAnswer(invocation -> {
			assertPendingDeduction(failed);
			throw new IllegalStateException("추가 차감 이후 장애");
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// when
		assertThatThrownBy(() -> entries.enter(command(user, failed, Map.of(TicketGrade.GOLD, 1L))))
				.isInstanceOf(InvalidDataAccessApiUsageException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("추가 차감 이후 장애");
		DrawSnapshot snapshot = prepare();
		// then
		assertThat(count("select count(*) from event_entries where id = ?", bytes(failed))).isZero();
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ?", bytes(failed))).isZero();
		assertThat(count("select count(*) from tickets where user_id = ? and status = 'SPENT'", bytes(user))).isEqualTo(1);
		assertThat(count("select used_ticket_count from event_participants where event_id = ?", bytes(event))).isEqualTo(1);
		assertThat(snapshot.candidates()).hasSize(1);
		assertThat(snapshot.candidates().getFirst().ticketCount()).isEqualTo(1);
		assertThat(snapshot.candidates().getFirst().weight()).isEqualTo(5);
		assertThat(snapshot.candidates().getFirst().entryEvidence().entries())
				.extracting(DrawSnapshotSource.Entry::id).containsExactly(accepted);
	}

	@Test
	@DisplayName("차감과 원장 저장 후 장애가 나면 정상 응모·응모자·차감·원장을 함께 롤백한다")
	void failureAfterDeductionRollsBackAllWrites() {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		doAnswer(invocation -> {
			assertPendingDeduction(entry);
			throw new IllegalStateException("차감 이후 테스트 장애");
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// when
		assertThatThrownBy(() -> entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L))))
				.isInstanceOf(InvalidDataAccessApiUsageException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("차감 이후 테스트 장애");
		// then
		assertThat(count("select count(*) from event_entries where id = ?", bytes(entry))).isZero();
		assertNoDeduction(entry);
		assertThat(prepare().status()).isEqualTo(DrawRunStatus.NO_ENTRIES);
	}

	@Test
	@DisplayName("차감 후 정확히 마감 시각이면 정상 저장은 롤백되고 독립된 거절 이력만 남는다")
	void deadlineAfterDeductionRollsBackButKeepsRejection() {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			assertPendingDeduction(entry);
			clock.set(END);
			return null;
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// when
		assertThatThrownBy(() -> entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L))))
				.satisfies(failure -> assertError(failure, EntryErrorCode.EVENT_NOT_OPEN));
		// then
		assertRejectedWithoutLinks(entry);
		assertNoDeduction(entry);
		assertThat(prepare().status()).isEqualTo(DrawRunStatus.NO_ENTRIES);
	}

	@Test
	@DisplayName("서비스 검사 후 커밋 직전 최종 검사에서 마감이면 응모·차감을 롤백한다")
	void rejectsWhenDeadlinePassesBeforeCommit() {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) { clock.set(END); }
			});
			return null;
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// when
		Throwable failure = catchThrowable(() -> entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L))));
		// then
		assertThat(count("select count(*) from event_entries where id = ? and status = 'ACCEPTED'", bytes(entry)))
				.as("커밋 직전 최종 검사에서 마감된 요청의 ACCEPTED 잔존")
				.isZero();
		assertError(failure, EntryErrorCode.EVENT_NOT_OPEN);
		assertRejectedWithoutLinks(entry);
		assertNoDeduction(entry);
	}

	@Test
	@DisplayName("마감 전 최종 검사를 통과한 뒤 커밋이 지연된 응모는 승인된 예외에 따라 후보에 포함한다")
	void includesCommitDelayedAfterFinalDeadlineCheck() {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			assertPendingDeduction(entry);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCompletion() {
					// beforeCommit의 최종 검사 뒤, 실제 DB 커밋 전의 지연을 시계로 재현한다.
					clock.set(END.plusSeconds(1));
				}
			});
			return null;
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// when
		EntryReceipt receipt = entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L)));
		// then
		assertThat(receipt.isCreated()).isTrue();
		assertThat(receipt.getAcceptedAt()).isBefore(END);
		assertThat(clock.instant()).isEqualTo(END.plusSeconds(1));
		assertThat(count("select count(*) from event_entries where id = ? and status = 'ACCEPTED'", bytes(entry))).isEqualTo(1);
		assertThat(count("select count(*) from tickets where user_id = ? and status = 'SPENT'", bytes(user))).isEqualTo(1);
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ? and operation_type = 'USE'", bytes(entry)))
				.isEqualTo(1);
		DrawSnapshot snapshot = prepare();
		assertThat(snapshot.candidates()).hasSize(1);
		DrawSnapshot.Candidate candidate = snapshot.candidates().getFirst();
		assertThat(candidate.ticketCount()).isEqualTo(1);
		assertThat(candidate.weight()).isEqualTo(5);
		assertThat(candidate.entryEvidence().entries()).extracting(DrawSnapshotSource.Entry::id).containsExactly(entry);
	}

	@Test
	@DisplayName("정확히 마감 시각의 신규 응모는 거절되며 후보나 차감이 생기지 않는다")
	void rejectsAtExactDeadline() {
		// given
		give(user, "BRONZE", 1);
		UUID entry = UUID.randomUUID();
		clock.set(END);
		// when
		assertThatThrownBy(() -> entries.enter(command(user, entry, Map.of(TicketGrade.BRONZE, 1L))))
				.satisfies(failure -> assertError(failure, EntryErrorCode.EVENT_NOT_OPEN));
		// then
		assertRejectedWithoutLinks(entry);
		assertNoDeduction(entry);
		assertThat(prepare().candidates()).isEmpty();
	}

	@Test
	@DisplayName("READY 재요청은 실제 응모 원본·경품을 다시 읽지 않고 고정된 스냅샷을 반환한다")
	void readyReplayDoesNotRecompute() {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L)));
		DrawSnapshot ready = prepare();
		jdbc.update("update event_participants set used_ticket_count = 99 where event_id = ?", bytes(event));
		jdbc.update("update event_prizes set winner_count = 9 where event_id = ?", bytes(event));
		doThrow(new AssertionError("READY에서 응모 원본을 다시 조회함")).when(source).findParticipants(event);
		// when
		DrawSnapshot replay = snapshots.prepareInitial(event);
		// then
		assertThat(replay).isEqualTo(ready);
		assertThat(count("select count(*) from draw_runs where event_id = ?", bytes(event))).isEqualTo(1);
		assertThat(replay.candidates().getFirst().weight()).isEqualTo(5);
	}

	@Test
	@DisplayName("같은 실제 응모 요청을 동시에 재시도해도 한 번만 접수·차감하고 스냅샷에 한 번 포함한다")
	void concurrentDuplicateRequestsCommitOnce() throws Exception {
		// given
		give(user, "GOLD", 2);
		UUID entry = UUID.randomUUID();
		EntryCommand command = command(user, entry, Map.of(TicketGrade.GOLD, 1L));
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<EntryReceipt> first = executor.submit(() -> { await(start); return entries.enter(command); });
			Future<EntryReceipt> second = executor.submit(() -> { await(start); return entries.enter(command); });
			// when
			start.countDown();
			EntryReceipt firstReceipt = first.get(15, TimeUnit.SECONDS);
			EntryReceipt secondReceipt = second.get(15, TimeUnit.SECONDS);
			// then
			assertThat(List.of(firstReceipt.isCreated(), secondReceipt.isCreated())).containsExactlyInAnyOrder(true, false);
			assertThat(firstReceipt.getEntryId()).isEqualTo(secondReceipt.getEntryId());
			assertThat(firstReceipt.getAcceptedAt()).isEqualTo(secondReceipt.getAcceptedAt());
		}
		DrawSnapshot snapshot = prepare();
		assertThat(snapshot.candidates()).hasSize(1);
		assertThat(snapshot.candidates().getFirst().ticketCount()).isEqualTo(1);
		assertThat(snapshot.candidates().getFirst().weight()).isEqualTo(5);
		assertThat(snapshot.candidates().getFirst().entryEvidence().entries())
				.extracting(DrawSnapshotSource.Entry::id).containsExactly(entry);
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ? and operation_type = 'USE'", bytes(entry)))
				.isEqualTo(1);
		assertThat(count("select count(*) from tickets where user_id = ? and status = 'SPENT'", bytes(user))).isEqualTo(1);
	}

	@Test
	@DisplayName("실제 응모의 공유 이벤트 잠금 뒤 추첨이 배타 잠금을 기다리고 커밋된 차감 근거를 포함한다")
	void snapshotWaitsForEntryCommit() throws Exception {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		CountDownLatch deducted = new CountDownLatch(1);
		CountDownLatch releaseEntry = new CountDownLatch(1);
		CountDownLatch releaseSnapshot = new CountDownLatch(1);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			assertPendingDeduction(entry);
			deducted.countDown();
			await(releaseEntry);
			return null;
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		// 추첨이 실제 잠금을 얻은 뒤에만 시계를 옮긴다. 응모 커밋은 마감 전 고정 시각에 완료한다.
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			await(releaseSnapshot);
			return result;
		}).when(source).lockEvent(event);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<EntryReceipt> accepted = executor.submit(() -> entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L))));
			try {
				await(deducted);
				// when
				Future<DrawSnapshot> drawing = executor.submit(() -> snapshots.prepareInitial(event));
				awaitEventWait("X", "S");
				assertThat(drawing.isDone()).isFalse();
				releaseEntry.countDown();
				assertThat(accepted.get(15, TimeUnit.SECONDS).isCreated()).isTrue();
				clock.set(END.plusSeconds(300));
				releaseSnapshot.countDown();
				DrawSnapshot snapshot = drawing.get(15, TimeUnit.SECONDS);
				// then
				assertThat(snapshot.status()).isEqualTo(DrawRunStatus.READY);
				assertThat(snapshot.candidates()).hasSize(1);
				assertThat(snapshot.candidates().getFirst().entryEvidence().entries())
						.extracting(DrawSnapshotSource.Entry::id).containsExactly(entry);
				assertThat(snapshot.candidates().getFirst().weight()).isEqualTo(5);
			} finally {
				releaseEntry.countDown();
				releaseSnapshot.countDown();
			}
		}
	}

	@Test
	@DisplayName("실제 차감 중 마감을 넘으면 추첨이 이벤트 잠금을 기다린 뒤 롤백된 응모를 제외한다")
	void snapshotWaitsForEntryRollback() throws Exception {
		// given
		give(user, "GOLD", 1);
		UUID entry = UUID.randomUUID();
		CountDownLatch deducted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			assertPendingDeduction(entry);
			deducted.countDown();
			await(release);
			return null;
		}).when(participants).addUsedTicketCount(any(UUID.class), anyLong());
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Throwable> rejected = executor.submit(() -> catchThrowable(() ->
					entries.enter(command(user, entry, Map.of(TicketGrade.GOLD, 1L)))));
			try {
				await(deducted);
				clock.set(END.plusSeconds(300));
				// when
				Future<DrawSnapshot> drawing = executor.submit(() -> snapshots.prepareInitial(event));
				awaitEventWait("X", "S");
				release.countDown();
				assertError(rejected.get(15, TimeUnit.SECONDS), EntryErrorCode.EVENT_NOT_OPEN);
				DrawSnapshot snapshot = drawing.get(15, TimeUnit.SECONDS);
				// then
				assertThat(snapshot.status()).isEqualTo(DrawRunStatus.NO_ENTRIES);
				assertThat(snapshot.candidates()).isEmpty();
				assertRejectedWithoutLinks(entry);
				assertNoDeduction(entry);
			} finally { release.countDown(); }
		}
	}

	@Test
	@DisplayName("추첨이 이벤트를 먼저 잠그면 응모가 응모자·응모권 잠금 전에 대기하고 마감으로 거절된다")
	void entryWaitsForSnapshotBeforeParticipantOrTicketLocks() throws Exception {
		// given
		give(user, "BRONZE", 1);
		UUID entry = UUID.randomUUID();
		clock.set(END.plusSeconds(300));
		CountDownLatch snapshotLocked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			snapshotLocked.countDown();
			await(release);
			return invocation.callRealMethod();
		}).when(source).findParticipants(event);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<DrawSnapshot> drawing = executor.submit(() -> snapshots.prepareInitial(event));
			try {
				await(snapshotLocked);
				// when
				Future<Throwable> rejected = executor.submit(() -> catchThrowable(() ->
						entries.enter(command(user, entry, Map.of(TicketGrade.BRONZE, 1L)))));
				awaitEventWait("S", "X");
				assertThat(rejected.isDone()).isFalse();
				assertThat(count("select count(*) from event_entries where id = ?", bytes(entry))).isZero();
				assertNoDeduction(entry);
				release.countDown();
				// then
				assertThat(drawing.get(15, TimeUnit.SECONDS).status()).isEqualTo(DrawRunStatus.NO_ENTRIES);
				assertError(rejected.get(15, TimeUnit.SECONDS), EntryErrorCode.EVENT_NOT_OPEN);
				assertRejectedWithoutLinks(entry);
			} finally { release.countDown(); }
		}
	}

	/** 대기·차단 양쪽의 실제 이벤트 PK 잠금과 응모 트랜잭션의 하위 잠금 부재를 확인한다. */
	private void awaitEventWait(String requestingMode, String blockingMode) throws Exception {
		Instant deadline = Instant.now().plusSeconds(20);
		try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
				PreparedStatement waits = root.prepareStatement("""
				select r.lock_mode, b.lock_mode, r.engine_transaction_id
				from performance_schema.data_lock_waits w
				join performance_schema.data_locks r on r.engine_lock_id = w.requesting_engine_lock_id
				join performance_schema.data_locks b on b.engine_lock_id = w.blocking_engine_lock_id
				where r.object_schema = ? and r.object_name = 'events' and r.index_name = 'PRIMARY'
				""")) {
			waits.setString(1, mysql.getDatabaseName());
			while (Instant.now().isBefore(deadline)) {
				try (ResultSet rows = waits.executeQuery()) {
					if (rows.next()) {
						assertThat(rows.getString(1)).startsWith(requestingMode);
						assertThat(rows.getString(2)).startsWith(blockingMode);
						if (requestingMode.equals("S")) {
							try (PreparedStatement lower = root.prepareStatement("""
								select count(*) from performance_schema.data_locks
								where engine_transaction_id = ? and object_schema = ?
								and object_name in ('event_participants', 'tickets')
								""")) {
								lower.setLong(1, rows.getLong(3));
								lower.setString(2, mysql.getDatabaseName());
								try (ResultSet locks = lower.executeQuery()) {
									locks.next();
									assertThat(locks.getLong(1)).isZero();
								}
							}
						}
						return;
					}
				}
				TimeUnit.MILLISECONDS.sleep(20);
			}
		}
		throw new AssertionError("events PRIMARY의 실제 잠금 대기가 관측되지 않음");
	}

	private void give(UUID owner, String grade, int count) {
		for (int index = 0; index < count; index++) seeds.ticket(owner, grade, END.plusSeconds(86_400));
	}

	private EntryCommand command(UUID owner, UUID entry, Map<TicketGrade, Long> tickets) {
		return new EntryCommand(owner, false, Membership.EXCELLENT, event, entry, tickets);
	}

	private DrawSnapshot prepare() {
		clock.set(END.plusSeconds(300));
		return snapshots.prepareInitial(event);
	}

	/** 롤백 전 실제 DB에 정상 응모·차감·원장이 쓰였음을 같은 서비스 트랜잭션에서 확인한다. */
	private void assertPendingDeduction(UUID entry) {
		entityManager.flush();
		assertThat(count("select count(*) from event_entries where id = ? and status = 'ACCEPTED'", bytes(entry))).isEqualTo(1);
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ? and operation_type = 'USE'", bytes(entry)))
				.isEqualTo(1);
		assertThat(count("""
			select count(*) from tickets t join ticket_histories h on h.ticket_id = t.id
			where h.event_entry_id = ? and h.operation_type = 'USE' and t.status = 'SPENT'
			""", bytes(entry))).isEqualTo(1);
	}

	private void assertRejectedWithoutLinks(UUID entry) {
		assertThat(count("""
			select count(*) from event_entries where id = ? and status = 'REJECTED'
			and participant_id is null and deducted_ticket_count = 0 and rejection_code is not null
			""", bytes(entry))).isEqualTo(1);
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ?", bytes(entry))).isZero();
	}

	private void assertNoDeduction(UUID entry) {
		assertThat(count("select count(*) from event_participants where event_id = ?", bytes(event))).isZero();
		assertThat(count("select count(*) from tickets where user_id = ? and status <> 'AVAILABLE'", bytes(user))).isZero();
		assertThat(count("select count(*) from ticket_histories where event_entry_id = ?", bytes(entry))).isZero();
	}

	private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
	private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
	private static void assertError(Throwable failure, EntryErrorCode code) {
		assertThat(failure).isInstanceOfSatisfying(EntryException.class,
				error -> assertThat(error.getErrorCode()).isEqualTo(code));
	}
	private static void await(CountDownLatch latch) throws InterruptedException {
		if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("서비스 경계 동기화 시간 초과");
	}

}
