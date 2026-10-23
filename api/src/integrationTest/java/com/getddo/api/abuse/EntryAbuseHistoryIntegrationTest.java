package com.getddo.api.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.abuse.domain.AbuseHistoryRetryPolicy;
import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;
import com.getddo.core.abuse.service.AbuseCaseSaveService;
import com.getddo.core.abuse.service.AbuseHistoryService;
import com.getddo.db.common.util.UuidBinary;

/** 응모 HTTP → Redis 자동 차단 → 건별 DB commit → AR01/AR02의 실제 연결. 로컬 실행하지 않는다. */
@ApiIntegrationTest
@TestPropertySource(properties = {"getddo.rate-limit.enabled=true", "getddo.rate-limit.store=redis",
		"getddo.rate-limit.policies.entry.limit=1", "getddo.rate-limit.policies.entry.scoped-limit=1",
		"getddo.rate-limit.entry-block.user-threshold=1", "getddo.rate-limit.entry-block.event-threshold=1",
		"getddo.abuse.history-worker.enabled=false"})
class EntryAbuseHistoryIntegrationTest {
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);
	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redis(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AbuseHistoryJournalRepository journals;
	@Autowired
	private AbuseCaseSaveService saves;
	private final UUID user = UUID.randomUUID();
	private final UUID admin = UUID.randomUUID();

	@AfterEach
	void cleanOwnRows() {
		jdbc.update("delete from abuse_cases where user_id = ?", UuidBinary.toBytes(user));
		jdbc.update("delete from users where id in (?, ?)", UuidBinary.toBytes(user), UuidBinary.toBytes(admin));
	}

	@Test
	@DisplayName("응모 차단 이력을 저장한 뒤 관리자 조회를 제공하고 차단을 유지한다")
	void blockedRequestEventuallyAppearsInBothAdminApisWithoutUnblockingUser() throws Exception {
		// given
		insertUser(user, "USER");
		insertUser(admin, "ADMIN");
		UUID missingEvent = UUID.randomUUID();
		// 실재 이벤트가 없으므로 event FK는 null이다. 첫 요청은 업무 404, 두 번째는 자동 차단 429다.
		// when / then
		mvc.perform(post("/api/v1/events/{event}/entries", missingEvent).header("X-User-ID", user).header("X-User-Role", "USER").header("X-User-Membership", "excellent")
				.header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/events/{event}/entries", missingEvent).header("X-User-ID", user).header("X-User-Role", "USER").header("X-User-Membership", "excellent")
				.header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isTooManyRequests());
		AbuseHistoryService service = new AbuseHistoryService(journals, saves,
				new AbuseHistoryRetryPolicy(Duration.ofMinutes(10), List.of(Duration.ofSeconds(10), Duration.ofMinutes(2))),
				Duration.ofSeconds(30), 100);
		assertThat(service.processNext()).isTrue();
		UUID id = jdbc.queryForObject("select id from abuse_cases where user_id = ?",
				(row, index) -> UuidBinary.fromBytes(row.getBytes(1)), UuidBinary.toBytes(user));
		mvc.perform(get("/api/v1/admin/abuse-cases").header("X-User-ID", admin).header("X-User-Role", "ADMIN")
				.param("userId", user.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1));
		mvc.perform(get("/api/v1/admin/abuse-cases/{id}", id).header("X-User-ID", admin).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()))
				.andExpect(jsonPath("$.data.evidence.triggerScope").value("USER"));
		mvc.perform(post("/api/v1/events/{event}/entries", missingEvent).header("X-User-ID", user).header("X-User-Role", "USER").header("X-User-Membership", "excellent")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isTooManyRequests());
		assertThat(service.processNext()).isFalse();
	}

	private void insertUser(UUID id, String role) {
		jdbc.update("""
				insert into users (id, name, role, status, membership, created_at, updated_at)
				values (?, 'GD291 테스트', ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", UuidBinary.toBytes(id), role, "USER".equals(role) ? "EXCELLENT" : null);
	}
}
