package com.getddo.db.notification.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.core.notification.domain.NotificationJob;
import com.getddo.db.common.util.UuidBinary;
import com.getddo.db.support.MySqlTestContainers;

import static org.assertj.core.api.Assertions.assertThat;

/** 전용 MySQL에서 실제 선점 SQL의 정렬 계획과 저장소 동작을 인덱스 가시성 대조로 검증한다. */
@Testcontainers
class NotificationJobScheduleIndexIntegrationTest {
	@Container
	static final MySQLContainer MYSQL = MySqlTestContainers.create();
	private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
	private static final String INDEX = "ix_notification_job_schedule";
	private static final int JOB_COUNT = 2336;

	/** 완료2000·대기64·만료임대16·미래200·재시도대기32·활성임대16·중지8개를 준비한다. */
	@BeforeAll
	static void prepareSchemaAndJobs() throws SQLException {
		Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.locations("classpath:db/migration").load().migrate();
		String recipients = IntStream.range(0, 10_000).mapToObj(index -> "\"" + new UUID(0, index + 1) + "\"")
				.collect(Collectors.joining(",", "[", "]"));
		String small = "{\"version\":1,\"title\":\"fixture\",\"body\":\"fixture\",\"recipientIds\":[]}";
		String large = "{\"version\":1,\"title\":\"fixture\",\"body\":\"fixture\",\"recipientIds\":" + recipients + "}";
		String stoppedPayload = large.substring(0, large.length() - 1) + ",\"generationStoppedBy\":\"fixture\"}";
		try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement("""
				insert into notification_jobs
				(id, payload, occurrence_key, notification_type, status, scheduled_at,
				 next_attempt_at, lease_until, attempt_count, created_at, completed_at)
				values (?, cast(? as json), ?, 'RESULT_PUBLISHED', ?, ?, ?, ?, ?, ?, ?)
				""")) {
			connection.setAutoCommit(false);
			for (int index = 0; index < JOB_COUNT; index++) {
				boolean completed = index < 2000;
				boolean expiredLease = index >= 2064 && index < 2080;
				boolean future = index >= 2080 && index < 2280;
				boolean retryWaiting = index >= 2280 && index < 2312;
				boolean activeLease = index >= 2312 && index < 2328;
				boolean stopped = index >= 2328;
				Instant scheduled = completed ? NOW.minusSeconds(172_800)
						: stopped ? NOW.minusSeconds(86_400)
						: future ? NOW.plusSeconds(86_400)
						: retryWaiting || activeLease ? NOW.minusSeconds(600) : NOW.minusSeconds(60);
				insert.setBytes(1, UuidBinary.toBytes(id(index)));
				insert.setString(2, completed || future ? small : stopped ? stoppedPayload : large);
				insert.setString(3, "schedule-index:" + index);
				insert.setString(4, completed ? "COMPLETED" : expiredLease || activeLease ? "PROCESSING" : "PENDING");
				insert.setObject(5, utc(scheduled));
				insert.setObject(6, retryWaiting ? utc(NOW.plusSeconds(86_400)) : null);
				insert.setObject(7, expiredLease ? utc(NOW.minusSeconds(1)) : activeLease ? utc(NOW.plusSeconds(60)) : null);
				insert.setInt(8, completed || expiredLease || activeLease ? 1 : 0);
				insert.setObject(9, utc(NOW.minusSeconds(172_800)));
				insert.setObject(10, completed ? utc(NOW.minusSeconds(86_400)) : null);
				insert.addBatch();
			}
			insert.executeBatch();
			connection.commit();
			try (java.sql.Statement statistics = connection.createStatement()) {
				statistics.execute("analyze table notification_jobs");
			}
		}
	}

	/** 네 행의 결과 순서만 보는 대신 동일 분포·SQL·버퍼에서 새 인덱스의 정렬 제거를 음성 대조한다. */
	@Test
	@DisplayName("2336개 혼합 작업에서 예약 인덱스가 filesort를 제거하고 1만명 작업을 실제 선점한다")
	void avoidsFilesortAndClaimsLargeJobsWithScheduleIndex() throws SQLException {
		try (Connection connection = connect()) {
			// 세션 설정·EXPLAIN·실제 저장소가 같은 연결을 쓴다. 공유 DB 설정은 변경하지 않는다.
			SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			jdbc.execute("set session sort_buffer_size = 262144");
			jdbc.execute("set session optimizer_switch = 'use_invisible_indexes=off'");
			assertThat(jdbc.queryForObject("select count(*) from notification_jobs", Integer.class)).isEqualTo(JOB_COUNT);
			assertThat(jdbc.queryForObject("select @@session.sort_buffer_size", Integer.class)).isEqualTo(262144);

			// 전용 컨테이너에서만 인덱스를 숨겨 V032가 없을 때의 별도 정렬 계획을 대조한다.
			try {
				jdbc.execute("alter table notification_jobs alter index " + INDEX + " invisible");
				// 힌트 없는 이전 쿼리로만 음성 대조한다. 운영 쿼리는 V032 인덱스를 필수로 사용한다.
				Map<String, Object> withoutIndex = jdbc.queryForMap("explain "
						+ NotificationJobRepositoryImpl.CLAIM_NEXT_JOB_SQL
								.replace(" force index (ix_notification_job_schedule)", ""),
						utc(NOW), utc(NOW), utc(NOW));
				assertThat(withoutIndex.get("key")).isNotEqualTo(INDEX);
				assertThat(String.valueOf(withoutIndex.get("Extra"))).contains("Using filesort");
			} finally {
				jdbc.execute("alter table notification_jobs alter index " + INDEX + " visible");
			}

			Map<String, Object> withIndex = explain(jdbc);
			assertThat(withIndex.get("key")).isEqualTo(INDEX);
			assertThat(String.valueOf(withIndex.get("Extra"))).doesNotContain("Using filesort");
			NotificationJobRepositoryImpl repository = new NotificationJobRepositoryImpl(jdbc);
			TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			NotificationJob first = transaction.execute(status -> repository.claimNextJob(NOW).orElseThrow());
			NotificationJob second = transaction.execute(status -> repository.claimNextJob(NOW).orElseThrow());

			// 같은 예약 시각에서는 ID 순서로 선점한다. 더 이른 중지·활성임대·재시도대기는 제외된다.
			assertThat(first.getId()).isEqualTo(id(2000));
			assertThat(second.getId()).isEqualTo(id(2001));
			assertThat(first.getAttemptCount()).isEqualTo(1);
			assertThat(second.getAttemptCount()).isEqualTo(1);
		}
	}

	/** 스테이징처럼 전체 작업은 적고 대기 작업 하나의 수신자 JSON만 큰 분포를 검증한다. */
	@Test
	@DisplayName("완료33·중지2·대기1 작업에서도 filesort 없이 1만명 작업을 선점한다")
	void claimsLargeJobAmongFewMostlyCompletedJobs() throws SQLException {
		try (Connection connection = connect()) {
			SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			jdbc.execute("set session sort_buffer_size = 262144");
			TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			transaction.executeWithoutResult(status -> {
				// given: 테스트 간 순서와 관계없이 36개 분포를 만들고 종료 시 원래 fixture로 되돌린다.
				status.setRollbackOnly();
				jdbc.update("""
						delete from notification_jobs
						where id > ? and id not in (?, ?, ?)
						""", UuidBinary.toBytes(id(32)), UuidBinary.toBytes(id(2000)),
						UuidBinary.toBytes(id(2328)), UuidBinary.toBytes(id(2329)));
				jdbc.update("""
						update notification_jobs set status = 'PENDING', attempt_count = 0, lease_until = null
						where id = ?
						""", UuidBinary.toBytes(id(2000)));
				assertThat(jdbc.queryForObject("select count(*) from notification_jobs", Integer.class)).isEqualTo(36);

				// when / then: 운영 SQL의 실제 선택 계획과 잠금을 포함한 선점을 모두 확인한다.
				Map<String, Object> plan = explain(jdbc);
				assertThat(plan.get("key")).isEqualTo(INDEX);
				assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("Using filesort");
				NotificationJobRepositoryImpl repository = new NotificationJobRepositoryImpl(jdbc);
				NotificationJob claimed = repository.claimNextJob(NOW).orElseThrow();
				assertThat(claimed.getId()).isEqualTo(id(2000));
				assertThat(claimed.getAttemptCount()).isEqualTo(1);
				assertThat(repository.claimNextJob(NOW)).isEmpty();
			});
		}
	}

	/** 저장소가 실제로 실행하는 SQL을 그대로 설명해 테스트 쿼리와의 조건·정렬 차이를 막는다. */
	private static Map<String, Object> explain(JdbcTemplate jdbc) {
		return jdbc.queryForMap("explain " + NotificationJobRepositoryImpl.CLAIM_NEXT_JOB_SQL,
				utc(NOW), utc(NOW), utc(NOW));
	}

	/** 실제 MySQL의 독립 연결이며 수명은 각 try-with-resources 범위에서 관리한다. */
	private static Connection connect() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	/** 정렬·상태 그룹을 재현할 고정 fixture ID다. */
	private static UUID id(int index) {
		return new UUID(42, index + 1);
	}

	/** 운영 저장소와 같은 UTC DATETIME 바인딩을 사용한다. */
	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
