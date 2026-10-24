package com.getddo.api.common.exception;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.support.ApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 배포 프로파일과 테스트 전용 MySQL로 통신 timeout·풀 고갈·새 연결 확보를 검증한다. 운영 DB에는 접근하지 않는다. */
@ApiIntegrationTest
@ActiveProfiles({"test", "monitoring"})
class DatabaseConnectionRecoveryIntegrationTest {

	@Autowired
	private HikariDataSource pool;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("테스트 DB의 6초 응답 대기는 socketTimeout으로 8초 이내 503이며 다음 정상 조회는 새 연결로 성공한다")
	void socketTimeoutReturns503AndPoolRecovers() throws Exception {
		// given
		MockMvc mvc = mvc();
		long started = System.nanoTime();
		// when / then
		mvc.perform(get("/connection-test/slow"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("COMMON-007"))
				.andExpect(header().doesNotExist("Retry-After"));
		assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8000);
		mvc.perform(get("/connection-test/normal")).andExpect(status().isOk());
	}

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
			// when / then
			mvc.perform(get("/connection-test/normal"))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("COMMON-007"));
			assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8000);
		} finally {
			for (Connection connection : borrowed) {
				connection.close();
			}
		}
		mvc.perform(get("/connection-test/normal")).andExpect(status().isOk());
	}

	private MockMvc mvc() {
		return MockMvcBuilders.standaloneSetup(new QueryController(jdbc))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@RestController
	static class QueryController {
		private final JdbcTemplate jdbc;

		QueryController(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		@GetMapping("/connection-test/slow")
		int slow() {
			return jdbc.queryForObject("select sleep(6)", Integer.class);
		}

		@GetMapping("/connection-test/normal")
		int normal() {
			return jdbc.queryForObject("select 1", Integer.class);
		}
	}
}
