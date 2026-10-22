package com.getddo.db.drawing;

import java.nio.ByteBuffer;
import java.time.Clock;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.db.common.config.JpaAuditingConfig;
import com.getddo.db.drawing.entity.DrawCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunCandidateEntity;
import com.getddo.db.drawing.entity.DrawRunCandidateId;
import com.getddo.db.drawing.entity.DrawRunEntity;
import com.getddo.db.drawing.repository.DrawCandidateJpaRepository;
import com.getddo.db.drawing.repository.DrawRunJpaRepository;
import com.getddo.db.drawing.repository.DrawRunCandidateJpaRepository;
import com.getddo.db.support.MySqlTestConfiguration;
import com.getddo.db.ticket.MutableClock;
import com.getddo.db.ticket.LockWaitProbe;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_EVIDENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = DrawSnapshotIntegrationTest.TestApplication.class,
		properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
class DrawSnapshotIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
	@Autowired private DrawSnapshotService service;
	@Autowired private MutableClock clock;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MySQLContainer mysql;
	@Autowired private DrawRunJpaRepository runs;
	@Autowired private DrawCandidateJpaRepository candidates;
	@MockitoSpyBean private DrawRunCandidateJpaRepository links;
	private UUID eventId, userId;

	/** 서버 시각과 유효한 이벤트·응모 테스트 환경을 각 검증 전에 초기화한다. */
	@BeforeEach
	void setUp() {
		clock.set(NOW);
		eventId = UUID.randomUUID(); userId = UUID.randomUUID();
		jdbc.update("""
			insert into users (id, name, role, status, membership, created_at, updated_at)
			values (?, '추첨 테스트', 'USER', 'ACTIVE', 'VIP', ?, ?)
			""", bytes(userId), utc(NOW), utc(NOW));
		jdbc.update("""
			insert into events (id, title, description, image_key, event_type, weighting_enabled,
			starts_at, ends_at, status, membership_rule, created_at, updated_at)
			values (?, '추첨 테스트', '설명', 'test.png', 'NO_TICKET', false, ?, ?, 'CLOSED', 'vip', ?, ?)
			""", bytes(eventId), utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(300)), utc(NOW), utc(NOW));
		jdbc.update("""
			insert into event_prizes (id, event_id, prize_rank, name, winner_count, created_at, updated_at)
			values (?, ?, 1, '경품', 2, ?, ?)
			""", bytes(UUID.randomUUID()), bytes(eventId), utc(NOW), utc(NOW));
	}

	/** 후보 저장 후 원본 응모·경품을 변경해도 같은 확정 입력을 반환하는지 검증한다. */
	@Test
	void storesAndReplaysEvidenceWithoutRecomputingOriginals() {
		UUID participant = addParticipant(0);
		DrawSnapshot first = service.prepareInitial(eventId);
		assertThat(first.status()).isEqualTo(DrawRunStatus.READY);
		assertThat(first.runId().version()).isEqualTo(7);
		assertThat(first.candidates().getFirst().id().version()).isEqualTo(7);
		assertThat(first.candidates().getFirst().ticketCount()).isZero();
		assertThat(first.candidates().getFirst().weight()).isEqualTo(1);
		assertThat(first.candidates().getFirst().entryEvidence().entries()).hasSize(1);
		assertThat(count("draw_run_candidates")).isEqualTo(1);
		jdbc.update("update event_participants set used_ticket_count = 7 where id = ?", bytes(participant));
		jdbc.update("update event_prizes set winner_count = 5 where event_id = ?", bytes(eventId));
		assertThat(service.prepareInitial(eventId)).isEqualTo(first);
	}

	/** 실제 USE 이력에서 등급별 차감량을 읽고 JSON 왕복 후 같은 가중치를 반환하는지 검증한다. */
	@Test
	void readsGradesFromOriginalUseHistoryAndPreservesJson() {
		jdbc.update("update events set event_type = 'TICKET', weighting_enabled = true where id = ?", bytes(eventId));
		UUID participant = addParticipant(3);
		byte[] entry = jdbc.queryForObject("select id from event_entries where participant_id = ?", byte[].class, bytes(participant));
		for (String grade : List.of("GOLD", "SILVER", "BRONZE")) addUsedTicket(entry, grade);
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		DrawSnapshot.Candidate candidate = snapshot.candidates().getFirst();
		assertThat(candidate.ticketCount()).isEqualTo(3);
		assertThat(candidate.weight()).isEqualTo(9);
		assertThat(candidate.entryEvidence().entries().getFirst().uses()).hasSize(3);
		assertThat(candidate.entryEvidence().goldCount()).isEqualTo(1);
		assertThat(service.prepareInitial(eventId)).isEqualTo(snapshot);
	}

	/** JPA가 UUID·JSON·UTC 시각과 실행별 후보 연결을 손실 없이 저장·복원하는지 검증한다. */
	@Test
	void mapsBinaryIdsJsonAndUtcTimesThroughJpa() {
		addParticipant(0);
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		DrawSnapshot.Candidate candidate = snapshot.candidates().getFirst();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DrawRunEntity run = runs.findById(snapshot.runId()).orElseThrow();
			assertThat(run.getEventId()).isEqualTo(eventId);
			assertThat(run.getCreatedAt()).isEqualTo(NOW);
			assertThat(run.getSnapshotFixedAt()).isEqualTo(NOW);
			assertThat(run.getStartedAt()).isNull();
			assertThat(run.getConfirmedAt()).isNull();
			DrawRunCandidateEntity link = links.findById(new DrawRunCandidateId(snapshot.runId(), candidate.id())).orElseThrow();
			assertThat(link.isNew()).isFalse();
			assertThat(link.getDrawRun().getId()).isEqualTo(snapshot.runId());
			assertThat(link.getCandidate().getParticipantId()).isEqualTo(candidate.participantId());
			assertThat(link.getCandidate().getCreatedAt()).isEqualTo(NOW);
		});
		assertThat(jdbc.queryForObject("select json_type(rules_snapshot) from draw_runs where id = ?",
				String.class, bytes(snapshot.runId()))).isEqualTo("OBJECT");
		assertThat(jdbc.queryForObject("select json_type(entry_snapshot) from draw_candidates where id = ?",
				String.class, bytes(candidate.id()))).isEqualTo("OBJECT");
		assertThat(jdbc.queryForObject("select json_type(eligibility_snapshot) from draw_candidates where id = ?",
				String.class, bytes(candidate.id()))).isEqualTo("OBJECT");
		assertThat(service.prepareInitial(eventId)).isEqualTo(snapshot);
	}

	/** DB에 같은 티켓의 중복 차감이 있어도 실행·후보를 저장하지 않는지 검증한다. */
	@Test
	@DisplayName("추가 응모 간 티켓 중복이 DB에 기록됐어도 스냅샷을 저장하지 않는다")
	void rejectsDuplicateTicketAcrossPersistedAdditionalEntries() {
		// given
		jdbc.update("update events set event_type = 'TICKET', weighting_enabled = true where id = ?", bytes(eventId));
		UUID participant = addParticipant(2);
		byte[] firstEntry = jdbc.queryForObject("select id from event_entries where participant_id = ?", byte[].class, bytes(participant));
		jdbc.update("update event_entries set requested_ticket_count = 1, deducted_ticket_count = 1 where id = ?", firstEntry);
		addUsedTicket(firstEntry, "GOLD");
		byte[] ticket = jdbc.queryForObject("select ticket_id from ticket_histories where event_entry_id = ?",
				byte[].class, firstEntry);
		UUID additionalEntry = UUID.randomUUID();
		jdbc.update("""
			insert into event_entries (id, event_id, participant_id, user_id, requested_ticket_count, deducted_ticket_count, created_at)
			values (?, (select event_id from event_participants where id = ?), ?, ?, 1, 1, ?)
			""", bytes(additionalEntry), bytes(participant), bytes(participant), bytes(userId), utc(NOW.minusSeconds(301)));
		// 응모별 UNIQUE로 거절할 수 없는 별도 이력·버전의 중복 사용을 재현한다.
		jdbc.update("""
			insert into ticket_histories (id, ticket_id, event_entry_id, operation_type, ticket_version, status, expires_at, reason, created_at)
			values (?, ?, ?, 'USE', 3, 'SPENT', ?, '중복 사용 검증', ?)
			""", bytes(UUID.randomUUID()), ticket, bytes(additionalEntry), utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(301)));

		// when / then
		assertThatThrownBy(() -> service.prepareInitial(eventId)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(INVALID_EVIDENCE);
		assertThat(count("draw_runs")).isZero();
		assertThat(count("draw_candidates")).isZero();
		assertThat(count("draw_run_candidates")).isZero();
	}

	/** 최초 응답과 재요청이 DB에 저장된 마이크로초 정밀도의 확정 시각을 공유하는지 검증한다. */
	@Test
	void firstResponseUsesPersistedMicrosecondPrecision() {
		clock.set(NOW.plusNanos(123456789));
		addParticipant(0);
		DrawSnapshot first = service.prepareInitial(eventId);
		assertThat(first.fixedAt().getNano() % 1000).isZero();
		assertThat(service.prepareInitial(eventId)).isEqualTo(first);
	}

	/** JPA 쓰기에서 실행·후보·실행별 연결의 DB 유일 제약을 지키는지 검증한다. */
	@Test
	void jpaWritesRespectRunCandidateAndLinkUniqueness() {
		addParticipant(0);
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		UUID candidateId = snapshot.candidates().getFirst().id();
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				runs.saveAndFlush(new DrawRunEntity(eventId))))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DrawCandidateEntity original = candidates.findById(candidateId).orElseThrow();
			candidates.saveAndFlush(new DrawCandidateEntity(original.getDrawRun(), original.getParticipantId(),
					original.getTicketCount(), original.getWeight(), original.getEntrySnapshot(), original.getEligibilitySnapshot()));
		})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DrawRunEntity run = runs.findById(snapshot.runId()).orElseThrow();
			DrawCandidateEntity candidate = candidates.findById(candidateId).orElseThrow();
			links.saveAndFlush(new DrawRunCandidateEntity(run, candidate));
		})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(count("draw_runs")).isEqualTo(1);
		assertThat(count("draw_candidates")).isEqualTo(1);
		assertThat(count("draw_run_candidates")).isEqualTo(1);
		assertThat(service.prepareInitial(eventId)).isEqualTo(snapshot);
	}

	/** 존재하지 않는 응모자와 양수가 아닌 가중치는 DB 제약으로 거절하는지 검증한다. */
	@Test
	void jpaWritesRespectParticipantForeignKeyAndPositiveWeight() {
		addParticipant(0);
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		UUID candidateId = snapshot.candidates().getFirst().id();
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DrawCandidateEntity original = candidates.findById(candidateId).orElseThrow();
			candidates.saveAndFlush(new DrawCandidateEntity(original.getDrawRun(), UUID.randomUUID(), 0, 1,
					original.getEntrySnapshot(), original.getEligibilitySnapshot()));
		})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
				.hasMessageContaining("fk_draw_candidates_2");
		UUID otherUser = UUID.randomUUID(), otherParticipant = UUID.randomUUID();
		jdbc.update("""
			insert into users (id, name, role, status, membership, created_at, updated_at)
			values (?, '추첨 테스트', 'USER', 'ACTIVE', 'VIP', ?, ?)
			""", bytes(otherUser), utc(NOW), utc(NOW));
		jdbc.update("insert into event_participants (id, event_id, user_id, used_ticket_count, created_at) values (?, ?, ?, 0, ?)",
				bytes(otherParticipant), bytes(eventId), bytes(otherUser), utc(NOW));
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DrawCandidateEntity original = candidates.findById(candidateId).orElseThrow();
			candidates.saveAndFlush(new DrawCandidateEntity(original.getDrawRun(), otherParticipant, 0, 0,
					original.getEntrySnapshot(), original.getEligibilitySnapshot()));
		})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
				.hasMessageContaining("chk_candidate_counts");
		assertThat(count("draw_candidates")).isEqualTo(1);
	}

	/** 응모자 없음 종료를 저장하고 같은 실행을 재요청에서도 보존하는지 검증한다. */
	@Test
	void distinguishesNormalEmptyTerminationsAndReplaysThem() {
		DrawSnapshot empty = service.prepareInitial(eventId);
		assertThat(empty.status()).isEqualTo(DrawRunStatus.NO_ENTRIES);
		assertThat(service.prepareInitial(eventId)).isEqualTo(empty);
		assertThat(count("draw_candidates")).isZero();
	}

	/** 자동 차단 이력의 만료 여부와 관계없이 정상 응모와 이력 자체를 보존하는지 검증한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("자동 차단 이력으로 같은 사용자의 정상 응모나 다른 유효 응모자를 제거하지 않는다")
	void existingAbuseRecordsDoNotRemoveValidParticipants(boolean expired) {
		// given
		UUID participant = addParticipant(0);
		UUID otherUser = UUID.randomUUID();
		jdbc.update("""
			insert into users (id, name, role, status, membership, created_at, updated_at)
			values (?, '다른 응모자', 'USER', 'ACTIVE', 'VIP', ?, ?)
			""", bytes(otherUser), utc(NOW), utc(NOW));
		UUID otherParticipant = addParticipant(0, otherUser);
		UUID record = UUID.randomUUID();
		Instant blockedAt = NOW.minusSeconds(expired ? 700 : 100);
		Instant blockedUntil = blockedAt.plusSeconds(600);
		jdbc.update("""
			insert into abuse_cases (id, event_id, user_id, source_type, detection_type,
				detection_reason, evidence, occurred_at, detected_at, blocked_at, blocked_until)
			values (?, ?, ?, 'ENTRY', 'REPEATED_RATE_LIMIT_EXCEEDED',
				'응모 요청 한도 초과가 반복되어 일시 차단되었습니다.',
				JSON_OBJECT('ruleVersion', 'v1', 'triggerScope', 'USER',
				  'windowStartedAt', ?, 'windowEndsAt', ?, 'requestLimit', 20,
				  'rejectedCount', 20, 'rejectionThreshold', 20), ?, ?, ?, ?)
			""", bytes(record), bytes(eventId), bytes(userId), blockedAt.minusSeconds(30).toString(),
				blockedAt.plusSeconds(30).toString(), utc(blockedAt), utc(blockedAt), utc(blockedAt), utc(blockedUntil));

		// when
		DrawSnapshot snapshot = service.prepareInitial(eventId);

		// then
		assertThat(snapshot.status()).isEqualTo(DrawRunStatus.READY);
		assertThat(snapshot.candidates()).extracting(DrawSnapshot.Candidate::participantId)
				.containsExactlyInAnyOrder(participant, otherParticipant);
		assertThat(snapshot.candidates()).allSatisfy(candidate -> {
			assertThat(candidate.ticketCount()).isZero();
			assertThat(candidate.weight()).isEqualTo(1);
			assertThat(candidate.eligibilityEvidence().excluded()).isFalse();
		});
		assertThat(count("draw_candidates")).isEqualTo(2);
		assertThat(count("draw_results")).isZero();
		assertThat(jdbc.queryForObject("select count(*) from abuse_cases where id = ?",
				Long.class, bytes(record))).isEqualTo(1L);
		assertThat(service.prepareInitial(eventId)).isEqualTo(snapshot);
	}

	/** 과거 전원 제외 실행이 이후 응모 변경에도 기존 스냅샷을 반환하는지 검증한다. */
	@Test
	@DisplayName("과거 NO_CANDIDATES 실행은 새 응모가 있어도 재계산하지 않는다")
	void replaysLegacyAllExcludedRunWithoutRecomputingCandidates() {
		// given
		DrawSnapshot empty = service.prepareInitial(eventId);
		// GD-84에서 저장한 과거 종료 상태를 재현한다. 새 입력 확정 경로로 생성하지 않는다.
		jdbc.update("update draw_runs set status = 'NO_CANDIDATES' where id = ?", bytes(empty.runId()));
		addParticipant(0);

		// when
		DrawSnapshot legacy = service.prepareInitial(eventId);

		// then
		assertThat(legacy.status()).isEqualTo(DrawRunStatus.NO_CANDIDATES);
		assertThat(legacy.runId()).isEqualTo(empty.runId());
		assertThat(legacy.rules()).isEqualTo(empty.rules());
		assertThat(legacy.algorithmVersion()).isEqualTo(empty.algorithmVersion());
		assertThat(legacy.fixedAt()).isEqualTo(empty.fixedAt());
		assertThat(legacy.candidates()).isEmpty();
		assertThat(service.prepareInitial(eventId)).isEqualTo(legacy);
		assertThat(count("draw_runs")).isEqualTo(1);
		assertThat(count("draw_candidates")).isZero();
		assertThat(count("draw_results")).isZero();
	}

	/** 확정 실행 재요청이 원본 변경에도 저장된 입력·결과·확정 시각을 변경하지 않는지 검증한다. */
	@Test
	void replaysConfirmedRunWithoutChangingSnapshotsOrResults() {
		UUID participant = addParticipant(0);
		DrawSnapshot ready = service.prepareInitial(eventId);
		DrawSnapshot.Candidate candidate = ready.candidates().getFirst();
		UUID result = UUID.randomUUID();
		jdbc.update("""
			insert into draw_results (id, draw_run_id, event_prize_id, slot_number, result_type,
				candidate_id, selection_order, created_at) values (?, ?, ?, 1, 'SELECTED', ?, 1, ?)
			""", bytes(result), bytes(ready.runId()), bytes(ready.rules().prizes().getFirst().id()),
				bytes(candidate.id()), utc(NOW));
		jdbc.update("update draw_runs set status = 'CONFIRMED', started_at = ?, confirmed_at = ? where id = ?",
				utc(NOW), utc(NOW), bytes(ready.runId()));
		Map<String, Object> storedResult = resultRow(result);
		Map<String, Object> storedInput = jdbc.queryForMap("""
			select entry_snapshot, eligibility_snapshot from draw_candidates where id = ?
			""", bytes(candidate.id()));
		jdbc.update("update event_participants set used_ticket_count = 7 where id = ?", bytes(participant));
		jdbc.update("update event_prizes set winner_count = 5 where event_id = ?", bytes(eventId));
		jdbc.update("update users set membership = 'VVIP' where id = ?", bytes(userId));

		DrawSnapshot confirmed = service.prepareInitial(eventId);

		assertThat(confirmed.status()).isEqualTo(DrawRunStatus.CONFIRMED);
		assertThat(confirmed.runId()).isEqualTo(ready.runId());
		assertThat(confirmed.rules()).isEqualTo(ready.rules());
		assertThat(confirmed.fixedAt()).isEqualTo(ready.fixedAt());
		assertThat(confirmed.algorithmVersion()).isEqualTo(ready.algorithmVersion());
		assertThat(confirmed.candidates()).isEqualTo(ready.candidates());
		assertThat(service.prepareInitial(eventId)).isEqualTo(confirmed);
		assertThat(resultRow(result)).isEqualTo(storedResult);
		assertThat(jdbc.queryForMap("select entry_snapshot, eligibility_snapshot from draw_candidates where id = ?",
				bytes(candidate.id()))).isEqualTo(storedInput);
		assertThat(jdbc.queryForObject("select confirmed_at from draw_runs where id = ?",
				LocalDateTime.class, bytes(ready.runId()))).isEqualTo(utc(NOW));
		assertThat(count("draw_runs")).isEqualTo(1);
		assertThat(count("draw_candidates")).isEqualTo(1);
		assertThat(count("draw_run_candidates")).isEqualTo(1);
		assertThat(count("draw_results")).isEqualTo(1);
	}

	/** 누적 응모 수량 불일치 시 실행·후보·연결 행을 남기지 않는지 검증한다. */
	@Test
	@DisplayName("누적 사용 장수가 다르면 준비를 거절하고 실행·후보·연결을 저장하지 않는다")
	void invalidAccumulationLeavesNoRunOrCandidates() {
		// given
		UUID participant = addParticipant(0);
		jdbc.update("update event_participants set used_ticket_count = 1 where id = ?", bytes(participant));

		// when
		Throwable failure = catchThrowable(() -> service.prepareInitial(eventId));

		// then
		assertThat(failure).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(INVALID_EVIDENCE);
		assertThat(count("draw_runs")).isZero();
		assertThat(count("draw_candidates")).isZero();
		assertThat(count("draw_run_candidates")).isZero();
	}

	/** 후보 연결 저장 실패 시 서비스 트랜잭션이 실행·후보 쓰기까지 롤백하는지 검증한다. */
	@Test
	void candidateLinkFailureRollsBackRunAndCandidate() {
		addParticipant(0);
		doThrow(new org.springframework.dao.DataIntegrityViolationException("test failure"))
				.when(links).saveAllAndFlush(anyList());
		assertThatThrownBy(() -> service.prepareInitial(eventId))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(count("draw_runs")).isZero();
		assertThat(count("draw_candidates")).isZero();
		assertThat(count("draw_run_candidates")).isZero();
	}

	/** 동시 최초 준비 요청이 같은 실행을 반환하고 스냅샷을 한 번만 저장하는지 검증한다. */
	@Test
	void concurrentPreparationCreatesOneRunAndSnapshot() throws Exception {
		addParticipant(0);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			CountDownLatch start = new CountDownLatch(1);
			Future<DrawSnapshot> first = executor.submit(() -> { start.await(); return service.prepareInitial(eventId); });
			Future<DrawSnapshot> second = executor.submit(() -> { start.await(); return service.prepareInitial(eventId); });
			start.countDown();
			assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
		}
		assertThat(count("draw_runs")).isEqualTo(1);
		assertThat(count("draw_candidates")).isEqualTo(1);
		assertThat(count("draw_run_candidates")).isEqualTo(1);
	}

	/** SQL 응모 트랜잭션의 이벤트 잠금을 기다린 뒤 커밋된 응모를 포함하는지 검증한다. */
	@Test
	@DisplayName("진행 중인 응모 SQL 트랜잭션의 실제 이벤트 잠금 대기를 확인하고 커밋된 후보를 포함한다")
	void waitsForInFlightEntryTransactionAndIncludesItsCommit() throws Exception {
		// given
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			// 실제 응모 서비스가 추가되기 전까지 합의된 이벤트 선잠금 계약을 SQL로 검증한다.
			Future<UUID> entry = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				jdbc.queryForObject("select id from events where id = ? for update", byte[].class, bytes(eventId));
				UUID participant = addParticipant(0);
				locked.countDown();
				try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
				return participant;
			}));
			try {
				assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
				// when
				Future<DrawSnapshot> drawing = executor.submit(() -> service.prepareInitial(eventId));
				new LockWaitProbe(mysql).awaitLockWaits(1);
				assertThat(drawing.isDone()).isFalse();
				release.countDown();
				UUID committedParticipant = entry.get(15, TimeUnit.SECONDS);
				DrawSnapshot snapshot = drawing.get(15, TimeUnit.SECONDS);
				// then
				assertThat(snapshot.candidates()).hasSize(1);
				assertThat(snapshot.candidates().getFirst().participantId()).isEqualTo(committedParticipant);
				assertThat(count("draw_runs")).isEqualTo(1);
				assertThat(count("draw_candidates")).isEqualTo(1);
				assertThat(count("draw_run_candidates")).isEqualTo(1);
			} finally { release.countDown(); }
		}
	}

	/** 거절 이력(REJECTED)은 응모자·차감이 없으므로 같은 사용자의 거절 건이 있어도 후보의 응모 증거와 대상에 포함되지 않는다. */
	@Test
	@DisplayName("거절된 응모 이력은 스냅샷의 응모 증거에 들어가지 않고 거절만 있는 사용자는 후보가 아니다")
	void ignoresRejectedEntries() {
		UUID participant = addParticipant(0);
		UUID rejectedOnly = UUID.randomUUID();
		jdbc.update("""
			insert into users (id, name, role, status, membership, created_at, updated_at)
			values (?, '거절만', 'USER', 'ACTIVE', 'VIP', ?, ?)
			""", bytes(rejectedOnly), utc(NOW), utc(NOW));
		for (UUID owner : List.of(userId, rejectedOnly)) {
			jdbc.update("""
				insert into event_entries (id, event_id, participant_id, user_id, status, rejection_code, rejection_reason,
				  requested_ticket_count, deducted_ticket_count, requested_gold_count, created_at)
				values (?, ?, null, ?, 'REJECTED', 'ENTRY-007', '거절', 2, 0, 2, ?)
				""", bytes(UUID.randomUUID()), bytes(eventId), bytes(owner), utc(NOW.minusSeconds(310)));
		}
		DrawSnapshot snapshot = service.prepareInitial(eventId);
		assertThat(snapshot.candidates()).hasSize(1);
		assertThat(snapshot.candidates().getFirst().participantId()).isEqualTo(participant);
		assertThat(snapshot.candidates().getFirst().entryEvidence().entries()).hasSize(1);
		assertThat(snapshot.candidates().getFirst().ticketCount()).isZero();
	}

	/** 지정한 사용량과 소유자로 마감 전 응모자·접수 행을 저장한다. */
	private UUID addParticipant(int count) {
		return addParticipant(count, userId);
	}

	/** 지정한 사용량과 소유자로 마감 전 응모자·접수 행을 저장한다. */
	private UUID addParticipant(int count, UUID owner) {
		UUID participant = UUID.randomUUID();
		jdbc.update("insert into event_participants (id, event_id, user_id, used_ticket_count, created_at) values (?, ?, ?, ?, ?)",
				bytes(participant), bytes(eventId), bytes(owner), count, utc(NOW.minusSeconds(301)));
		jdbc.update("""
			insert into event_entries (id, event_id, participant_id, user_id, requested_ticket_count, deducted_ticket_count, created_at)
			values (?, (select event_id from event_participants where id = ?), ?, ?, ?, ?, ?)
			""", bytes(UUID.randomUUID()), bytes(participant), bytes(participant), bytes(owner), count, count, utc(NOW.minusSeconds(301)));
		return participant;
	}

	/** 지정한 응모에 실제 보상 출처·등급 티켓·USE 이력을 저장한다. */
	private void addUsedTicket(byte[] entry, String grade) {
		UUID policy = UUID.randomUUID(), mission = UUID.randomUUID(), submission = UUID.randomUUID();
		UUID claim = UUID.randomUUID(), ticket = UUID.randomUUID();
		jdbc.update("""
			insert into reward_policies (id, created_by, reward_type, reward_ticket_count, effective_from, created_at)
			values (?, ?, 'MISSION', 1, ?, ?)
			""", bytes(policy), bytes(userId), utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(3600)));
		jdbc.update("""
			insert into missions (id, reward_policy_id, created_by, title, description, mission_type,
			starts_at, ends_at, status, created_at, updated_at)
			values (?, ?, ?, '테스트', '설명', 'QUIZ', ?, ?, 'ACTIVE', ?, ?)
			""", bytes(mission), bytes(policy), bytes(userId), utc(NOW.minusSeconds(3600)), utc(NOW), utc(NOW), utc(NOW));
		jdbc.update("""
			insert into mission_submissions (id, mission_id, reward_policy_id, user_id, is_completed, created_at)
			values (?, ?, ?, ?, true, ?)
			""", bytes(submission), bytes(mission), bytes(policy), bytes(userId), utc(NOW.minusSeconds(1000)));
		jdbc.update("""
			insert into mission_reward_claims (id, reward_policy_id, mission_id, mission_submission_id,
			user_id, source_key, ticket_count, created_at) values (?, ?, ?, ?, ?, ?, 1, ?)
			""", bytes(claim), bytes(policy), bytes(mission), bytes(submission), bytes(userId), claim.toString(), utc(NOW.minusSeconds(1000)));
		jdbc.update("""
			insert into tickets (id, user_id, mission_reward_claim_id, grade, status, expires_at, version, created_at, updated_at)
			values (?, ?, ?, ?, 'SPENT', ?, 2, ?, ?)
			""", bytes(ticket), bytes(userId), bytes(claim), grade, utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(1000)), utc(NOW.minusSeconds(301)));
		jdbc.update("""
			insert into ticket_histories (id, ticket_id, event_entry_id, operation_type, ticket_version, status, expires_at, reason, created_at)
			values (?, ?, ?, 'USE', 2, 'SPENT', ?, '테스트 응모', ?)
			""", bytes(UUID.randomUUID()), bytes(ticket), entry, utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(301)));
	}
	/** 확정 결과의 식별자·선정 내용·시각을 비교할 수 있도록 한 행을 조회한다. */
	private Map<String, Object> resultRow(UUID resultId) {
		return jdbc.queryForMap("""
			select hex(id) as id, hex(draw_run_id) as draw_run_id, hex(event_prize_id) as event_prize_id,
				slot_number, result_type, hex(candidate_id) as candidate_id, selection_order, created_at
			from draw_results where id = ?
			""", bytes(resultId));
	}
	/** 현재 이벤트에 속한 추첨 실행 또는 실행별 상세 행의 수를 조회한다. */
	private long count(String table) {
		String sql = table.equals("draw_runs") ? "select count(*) from draw_runs where event_id = ?"
				: "select count(*) from " + table + " where draw_run_id in (select id from draw_runs where event_id = ?)";
		return jdbc.queryForObject(sql, Long.class, bytes(eventId));
	}
	/** UUID를 테스트 DB의 BINARY(16) 표현으로 변환한다. */
	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}
	/** 서버 Instant를 테스트 SQL의 UTC DATETIME 값으로 변환한다. */
	private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@ComponentScan(basePackages = {"com.getddo.core.drawing", "com.getddo.db.drawing", "com.getddo.core.audit",
			"com.getddo.db.event.mapper", "com.getddo.db.event.repository",
			"com.getddo.db.audit.mapper", "com.getddo.db.audit.repository",
			"com.getddo.db.user.mapper", "com.getddo.db.user.repository", "com.getddo.core.notification.service", "com.getddo.db.notification.mapper", "com.getddo.db.notification.repository"})
	@EntityScan(basePackages = {"com.getddo.db.drawing", "com.getddo.db.audit", "com.getddo.db.user", "com.getddo.db.notification", "com.getddo.db.event"})
	@EnableJpaRepositories(basePackages = {"com.getddo.db.drawing", "com.getddo.db.audit", "com.getddo.db.user", "com.getddo.db.notification", "com.getddo.db.event"})
	@Import({MySqlTestConfiguration.class, JpaAuditingConfig.class,
			com.getddo.core.event.service.EventStatusChangeService.class})
	static class TestApplication {

		/** 공개 저장소·공통 관리자 검사·시각 제공자를 실제 관리자 확인 Bean에 연결한다. */
		@Bean com.getddo.core.drawing.service.DrawResultConfirmationService resultConfirmations(
				com.getddo.core.drawing.repository.DrawPublicationRepository repository,
				com.getddo.core.drawing.service.DrawPublicationService publications,
				com.getddo.core.drawing.service.DrawAdminAuthorization admins, TimeProvider time) {
			return new com.getddo.core.drawing.service.DrawResultConfirmationService(repository, publications, admins, time);
		}

		/** 테스트에서 직접 제어할 수 있는 서버 기준 시계를 제공한다. */
		@Bean MutableClock clock() { return new MutableClock(NOW); }
		/** 서비스에 테스트 시계 기반의 서버 시각 제공자를 주입한다. */
		@Bean TimeProvider timeProvider(Clock clock) { return new TimeProvider(clock); }
	}
}
