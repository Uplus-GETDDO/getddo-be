package com.getddo.db.abuse;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.abuse.service.AbuseCaseSaveService;
import com.getddo.core.abuse.service.AbuseCaseTransactionService;
import com.getddo.core.common.ratelimit.EntryBlock;
import com.getddo.db.abuse.repository.AbuseCaseWriteRepositoryImpl;
import com.getddo.db.support.MySqlTestConfiguration;
import com.getddo.db.ticket.TicketGrantSeeds;

/** 실제 MySQL 제약·트랜잭션 rollback 뒤 중복 확인을 검증한다. 로컬 실행하지 않는다. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
		"spring.jpa.properties.hibernate.jdbc.time_zone=UTC"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// JPA 슬라이스에서도 운영과 같은 Repository 예외 변환을 적용해 중복 확인 경로를 검증한다.
@ImportAutoConfiguration(PersistenceExceptionTranslationAutoConfiguration.class)
@ContextConfiguration(classes = AbusePersistenceTestConfiguration.class)
@ActiveProfiles("test")
@Import({MySqlTestConfiguration.class, AbuseCaseWriteRepositoryImpl.class,
		AbuseCaseTransactionService.class, AbuseCaseSaveService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AbuseCaseWriteIntegrationTest {
	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	private AbuseCaseSaveService saves;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("동일 전달은 한 행을 유지하고 다른 PK의 복합 제약 충돌은 거절한다")
	void identicalRedeliveryIsOneRowButDifferentPkUniqueCollisionStillFails() {
		// given
		UUID user = new TicketGrantSeeds(jdbc).user();
		EntryBlock block = block(UUID.randomUUID(), user, Instant.parse("2026-10-22T20:00:00.123Z"));
		try {
			// when
			saves.save(block);
			assertOriginalStored(block);
			saves.save(block);
			assertOriginalStored(block);
			// then
			assertThat(jdbc.queryForObject("select count(*) from abuse_cases where user_id = ?", Long.class, bytes(user))).isEqualTo(1);
			assertThatThrownBy(() -> saves.save(block(UUID.randomUUID(), user, block.getBlockedAt())))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertOriginalStored(block);
		} finally {
			jdbc.update("delete from abuse_cases where user_id = ?", bytes(user));
			jdbc.update("delete from users where id = ?", bytes(user));
		}
	}

	@Test
	@DisplayName("변경된 원본과 FK 실패를 중복 저장 성공으로 인정하지 않는다")
	void changedOriginalAndForeignKeyFailureAreNotAcknowledgedAsDuplicate() {
		// given
		UUID user = new TicketGrantSeeds(jdbc).user();
		EntryBlock block = block(UUID.randomUUID(), user, Instant.parse("2026-10-22T20:00:00.123Z"));
		try {
			saves.save(block);
			assertOriginalStored(block);
			// when / then
			assertThatThrownBy(() -> saves.save(block(block.getId(), user, block.getBlockedAt().plusSeconds(1))))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertOriginalStored(block);
			assertThatThrownBy(() -> saves.save(block(UUID.randomUUID(), UUID.randomUUID(), block.getBlockedAt())))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertOriginalStored(block);
		} finally {
			jdbc.update("delete from abuse_cases where user_id = ?", bytes(user));
			jdbc.update("delete from users where id = ?", bytes(user));
		}
	}

	@Test
	@DisplayName("같은 이력의 동시 전달은 두 호출 모두 성공하고 최초 한 행만 유지한다")
	void concurrentRedeliveryPreservesOneOriginalRow() throws Exception {
		// given
		UUID user = new TicketGrantSeeds(jdbc).user();
		EntryBlock original = block(UUID.randomUUID(), user, Instant.parse("2026-10-22T20:00:00.123Z"));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Void> delivery = () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 전달 시작을 기다리다 시간이 지났다.");
			}
			saves.save(original);
			return null;
		};
		try {
			// when: 두 호출이 각각 독립 트랜잭션을 열도록 동시에 출발시킨다.
			try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
				Future<Void> first = pool.submit(delivery);
				Future<Void> second = pool.submit(delivery);
				assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
				start.countDown();
				// then: 예외를 숨기지 않고 두 호출의 정상 반환과 최종 원본을 모두 확인한다.
				assertThat(first.get(20, TimeUnit.SECONDS)).isNull();
				assertThat(second.get(20, TimeUnit.SECONDS)).isNull();
			}
			assertOriginalStored(original);
		} finally {
			start.countDown();
			jdbc.update("delete from abuse_cases where user_id = ?", bytes(user));
			jdbc.update("delete from users where id = ?", bytes(user));
		}
	}

	/** 사용자별 행이 하나이며 UTC 시각·ID·일곱 근거가 최초 값과 같은지 실제 DB에서 읽어 확인한다. */
	private void assertOriginalStored(EntryBlock original) {
		assertThat(jdbc.queryForObject("select count(*) from abuse_cases where user_id = ?",
				Long.class, bytes(original.getUserId()))).isEqualTo(1);
		jdbc.queryForObject("""
				select id, user_id, event_id, occurred_at, detected_at, blocked_at, blocked_until, evidence
				from abuse_cases where user_id = ?
				""", (row, index) -> {
			assertThat(row.getBytes("id")).containsExactly(bytes(original.getId()));
			assertThat(row.getBytes("user_id")).containsExactly(bytes(original.getUserId()));
			assertThat(row.getBytes("event_id")).isNull();
			assertThat(row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
					.isEqualTo(original.getOccurredAt());
			assertThat(row.getObject("detected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
					.isEqualTo(original.getBlockedAt());
			assertThat(row.getObject("blocked_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
					.isEqualTo(original.getBlockedAt());
			assertThat(row.getObject("blocked_until", LocalDateTime.class).toInstant(ZoneOffset.UTC))
					.isEqualTo(original.getBlockedUntil());
			JsonNode expected = json.valueToTree(Map.of(
					"ruleVersion", original.getEvidence().getRuleVersion(),
					"triggerScope", original.getEvidence().getTriggerScope().name(),
					"windowStartedAt", original.getEvidence().getWindowStartedAt().toString(),
					"windowEndsAt", original.getEvidence().getWindowEndsAt().toString(),
					"requestLimit", original.getEvidence().getRequestLimit(),
					"rejectedCount", original.getEvidence().getRejectedCount(),
					"rejectionThreshold", original.getEvidence().getRejectionThreshold()));
			assertThat(json.readTree(row.getString("evidence"))).isEqualTo(expected);
			return true;
		}, bytes(original.getUserId()));
	}

	private EntryBlock block(UUID id, UUID user, Instant now) {
		return new EntryBlock(id, user, null, now.plusNanos(456000), now, now.plusSeconds(600),
				new AbuseEvidence("entry-v1", AbuseTriggerScope.USER, now, now.plusSeconds(60), 20, 20, 20));
	}
}
