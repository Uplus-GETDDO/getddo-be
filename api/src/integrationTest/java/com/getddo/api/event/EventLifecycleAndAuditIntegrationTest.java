package com.getddo.api.event;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.audit.repository.AuditLogRepository;
import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.drawing.service.DrawSnapshotService;
import com.getddo.core.event.domain.EventRegistration;
import com.getddo.core.event.domain.EventType;
import com.getddo.core.event.domain.MembershipRule;
import com.getddo.core.event.domain.RegisteredEvent;
import com.getddo.core.event.service.EventCancellationService;
import com.getddo.core.event.service.EventLifecycleService;
import com.getddo.core.event.service.EventModificationService;
import com.getddo.core.event.service.EventRegistrationService;
import com.getddo.db.common.util.UuidBinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** 시간 경계·취소와의 선잠금·자동 추첨 조회 연결 및 실제 감사 저장 롤백을 검증한다. */
@ApiIntegrationTest
class EventLifecycleAndAuditIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	@Autowired private JdbcTemplate jdbc;
	@Autowired private EventLifecycleService lifecycle;
	@Autowired private EventRegistrationService registrations;
	@Autowired private EventModificationService modifications;
	@Autowired private EventCancellationService cancellations;
	@Autowired private DrawSnapshotService drawings;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private Clock clock;
	@MockitoSpyBean private AuditLogRepository audits;
	private UUID admin;
	private final List<UUID> eventIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		admin = UUID.randomUUID();
		jdbc.update("insert into users(id,name,role,status,created_at,updated_at) values(?,'이벤트 테스트','ADMIN','ACTIVE',?,?)",
				bytes(admin), utc(NOW), utc(NOW));
	}

	@AfterEach
	void cleanUp() {
		for (UUID id : eventIds) {
			jdbc.update("delete from audit_logs where target_type='EVENT' and target_id=?", bytes(id));
			jdbc.update("delete from notifications where event_id=?", bytes(id));
			jdbc.update("update notification_jobs set source_job_id=null where event_id=?", bytes(id));
			jdbc.update("delete from notification_jobs where event_id=?", bytes(id));
			jdbc.update("delete from event_prizes where event_id=?", bytes(id));
			jdbc.update("delete from events where id=?", bytes(id));
		}
		jdbc.update("delete from audit_logs where actor_id=?", bytes(admin));
		jdbc.update("delete from users where id=?", bytes(admin));
	}

	@Test
	@DisplayName("시작·마감 정각과 서버 중단 중 지난 모집을 복구하고 삭제·취소·추첨 상태는 유지한다")
	void advancesRecruitmentAndMakesOverdueEventVisibleToDrawing() {
		// given
		UUID startsNow = seed("SCHEDULED", 0, 3600);
		UUID missed = seed("SCHEDULED", -7200, -3600);
		UUID endsNow = seed("OPEN", -3600, 0);
		UUID future = seed("SCHEDULED", 1, 3600);
		UUID canceled = seed("CANCELED", -7200, -3600);
		UUID confirmed = seed("DRAW_CONFIRMED", -7200, -3600);
		UUID published = seed("PUBLISHED", -7200, -3600);
		UUID deleted = seed("SCHEDULED", -7200, -3600);
		jdbc.update("update events set deleted_at=? where id=?", utc(NOW), bytes(deleted));
		// GD-86은 저장 상태가 늦게 반영돼도 마감 +1분부터 최초 추첨을 허용한다.
		assertThat(drawings.findInitialDue()).contains(missed);
		// when / then
		assertThat(lifecycle.advanceDue(20)).isEqualTo(3);
		assertThat(status(startsNow)).isEqualTo("OPEN");
		assertThat(status(missed)).isEqualTo("CLOSED");
		assertThat(status(endsNow)).isEqualTo("CLOSED");
		assertThat(status(future)).isEqualTo("SCHEDULED");
		assertThat(status(canceled)).isEqualTo("CANCELED");
		assertThat(status(confirmed)).isEqualTo("DRAW_CONFIRMED");
		assertThat(status(published)).isEqualTo("PUBLISHED");
		assertThat(status(deleted)).isEqualTo("SCHEDULED");
		assertThat(drawings.findInitialDue()).contains(missed).doesNotContain(endsNow, canceled, confirmed, published, deleted);
		assertThat(lifecycle.advanceDue(20)).isZero();
		assertThat(auditCount(missed, "EVENT_STATUS_CHANGE")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select actor_id is null from audit_logs where target_id=?", Boolean.class, bytes(missed))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("취소 중 잠긴 이벤트는 상태 전환이 기다리지 않고 건너뛰며 취소 커밋·롤백 결과를 따른다")
	void skipsLockedCancellationAndRespectsItsOutcome(boolean rollback) throws Exception {
		// given
		UUID id = seed("SCHEDULED", -7200, -3600);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate transaction = new TransactionTemplate(transactions);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> cancellation = executor.submit(() -> transaction.executeWithoutResult(tx -> {
				cancellations.cancel(id, admin, "취소 경합");
				held.countDown();
				try {
					if (!release.await(20, TimeUnit.SECONDS)) { throw new IllegalStateException("커밋 대기 실패"); }
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(interrupted);
				}
				if (rollback) { tx.setRollbackOnly(); }
			}));
			try {
				assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
				// when: 취소의 이벤트 X 잠금이 확실히 유지되는 동안 별도 트랜잭션에서 호출한다.
				assertThat(executor.submit(() -> lifecycle.advanceDue(20)).get(5, TimeUnit.SECONDS)).isZero();
			} finally { release.countDown(); }
			cancellation.get(20, TimeUnit.SECONDS);
		}
		// then
		assertThat(lifecycle.advanceDue(20)).isEqualTo(rollback ? 1 : 0);
		assertThat(status(id)).isEqualTo(rollback ? "CLOSED" : "CANCELED");
		assertThat(auditCount(id, "EVENT_STATUS_CHANGE")).isEqualTo(rollback ? 1 : 0);
		assertThat(auditCount(id, "EVENT_CANCEL")).isEqualTo(rollback ? 0 : 1);
	}

	@Test
	@DisplayName("반복 감사 실패는 해당 이벤트만 롤백하고 같은 묶음·다음 묶음의 이벤트는 독립 커밋한다")
	void auditFailureDoesNotBlockOtherEventsOrLaterPages() {
		// given
		UUID before = seed(new UUID(0, 101), "SCHEDULED", 0, 3600);
		UUID failed = seed(new UUID(0, 102), "SCHEDULED", 0, 3600);
		UUID after = seed(new UUID(0, 103), "SCHEDULED", 0, 3600);
		UUID nextPage = seed(new UUID(0, 104), "SCHEDULED", 0, 3600);
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			AuditLogCommand command = invocation.getArgument(0);
			if (command.getTargetId().equals(failed)) { throw new IllegalStateException("이벤트별 감사 실패 주입"); }
			return result;
		}).when(audits).append(any());
		// when / then
		assertThat(lifecycle.advanceDue(3)).isEqualTo(2);
		assertThat(status(before)).isEqualTo("OPEN");
		assertThat(status(after)).isEqualTo("OPEN");
		assertThat(auditCount(before, "EVENT_STATUS_CHANGE")).isEqualTo(1);
		assertThat(auditCount(after, "EVENT_STATUS_CHANGE")).isEqualTo(1);
		assertThat(status(failed)).isEqualTo("SCHEDULED");
		assertThat(auditCount(failed, "EVENT_STATUS_CHANGE")).isZero();
		assertThat(status(nextPage)).isEqualTo("SCHEDULED");
		assertThat(lifecycle.advanceDue(1)).isEqualTo(1);
		assertThat(status(nextPage)).isEqualTo("OPEN");
		assertThat(auditCount(nextPage, "EVENT_STATUS_CHANGE")).isEqualTo(1);
		assertThat(lifecycle.advanceDue(20)).isZero();
		assertThat(status(failed)).isEqualTo("SCHEDULED");
		assertThat(auditCount(failed, "EVENT_STATUS_CHANGE")).isZero();
		reset(audits);
		assertThat(lifecycle.advanceDue(20)).isEqualTo(1);
		assertThat(status(failed)).isEqualTo("OPEN");
		assertThat(auditCount(failed, "EVENT_STATUS_CHANGE")).isEqualTo(1);
	}

	@Test
	@DisplayName("두 상태 워커는 같은 due 후보를 읽어도 잠긴 이벤트를 건너뛰고 각 상태·감사를 한 번만 커밋한다")
	void lifecycleWorkersSkipEachOthersLocksAndProcessOtherDueEvents() throws Exception {
		// given
		UUID first = seed(new UUID(0, 201), "SCHEDULED", 0, 3600);
		UUID second = seed(new UUID(0, 202), "SCHEDULED", 0, 3600);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			AuditLogCommand command = invocation.getArgument(0);
			if (command.getTargetId().equals(first)) {
				held.countDown();
				if (!release.await(20, TimeUnit.SECONDS)) { throw new IllegalStateException("워커 커밋 대기 실패"); }
			}
			return invocation.callRealMethod();
		}).when(audits).append(any());
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> firstWorker = executor.submit(() -> lifecycle.advanceDue(2));
			try {
				assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
				// when: A가 첫 이벤트의 상태를 flush한 뒤 아직 커밋하지 않은 동안 B도 같은 후보를 조회한다.
				assertThat(executor.submit(() -> lifecycle.advanceDue(2)).get(5, TimeUnit.SECONDS)).isEqualTo(1);
				assertThat(status(first)).isEqualTo("SCHEDULED");
				assertThat(status(second)).isEqualTo("OPEN");
				assertThat(auditCount(first, "EVENT_STATUS_CHANGE")).isZero();
				assertThat(auditCount(second, "EVENT_STATUS_CHANGE")).isEqualTo(1);
			} finally { release.countDown(); }
			assertThat(firstWorker.get(20, TimeUnit.SECONDS)).isEqualTo(1);
		}
		// then
		assertThat(status(first)).isEqualTo("OPEN");
		assertThat(status(second)).isEqualTo("OPEN");
		assertThat(auditCount(first, "EVENT_STATUS_CHANGE")).isEqualTo(1);
		assertThat(auditCount(second, "EVENT_STATUS_CHANGE")).isEqualTo(1);
		assertThat(lifecycle.advanceDue(20)).isZero();
	}

	@Test
	@DisplayName("등록·수정은 저장된 경품 ID와 변경 전후 설정을 감사에 남기며 같은 설정의 재수정은 이력을 추가하지 않는다")
	void recordsCreationAndModificationWithoutNoOpDuplicates() {
		// given
		RegisteredEvent original = register();
		// when
		RegisteredEvent updated = modifications.modify(original.getId(), modification(original, "변경", original.getStartsAt()));
		modifications.modify(original.getId(), modification(updated, "변경", updated.getStartsAt()));
		// then
		assertThat(auditCount(original.getId(), "EVENT_CREATE")).isEqualTo(1);
		assertThat(auditCount(original.getId(), "EVENT_UPDATE")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select json_unquote(json_extract(before_data,'$.title')) from audit_logs where target_id=? and action='EVENT_UPDATE'",
				String.class, bytes(original.getId()))).isEqualTo("등록");
		assertThat(jdbc.queryForObject("select json_unquote(json_extract(after_data,'$.title')) from audit_logs where target_id=? and action='EVENT_UPDATE'",
				String.class, bytes(original.getId()))).isEqualTo("변경");
		assertThat(jdbc.queryForObject("select json_unquote(json_extract(after_data,'$.prizes[0].id')) from audit_logs where target_id=? and action='EVENT_CREATE'",
				String.class, bytes(original.getId()))).isEqualTo(original.getPrizes().getFirst().getId().toString());
	}

	@Test
	@DisplayName("등록 감사 실패는 이벤트와 경품을 남기지 않는다")
	void creationAuditFailureRollsBackEventAndPrizes() {
		// given
		failAfterAuditAppend();
		// when / then
		assertThatThrownBy(this::register).hasStackTraceContaining("감사 실패 주입");
		assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id=?", Long.class, bytes(admin))).isZero();
		// 실패한 등록은 반환값을 받지 못한다. 이 테스트만 사용하는 고유 이미지 키로 부모·경품 잔존을 확인한다.
		assertThat(jdbc.queryForObject("select count(*) from events where image_key=?", Long.class, "events/" + admin)).isZero();
	}

	@Test
	@DisplayName("수정 감사 실패는 이벤트·경품과 일정 변경 알림을 모두 롤백한다")
	void modificationAuditFailureRollsBackSettingsPrizesAndNotice() {
		// given
		RegisteredEvent original = register();
		failAfterAuditAppend();
		// when / then
		assertThatThrownBy(() -> modifications.modify(original.getId(), modification(original, "변경", original.getStartsAt().plusSeconds(600))))
				.hasStackTraceContaining("감사 실패 주입");
		assertThat(jdbc.queryForObject("select title from events where id=?", String.class, bytes(original.getId()))).isEqualTo("등록");
		assertThat(jdbc.queryForObject("select starts_at from events where id=?", LocalDateTime.class, bytes(original.getId()))).isEqualTo(utc(original.getStartsAt()));
		assertThat(jdbc.queryForObject("select name from event_prizes where event_id=?", String.class, bytes(original.getId()))).isEqualTo("등록 경품");
		assertThat(jdbc.queryForObject("select count(*) from notification_jobs where event_id=?", Long.class, bytes(original.getId()))).isZero();
		assertThat(auditCount(original.getId(), "EVENT_CREATE")).isEqualTo(1);
		assertThat(auditCount(original.getId(), "EVENT_UPDATE")).isZero();
	}

	private void failAfterAuditAppend() {
		doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("감사 실패 주입"); }).when(audits).append(any());
	}

	private RegisteredEvent register() {
		RegisteredEvent result = registrations.register(new EventRegistration(admin, "등록", "설명", "events/" + admin,
				EventType.NO_TICKET, false, null, MembershipRule.vip, NOW.plusSeconds(3600), NOW.plusSeconds(7200),
				List.of(new EventRegistration.Prize(1, "등록 경품", null, null, 1))));
		eventIds.add(result.getId());
		return result;
	}

	private EventRegistration modification(RegisteredEvent event, String title, Instant startsAt) {
		return new EventRegistration(admin, title, "변경 설명", event.getImageKey(), EventType.NO_TICKET, false, null,
				MembershipRule.vip, startsAt, event.getEndsAt(), event.getPrizes().stream().map(prize ->
						new EventRegistration.Prize(prize.getId(), prize.getRank(), title + " 경품", null, null, 1)).toList());
	}

	private UUID seed(String status, long startsOffset, long endsOffset) {
		return seed(UUID.randomUUID(), status, startsOffset, endsOffset);
	}

	private UUID seed(UUID id, String status, long startsOffset, long endsOffset) {
		eventIds.add(id);
		jdbc.update("""
				insert into events(id,title,description,event_type,weighting_enabled,membership_rule,starts_at,ends_at,status,created_at,updated_at)
				values(?,'상태 전환','설명','NO_TICKET',false,'vip',?,?,?,?,?)
				""", bytes(id), utc(NOW.plusSeconds(startsOffset)), utc(NOW.plusSeconds(endsOffset)), status, utc(NOW.minusSeconds(86400)), utc(NOW.minusSeconds(86400)));
		return id;
	}

	private String status(UUID id) { return jdbc.queryForObject("select status from events where id=?", String.class, bytes(id)); }
	private long auditCount(UUID id, String action) {
		return jdbc.queryForObject("select count(*) from audit_logs where target_id=? and action=?", Long.class, bytes(id), action);
	}
	private byte[] bytes(UUID id) { return UuidBinary.toBytes(id); }
	private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
