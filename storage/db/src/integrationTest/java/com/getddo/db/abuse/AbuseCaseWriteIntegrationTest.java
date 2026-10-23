package com.getddo.db.abuse;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

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
			saves.save(block);
			// then
			assertThat(jdbc.queryForObject("select count(*) from abuse_cases where user_id = ?", Long.class, bytes(user))).isEqualTo(1);
			assertThatThrownBy(() -> saves.save(block(UUID.randomUUID(), user, block.getBlockedAt())))
					.isInstanceOf(DataIntegrityViolationException.class);
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
			// when / then
			assertThatThrownBy(() -> saves.save(block(block.getId(), user, block.getBlockedAt().plusSeconds(1))))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> saves.save(block(UUID.randomUUID(), UUID.randomUUID(), block.getBlockedAt())))
					.isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			jdbc.update("delete from abuse_cases where user_id = ?", bytes(user));
			jdbc.update("delete from users where id = ?", bytes(user));
		}
	}

	private EntryBlock block(UUID id, UUID user, Instant now) {
		return new EntryBlock(id, user, null, now.plusNanos(456000), now, now.plusSeconds(600),
				new AbuseEvidence("entry-v1", AbuseTriggerScope.USER, now, now.plusSeconds(60), 20, 20, 20));
	}
}
