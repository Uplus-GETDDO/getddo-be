package com.getddo.api.event;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.audit.repository.AuditLogRepository;
import com.getddo.core.entry.domain.EntryCommand;
import com.getddo.core.entry.exception.EntryException;
import com.getddo.core.entry.service.EntryRecorder;
import com.getddo.core.entry.service.EntryService;
import com.getddo.core.event.domain.EventCancellationResult;
import com.getddo.core.event.service.EventCancellationService;
import com.getddo.core.notification.domain.NotificationJobRequest;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.service.EventChangeNotificationService;
import com.getddo.core.notification.service.NotificationJobService;
import com.getddo.core.ticket.repository.TicketBulkRefundRepository;
import com.getddo.core.ticket.domain.TicketGrade;
import com.getddo.core.user.domain.Membership;
import com.getddo.db.common.util.UuidBinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 응모·차감·반환과 취소 API, 감사·알림 저장의 원자성을 MySQL에서 검증한다. */
@ApiIntegrationTest
class AdminEventCancellationTest {
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	private static final Instant CREATED = NOW.minusSeconds(7200);
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private EventCancellationService cancellations;
	@Autowired private EntryService entries;
	@Autowired private EntryRecorder recorder;
	@Autowired private NotificationJobService jobs;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private MySQLContainer mysql;
	@MockitoBean private Clock clock;
	@MockitoSpyBean private EventChangeNotificationService notifications;
	@MockitoSpyBean private AuditLogRepository audits;
	@MockitoSpyBean private TicketBulkRefundRepository refunds;
	private UUID admin;
	private UUID user;
	private UUID event;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		admin = insertUser("ADMIN", null);
		user = insertUser("USER", "VIP");
		event = UUID.randomUUID();
		jdbc.update("""
				insert into events(id,title,description,event_type,weighting_enabled,max_tickets_per_user,
				membership_rule,starts_at,ends_at,status,created_at,updated_at)
				values(?,'취소 이벤트','설명','TICKET',true,5,'vip',?,?,'OPEN',?,?)
				""", bytes(event), utc(NOW.minusSeconds(3600)), utc(NOW.plusSeconds(3600)), utc(CREATED), utc(CREATED));
		seedTickets();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("delete from audit_logs where target_id=?", bytes(event));
		jdbc.update("delete from notifications where event_id=?", bytes(event));
		jdbc.update("update notification_jobs set source_job_id=null where event_id=?", bytes(event));
		jdbc.update("delete from notification_jobs where event_id=?", bytes(event));
		jdbc.update("delete h from ticket_histories h join tickets t on t.id=h.ticket_id where t.user_id=? and h.operation_type='REFUND'", bytes(user));
		jdbc.update("delete h from ticket_histories h join tickets t on t.id=h.ticket_id where t.user_id=?", bytes(user));
		jdbc.update("delete from tickets where user_id=?", bytes(user));
		jdbc.update("delete from event_entries where event_id=?", bytes(event));
		jdbc.update("delete from event_participants where event_id=?", bytes(event));
		jdbc.update("delete from events where id=?", bytes(event));
		jdbc.update("delete from attendance_reward_claims where user_id=?", bytes(user));
		jdbc.update("delete from attendances where user_id=?", bytes(user));
		jdbc.update("delete from reward_policies where created_by=?", bytes(user));
		jdbc.update("delete from users where id in (?,?)", bytes(admin), bytes(user));
	}

	@ParameterizedTest
	@ValueSource(strings = {"OPEN", "PUBLISHED", "CLOSED"})
	@DisplayName("진행·발표·종료 상태에서도 모든 성공 응모의 차감분을 반환하고 감사·취소 안내를 한 번만 기록한다")
	void cancelsAndRefundsAllEntries(String previousStatus) throws Exception {
		// given: 같은 사용자의 추가 응모도 각각 원본 USE를 따라 반환한다.
		entries.enter(command(UUID.randomUUID(), 1));
		entries.enter(command(UUID.randomUUID(), 2));
		assertThatThrownBy(() -> entries.enter(command(UUID.randomUUID(), 6))).isInstanceOf(EntryException.class);
		UUID startJob = startNotification();
		jdbc.update("update events set status=? where id=?", previousStatus, bytes(event));
		// when
		cancel(admin, event, "운영 취소").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.eventId").value(event.toString()))
				.andExpect(jsonPath("$.data.previousStatus").value(previousStatus))
				.andExpect(jsonPath("$.data.status").value("CANCELED"))
				.andExpect(jsonPath("$.data.refundedTicketCount").value(3))
				.andExpect(jsonPath("$.data.processedAt").value(NOW.toString()));
		// then
		assertThat(storedStatus()).isEqualTo("CANCELED");
		assertThat(canceledAt()).isEqualTo(utc(NOW));
		assertThat(count("select count(*) from tickets where user_id=? and status='RETURNED' and grade='BRONZE' and expires_at=?",
				bytes(user), utc(Instant.parse("2026-11-30T15:00:00Z")))).isEqualTo(3);
		assertThat(refundCount()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select reason from audit_logs where target_id=?", String.class, bytes(event))).isEqualTo("운영 취소");
		assertThat(jdbc.queryForObject("select json_unquote(json_extract(before_data,'$.status')) from audit_logs where target_id=?",
				String.class, bytes(event))).isEqualTo(previousStatus);
		assertThat(jdbc.queryForObject("select json_unquote(json_extract(payload,'$.generationStoppedBy')) from notification_jobs where id=?",
				String.class, bytes(startJob))).isNotNull();
		assertThat(jobs.processNextJob()).isTrue();
		assertThat(count("select count(*) from notifications n join notification_jobs j on j.id=n.job_id where n.event_id=? and j.notification_type='EVENT_CANCELED'", bytes(event))).isEqualTo(1);
		when(clock.instant()).thenReturn(NOW.plusSeconds(20));
		cancel(admin, event, "다시 취소").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT-017"));
		assertThat(canceledAt()).isEqualTo(utc(NOW));
		assertThat(count("select count(*) from audit_logs where target_id=?", bytes(event))).isEqualTo(1);
		assertThat(count("select count(*) from notification_jobs where event_id=? and notification_type='EVENT_CANCELED'", bytes(event))).isEqualTo(1);
		assertThat(refundCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("응모권 미사용 응모는 반환 없이 취소하고 잘못된 입력·일반 사용자·삭제·미존재 이벤트는 거절한다")
	void handlesFreeEntriesAndRejectsInvalidRequests() throws Exception {
		// given / when / then
		cancel(user, event, "취소").andExpect(status().isForbidden());
		cancel(admin, event, " ").andExpect(status().isBadRequest());
		cancel(admin, event, "😀".repeat(16_384)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVENT-016"));
		cancel(admin, UUID.randomUUID(), "취소").andExpect(status().isNotFound());
		jdbc.update("update events set deleted_at=? where id=?", utc(NOW), bytes(event));
		cancel(admin, event, "취소").andExpect(status().isNotFound());
		jdbc.update("update events set deleted_at=null,event_type='NO_TICKET',weighting_enabled=false,max_tickets_per_user=null where id=?", bytes(event));
		entries.enter(new EntryCommand(user, false, Membership.VIP, event, UUID.randomUUID(), Map.of()));
		cancel(admin, event, "무료 이벤트 취소").andExpect(status().isOk()).andExpect(jsonPath("$.data.refundedTicketCount").value(0));
		assertThat(count("select count(*) from tickets where user_id=? and status='AVAILABLE'", bytes(user))).isEqualTo(5);
	}

	@ParameterizedTest
	@ValueSource(strings = {"refund", "notification", "audit"})
	@DisplayName("반환·알림·감사 저장 중 실패하면 취소 상태·시각·반환 이력·알림·감사를 모두 롤백하고 다시 취소할 수 있다")
	void rollsBackEntireOperation(String failureAt) {
		// given
		entries.enter(command(UUID.randomUUID(), 1));
		entries.enter(command(UUID.randomUUID(), 1));
		UUID startJob = startNotification();
		if (failureAt.equals("refund")) {
			doAnswer(invocation -> {
				invocation.callRealMethod();
				throw new IllegalStateException("묶음 반환 저장 후 테스트 실패");
			})
					.when(refunds).saveRefunds(any(), any());
		} else if (failureAt.equals("notification")) {
			doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("테스트 실패"); })
					.when(notifications).register(any());
		} else {
			doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("테스트 실패"); })
					.when(audits).append(any());
		}
		// when / then
		assertThatThrownBy(() -> cancellations.cancel(event, admin, "운영 취소"))
				.isInstanceOf(RuntimeException.class).hasStackTraceContaining("테스트 실패");
		assertThat(storedStatus()).isEqualTo("OPEN");
		assertThat(canceledAt()).isNull();
		assertThat(jdbc.queryForObject("select updated_at from events where id=?", LocalDateTime.class, bytes(event))).isEqualTo(utc(CREATED));
		assertThat(count("select count(*) from tickets where user_id=? and status='SPENT'", bytes(user))).isEqualTo(2);
		assertThat(refundCount()).isZero();
		assertThat(count("select count(*) from audit_logs where target_id=?", bytes(event))).isZero();
		assertThat(count("select count(*) from notification_jobs where event_id=? and notification_type='EVENT_CANCELED'", bytes(event))).isZero();
		assertThat(jdbc.queryForObject("select json_unquote(json_extract(payload,'$.generationStoppedBy')) from notification_jobs where id=?",
				String.class, bytes(startJob))).isNull();
		// 실패 주입만 해제하고 같은 취소를 다시 처리한다.
		org.mockito.Mockito.reset(refunds, notifications, audits);
		assertThat(cancellations.cancel(event, admin, "운영 취소").getRefundedTicketCount()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("응모·취소의 실제 이벤트 잠금 경합에서 먼저 확정된 응모는 반환하고 취소 이후의 신규 응모는 차단한다")
	void serializesEntryAndCancellation(boolean cancellationFirst) throws Exception {
		// given
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		EntryCommand request = command(UUID.randomUUID(), 1);
		TransactionTemplate transaction = new TransactionTemplate(transactions);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		Callable<String> cancellation = () -> {
			EventCancellationResult result = cancellations.cancel(event, admin, "동시 취소");
			return "CANCELED:" + result.getRefundedTicketCount();
		};
		Callable<String> entry = () -> {
			try { entries.enter(request); return "ACCEPTED"; }
			catch (EntryException failure) { return failure.getErrorCode().getCode(); }
		};
		// 첫 업무만 외부 트랜잭션에서 커밋 직전에 멈춰 실제 잠금 대기를 관측한다.
		Callable<String> firstOperation = () -> transaction.execute(ignored -> {
			String result = cancellationFirst ? "CANCELED:" + cancellations.cancel(event, admin, "동시 취소").getRefundedTicketCount()
					: (recorder.record(request).isCreated() ? "ACCEPTED" : "REPLAYED");
			held.countDown();
			try {
				if (!release.await(20, TimeUnit.SECONDS)) { throw new IllegalStateException("커밋 대기 시간 초과"); }
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(interrupted);
			}
			return result;
		});
		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<String> first = executor.submit(firstOperation);
			Future<String> second;
			try {
				assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
				second = executor.submit(cancellationFirst ? entry : cancellation);
				awaitEventLockWait();
			} finally { release.countDown(); }
			// then
			assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(cancellationFirst ? "CANCELED:0" : "ACCEPTED");
			assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(cancellationFirst ? "ENTRY-005" : "CANCELED:1");
		}
		assertThat(storedStatus()).isEqualTo("CANCELED");
		assertThat(count("select count(*) from tickets where user_id=? and status='SPENT'", bytes(user))).isZero();
		assertThat(refundCount()).isEqualTo(cancellationFirst ? 0 : 1);
	}

	private void awaitEventLockWait() throws Exception {
		try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
				PreparedStatement statement = root.prepareStatement("""
					select count(*) from performance_schema.data_lock_waits w
					join performance_schema.data_locks l on l.engine_lock_id=w.blocking_engine_lock_id and l.engine=w.engine
					where l.object_schema=? and l.object_name='events'
					""")) {
			statement.setString(1, mysql.getDatabaseName());
			await().atMost(Duration.ofSeconds(10)).until(() -> {
				try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getInt(1) > 0; }
			});
		}
	}

	private UUID startNotification() {
		UUID job = jobs.register(new NotificationJobRequest("cancel-test:" + event, NotificationType.EVENT_START,
				event, null, "시작 안내", "곧 시작", "/events/" + event, NOW, List.of(user)));
		assertThat(jobs.processNextJob()).isTrue();
		return job;
	}

	@Test
	@DisplayName("발표 대기 이벤트의 5만 응모를 100개 SQL 묶음으로 반환하고 취소 API 응답을 확인한다")
	void cancelsFiftyThousandEntriesInBatches() throws Exception {
		seedBulkEntries(50_000);
		long started = System.nanoTime();
		cancel(admin, event, "대량 취소").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELED"))
				.andExpect(jsonPath("$.data.refundedTicketCount").value(50_000));
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		org.slf4j.LoggerFactory.getLogger(getClass()).info("50,000 entries cancellation API completed in {} ms", elapsedMillis);
		assertThat(storedStatus()).isEqualTo("CANCELED");
		assertThat(refundCount()).isEqualTo(50_000);
		assertThat(count("select count(*) from tickets where user_id=? and status='RETURNED' and version=3 and expires_at=?",
				bytes(user), utc(Instant.parse("2026-11-30T15:00:00Z")))).isEqualTo(50_000);
		assertThat(count("select count(*) from audit_logs where target_id=? and action='EVENT_CANCEL'", bytes(event))).isEqualTo(1);
		verify(refunds, times(100)).saveRefunds(any(), any());
	}

	@Test
	@DisplayName("두 번째 묶음 저장 실패는 앞선 500건도 롤백하고 재시도 시 중복 없이 반환한다")
	void rollsBackEarlierBatchesWhenLaterBatchFails() {
		seedBulkEntries(501);
		AtomicInteger savedBatches = new AtomicInteger();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			if (savedBatches.incrementAndGet() == 2) {
				throw new IllegalStateException("두 번째 묶음 실패");
			}
			return null;
		}).when(refunds).saveRefunds(any(), any());
		assertThatThrownBy(() -> cancellations.cancel(event, admin, "대량 취소"))
				.hasStackTraceContaining("두 번째 묶음 실패");
		assertThat(storedStatus()).isEqualTo("DRAW_CONFIRMED");
		assertThat(canceledAt()).isNull();
		assertThat(refundCount()).isZero();
		assertThat(count("select count(*) from tickets where user_id=? and status='SPENT' and version=2", bytes(user))).isEqualTo(501);
		assertThat(count("select count(*) from audit_logs where target_id=?", bytes(event))).isZero();
		org.mockito.Mockito.reset(refunds);
		assertThat(cancellations.cancel(event, admin, "대량 취소").getRefundedTicketCount()).isEqualTo(501);
		assertThat(refundCount()).isEqualTo(501);
	}

	private void seedBulkEntries(int quantity) {
		// 응모 수를 늘리되 실제로 허용되는 상한 없는 가중치 이벤트로 구성한다.
		UUID participant = UUID.randomUUID();
		byte[] claim = jdbc.queryForObject("select id from attendance_reward_claims where user_id=?", byte[].class, bytes(user));
		jdbc.update("update attendance_reward_claims set ticket_count=? where id=?", quantity + 5, claim);
		jdbc.update("update events set status='DRAW_CONFIRMED',max_tickets_per_user=null,ends_at=? where id=?",
				utc(NOW.minusSeconds(60)), bytes(event));
		jdbc.update("insert into event_participants(id,event_id,user_id,created_at,used_ticket_count) values(?,?,?,?,?)",
				bytes(participant), bytes(event), bytes(user), utc(CREATED), quantity);
		String digits = "(select 0 n union all select 1 union all select 2 union all select 3 union all select 4"
				+ " union all select 5 union all select 6 union all select 7 union all select 8 union all select 9)";
		String sequence = "(select a.n+10*b.n+100*c.n+1000*d.n+10000*e.n as n from "
				+ digits + " a cross join " + digits + " b cross join " + digits + " c cross join " + digits + " d cross join " + digits + " e) seq";
		String prefix = event.toString();
		jdbc.update("""
				insert into event_entries(id,event_id,participant_id,user_id,requested_ticket_count,deducted_ticket_count,
				requested_bronze_count,status,created_at)
				select unhex(md5(concat(?,':entry:',n))),?,?,?,1,1,1,'ACCEPTED',? from
				""" + sequence + " where n < ?", prefix, bytes(event), bytes(participant), bytes(user),
				utc(NOW.minusSeconds(120)), quantity);
		jdbc.update("""
				insert into tickets(id,user_id,attendance_reward_claim_id,grade,status,expires_at,version,created_at,updated_at)
				select unhex(md5(concat(?,':ticket:',n))),?,?,'BRONZE','SPENT',?,2,?,? from
				""" + sequence + " where n < ?", prefix, bytes(user), claim, utc(NOW.plusSeconds(86400)),
				utc(CREATED), utc(NOW.minusSeconds(120)), quantity);
		jdbc.update("""
				insert into ticket_histories(id,ticket_id,operation_type,ticket_version,status,expires_at,reason,created_at)
				select unhex(md5(concat(?,':grant:',n))),unhex(md5(concat(?,':ticket:',n))),
				'GRANT',1,'AVAILABLE',?,'지급',? from
				""" + sequence + " where n < ?", prefix, prefix, utc(NOW.plusSeconds(86400)), utc(CREATED), quantity);
		jdbc.update("""
				insert into ticket_histories(id,ticket_id,event_entry_id,operation_type,ticket_version,status,expires_at,reason,created_at)
				select unhex(md5(concat(?,':use:',n))),unhex(md5(concat(?,':ticket:',n))),unhex(md5(concat(?,':entry:',n))),
				'USE',2,'SPENT',?,'응모',? from
				""" + sequence + " where n < ?", prefix, prefix, prefix, utc(NOW.plusSeconds(86400)), utc(NOW.minusSeconds(120)), quantity);
	}

	private ResultActions cancel(UUID actorId, UUID eventId, String reason) throws Exception {
		return mvc.perform(post("/api/v1/admin/events/{eventId}/cancel", eventId)
				.header("X-User-ID", actorId).header("X-User-Role", actorId.equals(admin) ? "ADMIN" : "USER")
				.header("X-User-Membership", "vip")
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
	}

	private EntryCommand command(UUID entryId, long quantity) {
		return new EntryCommand(user, false, Membership.VIP, event, entryId, Map.of(TicketGrade.BRONZE, quantity));
	}

	private UUID insertUser(String role, String membership) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into users(id,name,role,status,membership,created_at,updated_at) values(?,'취소 테스트',?,'ACTIVE',?,?,?)",
				bytes(id), role, membership, utc(CREATED), utc(CREATED));
		return id;
	}

	private void seedTickets() {
		UUID policy = UUID.randomUUID();
		UUID attendance = UUID.randomUUID();
		UUID claim = UUID.randomUUID();
		jdbc.update("""
				insert into reward_policies(id,created_by,reward_type,reward_ticket_count,effective_from,effective_until,created_at)
				values(?,?,'ATTENDANCE',1,?,?,?)
				""", bytes(policy), bytes(user), utc(CREATED.minusSeconds(3600)), utc(NOW.plusSeconds(86400)), utc(CREATED));
		jdbc.update("insert into attendances(id,user_id,attendance_date,created_at) values(?,?,?,?)",
				bytes(attendance), bytes(user), LocalDate.of(2026, 10, 15), utc(CREATED));
		jdbc.update("""
				insert into attendance_reward_claims(id,user_id,attendance_id,reward_type,reward_policy_id,reward_date,source_key,ticket_count,created_at)
				values(?,?,?,'DAILY',?,?,'2026-10-15',5,?)
				""", bytes(claim), bytes(user), bytes(attendance), bytes(policy), LocalDate.of(2026, 10, 15), utc(CREATED));
		for (int i = 0; i < 5; i++) {
			jdbc.update("""
					insert into tickets(id,user_id,attendance_reward_claim_id,grade,status,expires_at,version,created_at,updated_at)
					values(?,?,?,'BRONZE','AVAILABLE',?,1,?,?)
					""", bytes(UUID.randomUUID()), bytes(user), bytes(claim), utc(NOW.plusSeconds(86400)), utc(CREATED), utc(CREATED));
		}
	}

	private String storedStatus() { return jdbc.queryForObject("select status from events where id=?", String.class, bytes(event)); }
	private long refundCount() {
		return count("select count(*) from ticket_histories h join tickets t on t.id=h.ticket_id where t.user_id=? and h.operation_type='REFUND'", bytes(user));
	}
	private LocalDateTime canceledAt() { return jdbc.queryForObject("select canceled_at from events where id=?", LocalDateTime.class, bytes(event)); }
	private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
	private byte[] bytes(UUID id) { return UuidBinary.toBytes(id); }
	private LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
