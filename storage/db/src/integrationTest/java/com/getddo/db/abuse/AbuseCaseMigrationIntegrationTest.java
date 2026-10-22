package com.getddo.db.abuse;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.db.support.MySqlTestContainers;
import com.getddo.db.ticket.TicketGrantSeeds;

import static com.getddo.db.ticket.TicketGrantSeeds.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V030의 DB 내부 자동 gate가 비어 있지 않은 기존 테이블을 무삭제 거절하는지 검증한다. */
@Testcontainers
class AbuseCaseMigrationIntegrationTest {

	@Container
	static final MySQLContainer MYSQL = MySqlTestContainers.create();

	@Test
	@DisplayName("기존 검토 행이 있으면 V030을 중단하고 원본 행·스키마를 그대로 보존한다")
	void refusesNonEmptyTableWithoutChangingExistingData() {
		// given
		Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.locations("classpath:db/migration").target("29").load().migrate();
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
		UUID user = new TicketGrantSeeds(jdbc).user();
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into abuse_cases (id, user_id, request_id, detection_key, detection_type,
				  detection_reason, evidence, occurred_at, detected_at, review_status)
				values (?, ?, 'migration-test', 'migration-test', 'LEGACY', '기존 검토 근거', '{}',
				  '2026-10-21 06:00:00', '2026-10-21 06:00:00', 'PENDING')
				""", bytes(id), bytes(user));
		Map<String, Object> before = jdbc.queryForMap("select * from abuse_cases where id = ?", bytes(id));
		Flyway latest = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.locations("classpath:db/migration").load();

		// when / then
		assertThatThrownBy(latest::migrate).isInstanceOf(FlywayException.class)
				.rootCause().isInstanceOfSatisfying(SQLException.class, cause -> {
					assertThat(cause.getErrorCode()).isEqualTo(3819);
					assertThat(cause.getMessage()).contains("chk_abuse_empty_transition");
				});
		assertThat(jdbc.queryForMap("select * from abuse_cases where id = ?", bytes(id)))
				.usingRecursiveComparison().isEqualTo(before);
		assertThat(jdbc.queryForObject("""
				select count(*) from information_schema.columns
				where table_schema = database() and table_name = 'abuse_cases'
				  and column_name in ('source_type', 'blocked_at', 'blocked_until')
				""", Long.class)).isZero();
		assertThat(jdbc.queryForObject("""
				select count(*) from information_schema.table_constraints
				where constraint_schema = database() and table_name = 'abuse_cases'
				  and constraint_name = 'chk_abuse_empty_transition'
				""", Long.class)).isZero();
	}
}
