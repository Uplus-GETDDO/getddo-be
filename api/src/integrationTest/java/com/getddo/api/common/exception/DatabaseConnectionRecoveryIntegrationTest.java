package com.getddo.api.common.exception;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 배포 프로파일과 테스트 전용 MySQL로 통신 timeout·풀 고갈·새 연결 확보를 검증한다. 운영 DB에는 접근하지 않는다. */
@ApiIntegrationTest
@ActiveProfiles({"test", "monitoring"})
@TestPropertySource(properties = "spring.datasource.hikari.data-source-properties.socketTimeout=5000")
@Import(DatabaseConnectionRecoveryIntegrationTest.JpaProbeConfiguration.class)
class DatabaseConnectionRecoveryIntegrationTest {

	@Autowired
	private HikariDataSource pool;
	@Autowired
	private JpaProbe jpa;

	/** 한 연결짜리 테스트 풀에서 통신 오류 전·후의 MySQL 연결 ID를 비교해 유휴 연결 재사용과 물리 연결 교체를 구분한다. */
	@Test
	@DisplayName("테스트 DB의 6초 응답 대기는 socketTimeout으로 8초 이내 503이며 다음 정상 조회는 새 연결로 성공한다")
	void socketTimeoutReturns503AndPoolRecovers() throws Exception {
		// given
		// 연결 한 개짜리 별도 풀로 기존의 다른 유휴 연결을 재사용한 성공과 물리 연결 교체를 구분한다.
		try (HikariDataSource isolated = singleConnectionPool(5000)) {
			JdbcTemplate probe = new JdbcTemplate(isolated);
			Long before = probe.queryForObject("select connection_id()", Long.class);
			MockMvc mvc = MockMvcBuilders.standaloneSetup(new QueryController(probe))
					.setControllerAdvice(new GlobalExceptionHandler()).build();
			long started = System.nanoTime();
			// when
			ResultActions result = mvc.perform(get("/connection-test/slow"));
			// then
			result.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("COMMON-007"))
					.andExpect(header().doesNotExist("Retry-After"));
			assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8000);
			mvc.perform(get("/connection-test/normal")).andExpect(status().isOk()).andExpect(content().string("1"));
			assertThat(probe.queryForObject("select connection_id()", Long.class)).isNotEqualTo(before);
		}
	}

	/** 정상 DB의 연결을 모두 점유해 실제 JPA 트랜잭션 시작 실패의 503과 연결 반환 후 정상 결과를 검증한다. */
	@Test
	@DisplayName("정상 DB에서도 풀을 모두 점유하면 8초 이내 503이며 연결 반환 후 정상 조회로 회복한다")
	void exhaustedPoolIsUnavailableWithoutClaimingDatabaseFailover() throws Exception {
		// given
		MockMvc mvc = mvc();
		List<Connection> borrowed = new ArrayList<>();
		try {
			for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
				borrowed.add(pool.getConnection());
			}
			long started = System.nanoTime();
			// when
			ResultActions result = mvc.perform(get("/connection-test/jpa/normal"));
			// then
			result.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("COMMON-007"));
			assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8000);
		} finally {
			for (Connection connection : borrowed) {
				connection.close();
			}
		}
		mvc.perform(get("/connection-test/jpa/normal")).andExpect(status().isOk()).andExpect(content().string("1"));
	}

	/** 실제 JPA 조회의 통신 단절과 롤백 실패를 거친 뒤에도 503으로 응답하고 다음 트랜잭션이 정상 조회하는지 검증한다. */
	@Test
	@DisplayName("실제 JPA Service·Repository 트랜잭션의 socket 장애는 503이고 이후 새 트랜잭션 조회는 성공한다")
	void jpaTransactionRecoversAfterSocketTimeout() throws Exception {
		// given
		MockMvc mvc = mvc();
		long started = System.nanoTime();
		// when
		ResultActions result = mvc.perform(get("/connection-test/jpa/slow"));
		// then
		result.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("COMMON-007"));
		assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8000);
		mvc.perform(get("/connection-test/jpa/normal")).andExpect(status().isOk()).andExpect(content().string("1"));
	}

	/** 5초 제한을 넘는 정상 SQL이 보수적인 운영 제한에서는 완료되며 같은 물리 연결을 유지하는지 확인한다. */
	@Test
	@DisplayName("운영 socket 60초 제한에서는 6초 정상 SQL을 끊지 않고 같은 물리 연결을 유지한다")
	void finiteProductionBudgetAllowsQueryLongerThanFiveSeconds() {
		// given
		try (HikariDataSource isolated = singleConnectionPool(60000)) {
			JdbcTemplate probe = new JdbcTemplate(isolated);
			Long before = probe.queryForObject("select connection_id()", Long.class);
			// when
			Integer result = probe.queryForObject("select sleep(6)", Integer.class);
			// then
			assertThat(result).isZero();
			assertThat(probe.queryForObject("select connection_id()", Long.class)).isEqualTo(before);
		}
	}

	/** 테스트 소유 연결 수를 1개로 제한하고 장애 재현·정상 장기 SQL의 읽기 예산을 구분한다. */
	private HikariDataSource singleConnectionPool(int socketTimeout) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(pool.getJdbcUrl());
		config.setUsername(pool.getUsername());
		config.setPassword(pool.getPassword());
		config.getDataSourceProperties().putAll(pool.getDataSourceProperties());
		config.addDataSourceProperty("socketTimeout", Integer.toString(socketTimeout));
		config.setConnectionTimeout(pool.getConnectionTimeout());
		config.setValidationTimeout(pool.getValidationTimeout());
		config.setExceptionOverrideClassName(pool.getExceptionOverrideClassName());
		config.setMaximumPoolSize(1);
		config.setMinimumIdle(0);
		return new HikariDataSource(config);
	}

	/** Spring이 생성한 트랜잭션 프록시를 테스트 Controller에 연결해 HTTP 오류 변환까지 검증한다. */
	private MockMvc mvc() {
		return MockMvcBuilders.standaloneSetup(new JpaQueryController(jpa))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	/** 테스트 컨텍스트에만 프록시를 거치는 JPA 트랜잭션 진입점을 등록한다. */
	@TestConfiguration(proxyBeanMethods = false)
	static class JpaProbeConfiguration {
		/** 실제 EntityManager와 사용자 Repository를 사용하는 테스트 전용 트랜잭션 진입점을 등록한다. */
		@Bean
		JpaProbe jpaProbe(EntityManager entityManager, UserRepository users) {
			return new JpaProbe(entityManager, users);
		}
	}

	/** 실제 사용자 JPA Repository 조회와 느린 SQL을 같은 트랜잭션에서 실행한다. */
	public static class JpaProbe {
		private final EntityManager entityManager;
		private final UserRepository users;

		JpaProbe(EntityManager entityManager, UserRepository users) {
			this.entityManager = entityManager;
			this.users = users;
		}

		/** 같은 트랜잭션에서 실제 사용자 Repository를 조회한 뒤 정상 SQL 또는 통신 제한을 넘기는 SQL을 실행한다. */
		@Transactional(readOnly = true)
		public int query(boolean slow) {
			users.findById(UUID.randomUUID());
			return ((Number) entityManager.createNativeQuery(slow ? "select sleep(6)" : "select 1", Integer.class)
					.getSingleResult()).intValue();
		}
	}

	/** 트랜잭션 프록시를 거치는 JPA 장애·복구의 HTTP 진입점이다. */
	@RestController
	static class JpaQueryController {
		private final JpaProbe service;

		JpaQueryController(JpaProbe service) {
			this.service = service;
		}

		/** Spring 트랜잭션 안의 통신 제한 초과를 HTTP 경계까지 전달한다. */
		@GetMapping("/connection-test/jpa/slow")
		int slow() {
			return service.query(true);
		}

		/** 복구 뒤 실제 JPA 정상 조회가 결과 1을 반환하는지 확인하는 경로다. */
		@GetMapping("/connection-test/jpa/normal")
		int normal() {
			return service.query(false);
		}
	}

	/** 독립된 JDBC 테스트 풀의 물리 연결 교체를 확인하는 HTTP 진입점이다. */
	@RestController
	static class QueryController {
		private final JdbcTemplate jdbc;

		QueryController(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		/** JDBC 통신 대기를 유발해 테스트 전용 풀의 연결 폐기·교체를 검증한다. */
		@GetMapping("/connection-test/slow")
		int slow() {
			return jdbc.queryForObject("select sleep(6)", Integer.class);
		}

		/** 연결 교체 이후 JDBC 조회 결과를 확인하는 경로다. */
		@GetMapping("/connection-test/normal")
		int normal() {
			return jdbc.queryForObject("select 1", Integer.class);
		}
	}
}
