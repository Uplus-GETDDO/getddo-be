package com.getddo.api.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.db.common.util.UuidBinary;

/** 응모 HTTP → Redis 자동 차단 → 건별 DB commit → AR01/AR02의 실제 연결. 로컬 실행하지 않는다. */
@ApiIntegrationTest
@TestPropertySource(properties = {"getddo.rate-limit.enabled=true", "getddo.rate-limit.store=redis",
		"getddo.rate-limit.policies.entry.limit=1", "getddo.rate-limit.policies.entry.scoped-limit=1",
		"getddo.rate-limit.entry-block.user-threshold=1", "getddo.rate-limit.entry-block.event-threshold=1",
		"getddo.abuse.history-worker.enabled=true",
		"getddo.abuse.history-worker.poll-delay=100", "getddo.abuse.history-worker.recovery-delay=3600000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
	private StringRedisTemplate redis;
	private final UUID user = UUID.randomUUID();
	private final UUID admin = UUID.randomUUID();
	private final JsonMapper json = JsonMapper.builder().build();

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
		// 완료 표시가 먼저 생겨도 만료 전 저널 payload는 유지되므로 최초 값을 직접 대조할 수 있다.
		Set<String> ids = redis.opsForSet().members("getddo:entry-block:user:" + user);
		assertThat(ids).hasSize(1);
		UUID id = UUID.fromString(ids.iterator().next());
		String payload = (String) redis.opsForHash().get("getddo:entry-block:journal:" + id, "payload");
		assertThat(payload).isNotBlank();
		JsonNode original = json.readTree(payload);
		assertThat(original.path("id").asText()).isEqualTo(id.toString());
		assertThat(original.path("userId").asText()).isEqualTo(user.toString());
		assertThat(original.path("eventId").isNull()).isTrue();
		assertThat(original.path("evidence").path("triggerScope").asText()).isEqualTo("USER");
		assertThat(original.path("evidence").path("requestLimit").asInt()).isEqualTo(1);
		assertThat(original.path("evidence").path("rejectedCount").asInt()).isEqualTo(1);
		assertThat(original.path("evidence").path("rejectionThreshold").asInt()).isEqualTo(1);
		Instant blockedAt = Instant.ofEpochMilli(original.path("blockedAt").asLong());
		Instant blockedUntil = Instant.ofEpochMilli(original.path("blockedUntil").asLong());
		JsonNode evidence = expectedEvidence(original.path("evidence"));
		// 실제 Spring 작업자의 정기 poll이 저널을 선점해 DB 커밋할 때까지 기다린다.
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				assertThat(jdbc.queryForObject("select count(*) from abuse_cases where user_id = ?",
						Long.class, UuidBinary.toBytes(user))).isEqualTo(1));
		jdbc.queryForObject("""
				select id, user_id, event_id, blocked_at, blocked_until, occurred_at, detected_at, evidence
				from abuse_cases where user_id = ?
				""", (row, index) -> {
			assertThat(row.getBytes("id")).containsExactly(UuidBinary.toBytes(id));
			assertThat(row.getBytes("user_id")).containsExactly(UuidBinary.toBytes(user));
			assertThat(row.getBytes("event_id")).isNull();
			assertThat(row.getObject("blocked_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(blockedAt);
			assertThat(row.getObject("blocked_until", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(blockedUntil);
			assertThat(row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
					.isEqualTo(Instant.parse(original.path("occurredAt").asText()));
			assertThat(row.getObject("detected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
					.isEqualTo(Instant.ofEpochMilli(original.path("detectedAt").asLong()));
			assertThat(json.readTree(row.getString("evidence"))).isEqualTo(evidence);
			return true;
		}, UuidBinary.toBytes(user));
		mvc.perform(get("/api/v1/admin/abuse-cases").header("X-User-ID", admin).header("X-User-Role", "ADMIN")
				.param("userId", user.toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(id.toString()));
		MvcResult detailResult = mvc.perform(get("/api/v1/admin/abuse-cases/{id}", id).header("X-User-ID", admin).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()))
				.andExpect(jsonPath("$.data.evidence.triggerScope").value("USER")).andReturn();
		JsonNode detail = json.readTree(detailResult.getResponse().getContentAsByteArray()).path("data");
		assertThat(Instant.parse(detail.path("blockedAt").asText())).isEqualTo(blockedAt);
		assertThat(Instant.parse(detail.path("blockedUntil").asText())).isEqualTo(blockedUntil);
		assertThat(detail.path("userId").asText()).isEqualTo(original.path("userId").asText());
		assertThat(detail.path("eventId")).isEqualTo(original.path("eventId"));
		assertThat(detail.path("evidence")).isEqualTo(evidence);
		mvc.perform(post("/api/v1/events/{event}/entries", missingEvent).header("X-User-ID", user).header("X-User-Role", "USER").header("X-User-Membership", "excellent")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isTooManyRequests());
		// 저장 성공이 차단 해제를 뜻하지 않으며 Redis 완료 표식까지 실제 작업자 경로로 확인한다.
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				assertThat(redis.opsForHash().get("getddo:entry-block:journal:" + id, "dbPersisted")).isEqualTo("true"));
	}

	/** Redis 내부 epoch 밀리초만 공용 UTC 문자열로 바꾸고 나머지 일곱 근거 값은 원본 그대로 기대한다. */
	private JsonNode expectedEvidence(JsonNode original) {
		return json.valueToTree(Map.of(
				"ruleVersion", original.path("ruleVersion").asText(),
				"triggerScope", original.path("triggerScope").asText(),
				"windowStartedAt", Instant.ofEpochMilli(original.path("windowStartedAt").asLong()).toString(),
				"windowEndsAt", Instant.ofEpochMilli(original.path("windowEndsAt").asLong()).toString(),
				"requestLimit", original.path("requestLimit").asInt(),
				"rejectedCount", original.path("rejectedCount").asInt(),
				"rejectionThreshold", original.path("rejectionThreshold").asInt()));
	}

	private void insertUser(UUID id, String role) {
		jdbc.update("""
				insert into users (id, name, role, status, membership, created_at, updated_at)
				values (?, 'GD291 테스트', ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", UuidBinary.toBytes(id), role, "USER".equals(role) ? "EXCELLENT" : null);
	}
}
