package com.getddo.db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.getddo.db.support.MySqlTestContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MySqlMigrationTest {

	@Container
	static final MySQLContainer MYSQL = MySqlTestContainers.create();

	/**
	 * 이미 병합된 마이그레이션 버전. 발견 결과와 무관한 독립된 기준이라 파일을 지우거나 번호를 바꾸면 이 목록과 어긋나 실패한다.
	 * 새 마이그레이션을 추가할 때는 이 목록을 고치지 않는다(PR끼리 충돌하지 않게 하려는 것이다). 기존 버전을 정리할 때만 고친다.
	 * 버전 20은 건너뛰었다.
	 */
	private static final List<Integer> MERGED_VERSIONS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
			14, 16, 17, 18, 19, 21, 22, 23);

	private static Flyway flyway;
	private static int appliedMigrations;

	/** 스키마 검증 사례들이 공유할 빈 테스트 DB에 전체 마이그레이션을 한 번 적용한다. */
	@BeforeAll
	static void migrate() {
		flyway = Flyway.configure()
				.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.locations("classpath:db/migration")
				.load();
		appliedMigrations = flyway.migrate().migrationsExecuted;
	}

	/** 빈 DB에 이벤트·알림 인덱스를 포함한 스키마를 적용하고 재실행의 멱등성을 검증한다. */
	@Test
	@DisplayName("빈 MySQL에 전체 마이그레이션이 적용되어 46개 테이블·인덱스·추첨 상태·복구 차수를 갖추고 재실행해도 다시 적용되지 않는다")
	void migratesSchemaAndDoesNotReapplyIt() throws SQLException {
		// given
		// when
		int repeatedMigrations = flyway.migrate().migrationsExecuted;

		// then
		// 마이그레이션이 늘 때마다 개수를 고치면 PR끼리 충돌하므로 전체 개수는 적지 않는다. Flyway가 찾은 전부가 적용됐는지 확인하고,
		// 이미 병합된 버전이 삭제·번호 변경되지 않았는지는 아래의 독립된 기준 목록으로 확인한다.
		assertThat(appliedMigrations).isEqualTo(flyway.info().all().length);
		assertThat(appliedVersions()).containsAll(MERGED_VERSIONS).contains(24);
		assertThat(repeatedMigrations).isZero();
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(indexColumns("award_cancellations", "uq_award_cancellations_request", 0)).containsExactly("request_key");
		flyway.validate();
		try (Connection connection = connect();
				ResultSet rows = connection.createStatement().executeQuery("""
						select column_type, is_nullable, column_default, column_comment from information_schema.columns
						where table_schema=database() and table_name='events' and column_name='detail_image_key'
						""")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString("column_type")).isEqualTo("varchar(500)");
			assertThat(rows.getString("is_nullable")).isEqualTo("YES");
			assertThat(rows.getObject("column_default")).isNull();
			assertThat(rows.getString("column_comment")).isEqualTo("이벤트 상세 설명 이미지의 S3 저장 키");
		}
		assertNullableDeletedAtColumn("events");
		assertThat(indexColumns("events", "idx_events_list", 1))
				.containsExactly("deleted_at", "created_at", "id");
		assertThat(indexColumns("events", "idx_events_lifecycle_start", 1))
				.containsExactly("status", "deleted_at", "starts_at", "id");
		assertThat(indexColumns("events", "idx_events_lifecycle_end", 1))
				.containsExactly("status", "deleted_at", "ends_at", "id");
		assertThat(indexColumns("banners", "idx_banners_event_id", 1)).containsExactly("event_id");
		assertIndexExists("notification_jobs", "ix_notification_job_pending");
		assertIndexExists("notification_jobs", "ix_notification_job_lease");
		assertIndexExists("notifications", "ix_notification_delivery_retry");
		assertThat(indexColumns("draw_runs", "draw_retry_due_idx", 1)).containsExactly("status", "retry_at");
		assertThat(indexColumns("draw_runs", "draw_lease_due_idx", 1)).containsExactly("status", "lease_until");
		assertThat(indexColumns("draw_failures", "draw_failure_history_idx", 1)).containsExactly("draw_run_id", "failed_at", "id");
		assertThat(indexColumns("tickets", "ix_tickets_user_grade_expiry", 1))
				.containsExactly("user_id", "grade", "expires_at");
		assertThat(indexColumns("tickets", "ix_tickets_status_expiry", 1))
				.containsExactly("status", "expires_at");
		try (Connection connection = connect();
			ResultSet rows = connection.createStatement().executeQuery("""
				select column_type from information_schema.columns
				where table_schema = database() and table_name = 'draw_runs' and column_name = 'status'
				""")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).contains("'NO_ENTRIES'", "'NO_CANDIDATES'");
		}
		try (Connection connection = connect();
			ResultSet rows = connection.createStatement().executeQuery("""
					select count(*) from information_schema.tables
					where table_schema = database() and table_name <> 'flyway_schema_history'
					""")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getInt(1)).isEqualTo(46);
		}
		try (Connection connection = connect();
			ResultSet rows = connection.createStatement().executeQuery("""
					select count(*) from information_schema.columns
					where table_schema = database() and table_name = 'notification_jobs'
					and column_name in ('publication_id', 'target_user_id')
					""")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getInt(1)).isZero();
		}
	}

	/** 개별 응모권·이력과 게임 통계의 PK 및 중복 방지 UNIQUE를 확인한다. */
	@Test
	@DisplayName("개별 응모권·이력과 게임 통계의 PK 및 중복 방지 UNIQUE를 확인한다")
	void ticketsHistoriesAndGameStatsUseSingleIdPrimaryKey() throws SQLException {
		// given
		// when
		// then
		assertThat(idColumn("tickets")).containsExactly("binary(16)", "NO");
		assertThat(idColumn("ticket_histories")).containsExactly("binary(16)", "NO");
		assertThat(idColumn("user_game_stats")).containsExactly("binary(16)", "NO");
		assertThat(indexColumns("tickets", "PRIMARY")).containsExactly("id");
		assertThat(indexColumns("ticket_histories", "PRIMARY")).containsExactly("id");
		assertThat(indexColumns("ticket_histories", "uq_ticket_histories_1"))
				.containsExactly("ticket_id", "ticket_version");
		assertThat(indexColumns("ticket_histories", "uq_ticket_histories_refund_source"))
				.containsExactly("original_use_history_id");
		assertThat(indexColumns("draw_run_candidates", "PRIMARY")).containsExactly("draw_run_id", "candidate_id");
		assertThat(indexColumns("draw_publication_executions", "PRIMARY")).containsExactly("publication_id", "draw_run_id");
		assertThat(indexColumns("draw_publication_results", "PRIMARY"))
				.containsExactly("publication_id", "draw_result_id");
		assertThat(indexColumns("user_game_stats", "PRIMARY")).containsExactly("id");
		assertThat(indexColumns("user_game_stats", "uq_user_game_stats_1")).containsExactly("user_id", "game_id");
	}

	/** 출석 응모권은 브론즈 및 지급 근거 소유자를 검증하고 이력 버전 중복을 거부한다. */
	@Test
	@DisplayName("출석 응모권은 브론즈 및 지급 근거 소유자를 검증하고 이력 버전 중복을 거부한다")
	void enforcesTicketSourceGradeAndHistoryVersion() throws SQLException {
		try (Connection connection = connect()) {
			execute(connection, """
					insert into users (id, name, role, status, created_at, updated_at) values
					(unhex('0199A0000000700080000000000000B1'), '티켓사용자', 'USER', 'ACTIVE', now(6), now(6)),
					(unhex('0199A0000000700080000000000000B2'), '다른사용자', 'USER', 'ACTIVE', now(6), now(6))
					""");
			execute(connection, """
					insert into reward_policies (id, created_by, reward_type, reward_ticket_count, effective_from, created_at)
					values (unhex('0199A0000000700080000000000000B3'), unhex('0199A0000000700080000000000000B1'),
					        'ATTENDANCE', 1, now(6), now(6))
					""");
			execute(connection, """
					insert into attendances (id, user_id, attendance_date, created_at)
					values (unhex('0199A0000000700080000000000000B4'), unhex('0199A0000000700080000000000000B1'),
					        '2026-10-08', now(6))
					""");
			execute(connection, """
					insert into attendance_reward_claims
					(id, attendance_id, reward_policy_id, user_id, reward_type, reward_date, source_key, ticket_count, created_at)
					values (unhex('0199A0000000700080000000000000B5'), unhex('0199A0000000700080000000000000B4'),
					        unhex('0199A0000000700080000000000000B3'), unhex('0199A0000000700080000000000000B1'),
					        'DAILY', '2026-10-08', '2026-10-08', 1, now(6))
					""");
			String insertTicket = """
					insert into tickets (id, user_id, attendance_reward_claim_id, grade, status, expires_at, created_at, updated_at)
					values (unhex(?), unhex(?), unhex('0199A0000000700080000000000000B5'), ?, 'AVAILABLE',
					        '2026-11-01 00:00:00', now(6), now(6))
					""";
			assertThatThrownBy(() -> execute(connection, insertTicket, "0199A0000000700080000000000000B6",
					"0199A0000000700080000000000000B1", "GOLD"))
					.isInstanceOf(SQLException.class).hasMessageContaining("chk_ticket_attendance_bronze");
			assertThatThrownBy(() -> execute(connection, insertTicket, "0199A0000000700080000000000000B6",
					"0199A0000000700080000000000000B2", "BRONZE"))
					.isInstanceOf(SQLIntegrityConstraintViolationException.class);
			execute(connection, insertTicket, "0199A0000000700080000000000000B6",
					"0199A0000000700080000000000000B1", "BRONZE");
			String insertHistory = """
					insert into ticket_histories (id, ticket_id, operation_type, ticket_version, status, expires_at, reason, created_at)
					values (unhex(?), unhex('0199A0000000700080000000000000B6'), 'GRANT', 1, 'AVAILABLE',
					        '2026-11-01 00:00:00', '출석 지급', now(6))
					""";
			execute(connection, insertHistory, "0199A0000000700080000000000000B7");
			assertThatThrownBy(() -> execute(connection, insertHistory, "0199A0000000700080000000000000B8"))
					.isInstanceOf(SQLIntegrityConstraintViolationException.class)
					.hasMessageContaining("uq_ticket_histories_1");
		}
	}

	/** 같은 사용자·게임의 게임 통계 두 번째 행은 UNIQUE로 거부한다. */
	@Test
	@DisplayName("같은 사용자·게임의 게임 통계 두 번째 행은 UNIQUE로 거부한다")
	void rejectsDuplicateUserGameStats() throws SQLException {
		try (Connection connection = connect()) {
			// given
			execute(connection, """
					insert into users (id, name, role, status, membership, updated_at, created_at)
					values (unhex('0199A0000000700080000000000000A1'), '통계사용자', 'USER', 'ACTIVE', 'VIP', now(6), now(6))
					""");
			execute(connection, """
					insert into games (id, code, name, rules, rule_version, created_at, updated_at)
					values (unhex('0199A0000000700080000000000000A2'), 'stats-unique', '통계 게임', '{}', 'v1', now(6), now(6))
					""");
			String insertStats = """
					insert into user_game_stats (id, user_id, game_id, updated_at, created_at)
					values (unhex(?), unhex('0199A0000000700080000000000000A1'), unhex('0199A0000000700080000000000000A2'),
					        now(6), now(6))
					""";
			execute(connection, insertStats, "0199A0000000700080000000000000A3");
			// when
			// then
			assertThatThrownBy(() -> execute(connection, insertStats, "0199A0000000700080000000000000A4"))
					.isInstanceOf(SQLIntegrityConstraintViolationException.class)
					.hasMessageContaining("uq_user_game_stats_1");
		}
	}

	/** 다음 동작을 검증한다: 공개 실행 연결은 다른 이벤트의 실행과 확인을 복합 FK로 거절한다. */
	@Test @DisplayName("공개 실행 연결은 다른 이벤트의 실행과 확인을 복합 FK로 거절한다")
	void rejectsPublicationEvidenceFromAnotherEvent() throws SQLException {
		// given
		try (Connection connection = connect()) {
			connection.setAutoCommit(false);
			try {
				String eventA = "0199A0000000700080000000000000E1", eventB = "0199A0000000700080000000000000E2";
				String runA = "0199A0000000700080000000000000E3", runB = "0199A0000000700080000000000000E4";
				String publication = "0199A0000000700080000000000000E5", admin = "0199A0000000700080000000000000E6";
				String check = "0199A0000000700080000000000000E7";
				execute(connection, "insert into users (id,name,role,status,created_at,updated_at) values (unhex(?),'관리자','ADMIN','ACTIVE',now(6),now(6))", admin);
				for (String event : List.of(eventA, eventB)) execute(connection, """
					insert into events (id,title,description,event_type,starts_at,ends_at,status,membership_rule,created_at,updated_at)
					values (unhex(?),'이벤트','설명','NO_TICKET','2026-10-01','2026-10-02','CLOSED','vip',now(6),now(6))
					""", event);
				execute(connection, "insert into draw_runs (id,event_id,run_number,draw_type,status,created_at) values (unhex(?),unhex(?),0,'INITIAL','PREPARING',now(6))", runA, eventA);
				execute(connection, "insert into draw_runs (id,event_id,run_number,draw_type,status,created_at) values (unhex(?),unhex(?),1,'REPLACEMENT','PREPARING',now(6))", runB, eventB);
				execute(connection, "insert into draw_publications (id,event_id,source_draw_id,revision,publication_type,published_at) values (unhex(?),unhex(?),unhex(?),1,'INITIAL',now(6))", publication, eventA, runA);
				execute(connection, "insert into draw_result_confirmations (id,draw_run_id,confirmed_by,confirmed_at) values (unhex(?),unhex(?),unhex(?),now(6))", check, runB, admin);
				// when / then: 존재하는 공개·실행·확인 ID라도 이벤트가 다르면 연결할 수 없다.
				assertThatThrownBy(() -> execute(connection, """
					insert into draw_publication_executions (publication_id,draw_run_id,event_id,confirmation_id)
					values (unhex(?),unhex(?),unhex(?),unhex(?))
					""", publication, runB, eventA, check)).isInstanceOf(SQLIntegrityConstraintViolationException.class)
						.hasMessageContaining("fk_publication_execution_run");
			} finally { connection.rollback(); }
		}
	}

	/** Testcontainers 임시 MySQL의 접속 정보로 독립 JDBC 연결을 연다. */
	private static Connection connect() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	/** 검증용 SQL의 문자열 매개변수를 바인딩해 임시 DB의 제약 조건을 확인할 데이터를 처리한다. */
	private static void execute(Connection connection, String sql, String... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < parameters.length; i++) {
				statement.setString(i + 1, parameters[i]);
			}
			statement.executeUpdate();
		}
	}

	/** {@code id} 컬럼의 타입과 NULL 허용 여부({@code YES}/{@code NO})를 반환한다. */
	private static List<String> idColumn(String tableName) throws SQLException {
		try (Connection connection = connect();
			PreparedStatement statement = connection.prepareStatement("""
					select column_type, is_nullable from information_schema.columns
					where table_schema = database() and table_name = ? and column_name = 'id'
					""")) {
			statement.setString(1, tableName);
			try (ResultSet rows = statement.executeQuery()) {
				assertThat(rows.next()).as("%s.id 컬럼", tableName).isTrue();
				return List.of(rows.getString(1), rows.getString(2));
			}
		}
	}

	/** 적용된 마이그레이션의 버전 번호. */
	private static List<Integer> appliedVersions() {
		return java.util.Arrays.stream(flyway.info().applied())
				.map(info -> Integer.parseInt(info.getVersion().getVersion()))
				.toList();
	}

	/** 유니크 인덱스(PK 포함)의 컬럼을 인덱스 내 순서대로 반환한다. */
	private static List<String> indexColumns(String tableName, String indexName) throws SQLException {
		return indexColumns(tableName, indexName, 0);
	}

	/** {@code nonUnique}가 1이면 UNIQUE가 아닌 인덱스의 컬럼을 순서대로 읽는다. */
	private static List<String> indexColumns(String tableName, String indexName, int nonUnique) throws SQLException {
		try (Connection connection = connect();
			PreparedStatement statement = connection.prepareStatement("""
					select column_name from information_schema.statistics
					where table_schema = database() and table_name = ? and index_name = ? and non_unique = ?
					order by seq_in_index
					""")) {
			statement.setString(1, tableName);
			statement.setString(2, indexName);
			statement.setInt(3, nonUnique);
			List<String> columns = new ArrayList<>();
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					columns.add(rows.getString(1));
				}
			}
			return columns;
		}
	}

	/** MySQL 메타데이터에서 워커 선점·재시도에 필요한 인덱스의 적용을 확인한다. */
	private void assertIndexExists(String tableName, String indexName) throws SQLException {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
			ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
			boolean found = false;
			while (indexes.next()) {
				found |= indexName.equals(indexes.getString("INDEX_NAME"));
			}
			assertThat(found).as(indexName).isTrue();
		}
	}

	/** 논리 삭제 컬럼이 기존 활성 데이터를 표현할 수 있도록 null을 허용하는지 확인한다. */
	private void assertNullableDeletedAtColumn(String tableName) throws SQLException {
		try (Connection connection = connect();
			ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, "deleted_at")) {
			assertThat(columns.next()).isTrue();
			assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
		}
	}
}
