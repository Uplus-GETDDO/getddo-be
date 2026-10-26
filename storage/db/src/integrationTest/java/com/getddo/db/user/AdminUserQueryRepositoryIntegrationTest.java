package com.getddo.db.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserQuery;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.repository.AdminUserQueryRepository;
import com.getddo.db.support.MySqlTestConfiguration;
import com.getddo.db.user.repository.AdminUserQueryRepositoryImpl;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 정확한 페이지 건수와 COUNT 생략, Flyway 인덱스를 검증한다. */
@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = UserPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@Import({MySqlTestConfiguration.class, AdminUserQueryRepositoryImpl.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserQueryRepositoryIntegrationTest {

	@Autowired private AdminUserQueryRepository repository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void seed() {
		for (int n = 1; n <= 7; n++) {
			jdbc.update("""
					insert into users (id, name, role, status, created_at, updated_at)
					values (unhex(replace(?, '-', '')), ?, 'USER', 'ACTIVE',
					        '2040-01-01 00:00:00.123456', '2040-01-01 00:00:00.123456')
					""", id(n).toString(), "GD367 사용자 " + n);
		}
	}

	@ParameterizedTest
	@CsvSource({
			"1,20,7,1", "1,7,7,2", "2,3,3,2", "3,3,1,1", "4,3,0,2", "2147483647,100,0,1"
	})
	@DisplayName("첫·중간·마지막·범위 밖 페이지의 정확한 건수와 필요한 SQL 횟수를 보존한다")
	void preservesTotalsAndSkipsOnlyProvableCounts(int page, int size, int itemCount, int queryCount) {
		// given
		Statistics statistics = statistics();
		statistics.clear();

		// when
		PageResult<User> result = repository.findAll(query(page, size, "GD367"));

		// then
		assertThat(result.getTotalElements()).isEqualTo(7);
		assertThat(result.getItems()).hasSize(itemCount);
		assertThat(result.getPage()).isEqualTo(page);
		assertThat(result.getSize()).isEqualTo(size);
		assertThat(statistics.getQueryExecutionCount()).isEqualTo(queryCount);
		if (itemCount > 0) {
			int first = 7 - (page - 1) * size;
			assertThat(result.getItems()).extracting(User::getId)
					.containsExactlyElementsOf(java.util.stream.IntStream.range(0, itemCount)
							.mapToObj(n -> id(first - n)).toList());
		}
	}

	@ParameterizedTest
	@CsvSource({"1,1", "2,2"})
	@DisplayName("검색 결과가 없으면 첫 페이지에서만 COUNT 없이 0을 확정한다")
	void preservesEmptySearch(int page, int queryCount) {
		// given
		Statistics statistics = statistics();
		statistics.clear();

		// when
		PageResult<User> result = repository.findAll(query(page, 20, "존재하지않는이름"));

		// then
		assertThat(result.getItems()).isEmpty();
		assertThat(result.getTotalElements()).isZero();
		assertThat(statistics.getQueryExecutionCount()).isEqualTo(queryCount);
	}

	@Test
	@DisplayName("사용자 상태 변경 후 활성 사용자 집계는 캐시 없이 현재 DB 값을 반영한다")
	void countsCurrentActiveUsers() {
		// given
		long before = repository.countActiveRegularUsers();
		jdbc.update("update users set status = 'INACTIVE' where id = unhex(replace(?, '-', ''))", id(1).toString());

		// when
		long after = repository.countActiveRegularUsers();

		// then
		assertThat(after).isEqualTo(before - 1);
		assertThat(after).isEqualTo(jdbc.queryForObject(
				"select count(*) from users where role = 'USER' and status = 'ACTIVE'", Long.class));
	}

	@Test
	@DisplayName("Flyway는 사용자 최신순 정렬과 역할·상태 집계 인덱스를 생성한다")
	void createsQueryIndexes() {
		// given / when
		List<Map<String, Object>> indexes = jdbc.queryForList("""
				select index_name as name,
				       group_concat(concat(column_name, ':', collation) order by seq_in_index) as columns_in_order
				from information_schema.statistics
				where table_schema = database() and table_name = 'users'
				  and index_name in ('ix_users_created_id', 'ix_users_role_status')
				group by index_name order by index_name
				""");

		// then
		assertThat(indexes).containsExactly(
				Map.of("name", "ix_users_created_id", "columns_in_order", "created_at:D,id:D"),
				Map.of("name", "ix_users_role_status", "columns_in_order", "role:A,status:A"));
	}

	private Statistics statistics() {
		return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}

	private static AdminUserQuery query(int page, int size, String keyword) {
		return new AdminUserQuery(page, size, null, keyword, null, null, null);
	}

	private static UUID id(int value) {
		return UUID.fromString("00000000-0000-0000-0000-00000003670" + value);
	}
}
