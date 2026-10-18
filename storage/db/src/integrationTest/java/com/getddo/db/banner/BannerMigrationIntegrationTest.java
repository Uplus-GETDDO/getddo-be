package com.getddo.db.banner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import com.getddo.db.support.MySqlTestContainers;
import static org.assertj.core.api.Assertions.assertThat;

/** V027 이전 URL 행을 넣은 뒤 V028로 올리는 실제 이전 회귀 테스트. */
@Testcontainers
class BannerMigrationIntegrationTest {
	@Container
	static final MySQLContainer MYSQL = MySqlTestContainers.create();

	@Test
	@DisplayName("배너 이벤트 FK 추가 후에도 이전 URL·키·순서·식별자·시각을 보존하고 제약을 유지한다")
	void preservesLegacyRowsOnUpgrade() throws Exception {
		// given
		Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.locations("classpath:db/migration").target("27").load().migrate();
		try (Connection connection = connect(); java.sql.Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					insert into banners(id,image_key,display_order,created_at,updated_at,event_url)
					values (unhex('00000000000000000000000000000209'),'legacy/banner.png',4,
					'2026-10-01 01:02:03.123456','2026-10-02 02:03:04.234567','/old/event?keep=true')
					""");
		}
		// when
		Flyway latest = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.locations("classpath:db/migration").load();
		latest.migrate(); latest.validate();
		// then
		try (Connection connection = connect(); java.sql.Statement statement = connection.createStatement();
				ResultSet row = statement.executeQuery("""
					select hex(id) id, event_id, image_key, display_order, event_url,
					date_format(created_at,'%Y-%m-%d %H:%i:%s.%f') created,
					date_format(updated_at,'%Y-%m-%d %H:%i:%s.%f') updated from banners
					""")) {
			assertThat(row.next()).isTrue();
			assertThat(row.getString("id")).isEqualTo("00000000000000000000000000000209");
			assertThat(row.getBytes("event_id")).isNull();
			assertThat(row.getString("image_key")).isEqualTo("legacy/banner.png");
			assertThat(row.getInt("display_order")).isEqualTo(4);
			assertThat(row.getString("event_url")).isEqualTo("/old/event?keep=true");
			assertThat(row.getString("created")).isEqualTo("2026-10-01 01:02:03.123456");
			assertThat(row.getString("updated")).isEqualTo("2026-10-02 02:03:04.234567");
			assertThat(row.next()).isFalse();
		}
		try (Connection connection = connect(); java.sql.Statement statement = connection.createStatement();
				ResultSet row = statement.executeQuery("select id from banner_catalog_lock")) {
			assertThat(row.next()).isTrue(); assertThat(row.getInt(1)).isEqualTo(1); assertThat(row.next()).isFalse();
		}
		assertThat(latest.migrate().migrationsExecuted).isZero();
	}

	private static Connection connect() throws java.sql.SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}
}
