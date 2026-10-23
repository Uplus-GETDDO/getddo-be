package com.getddo.api.abuse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.db.common.util.UuidBinary;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GD-142의 V030·실제 사용자 문맥·Repository·Mapper·Service·MVC를 같은 MySQL에서 연결한다. */
@ApiIntegrationTest
class AdminAbuseCaseIntegrationTest {

	private static final String PATH = "/api/v1/admin/abuse-cases";
	private static final Instant AT = Instant.parse("2026-10-21T06:00:00.123456Z");
	private final UUID adminId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID inactiveId = UUID.randomUUID();
	private final UUID eventId = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void seedUsers() {
		insertUser(adminId, "ADMIN", "ACTIVE");
		insertUser(userId, "USER", "ACTIVE");
		insertUser(inactiveId, "ADMIN", "INACTIVE");
	}

	@AfterEach
	void cleanOwnRows() {
		jdbc.update("delete from abuse_cases where id = ?", bytes(caseId));
		jdbc.update("delete from events where id = ?", bytes(eventId));
		jdbc.update("delete from users where id in (?, ?, ?)", bytes(adminId), bytes(userId), bytes(inactiveId));
	}

	@ParameterizedTest
	@CsvSource({"USER,false", "EVENT,true"})
	@DisplayName("AR02는 이벤트 null·현재 표시값·저장된 USER/EVENT 근거를 공개 필드만으로 반환하고 목록은 그대로다")
	void readsStoredEvidenceThroughRealApi(String scope, boolean withEvent) throws Exception {
		// given
		insertCase(scope, withEvent);
		jdbc.update("update users set name = '현재 닉네임' where id = ?", bytes(userId));
		if (withEvent) {
			jdbc.update("update events set title = '현재 제목' where id = ?", bytes(eventId));
		}

		// when / then
		mvc.perform(asAdmin(get(PATH + "/{caseId}", caseId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data", aMapWithSize(11)))
				.andExpect(jsonPath("$.data.id").value(caseId.toString()))
				.andExpect(jsonPath("$.data.userId").value(userId.toString()))
				.andExpect(jsonPath("$.data.userNickname").value("현재 닉네임"))
				.andExpect(jsonPath("$.data.eventId").value(withEvent ? org.hamcrest.Matchers.is(eventId.toString()) : nullValue()))
				.andExpect(jsonPath("$.data.eventTitle").value(withEvent ? org.hamcrest.Matchers.is("현재 제목") : nullValue()))
				.andExpect(jsonPath("$.data.blockedAt").value(AT.toString()))
				.andExpect(jsonPath("$.data.blockedUntil").value(AT.plusSeconds(600).toString()))
				.andExpect(jsonPath("$.data.evidence", aMapWithSize(7)))
				.andExpect(jsonPath("$.data.evidence.ruleVersion").value("stored-v2"))
				.andExpect(jsonPath("$.data.evidence.triggerScope").value(scope))
				.andExpect(jsonPath("$.data.evidence.windowStartedAt").value(AT.minusSeconds(30).toString()))
				.andExpect(jsonPath("$.data.evidence.windowEndsAt").value(AT.plusSeconds(30).toString()))
				.andExpect(jsonPath("$.data.evidence.requestLimit").value(37))
				.andExpect(jsonPath("$.data.evidence.rejectedCount").value(39))
				.andExpect(jsonPath("$.data.evidence.rejectionThreshold").value(37));
		mvc.perform(asAdmin(get(PATH).param("userId", userId.toString())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0]", aMapWithSize(10)));
	}

	@Test
	@DisplayName("없는 이력과 조회 대상이 아닌 다른 기능 행은 ABUSE-002다")
	void missingOrIneligibleHistoryIsNotFound() throws Exception {
		// given / when / then
		mvc.perform(asAdmin(get(PATH + "/{caseId}", caseId))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ABUSE-002"));
		insertCase("USER", false);
		jdbc.update("update abuse_cases set source_type = 'OTHER', evidence = '{}' where id = ?", bytes(caseId));
		mvc.perform(asAdmin(get(PATH + "/{caseId}", caseId))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ABUSE-002"));
	}

	@ParameterizedTest
	@CsvSource({"USER", "INACTIVE_ADMIN"})
	@DisplayName("비관리자·비활성 관리자는 존재하는 이력과 없는 이력 모두 ABUSE-001로 거절한다")
	void permissionDoesNotRevealExistence(String actorType) throws Exception {
		// given
		insertCase("USER", false);
		UUID actor = actorType.equals("USER") ? userId : inactiveId;
		String role = actorType.equals("USER") ? "USER" : "ADMIN";

		// when / then
		for (UUID target : new UUID[] {caseId, UUID.randomUUID()}) {
			mvc.perform(get(PATH + "/{caseId}", target).header("X-User-ID", actor).header("X-User-Role", role))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ABUSE-001"));
		}
	}

	@Test
	@DisplayName("손상된 저장 근거는 빈 근거·404로 바꾸지 않고 원문 없는 COMMON-001을 반환한다")
	void invalidStoredEvidenceIsInternalError() throws Exception {
		// given
		insertCase("USER", false);
		jdbc.update("update abuse_cases set evidence = '{\"token\":\"private-token\"}' where id = ?", bytes(caseId));

		// when / then
		mvc.perform(asAdmin(get(PATH + "/{caseId}", caseId))).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-001"))
				.andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
		mvc.perform(asAdmin(get(PATH).param("userId", userId.toString())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0]", aMapWithSize(10)));
	}

	@Test
	@DisplayName("실제 사용자 문맥 오류와 경로 UUID 해석 실패도 기존 공통 코드를 유지한다")
	void preservesCommonErrors() throws Exception {
		// given / when / then
		mvc.perform(get(PATH + "/{caseId}", caseId)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("USER-003"));
		mvc.perform(get(PATH + "/{caseId}", caseId).header("X-User-ID", userId).header("X-User-Role", "ADMIN"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
		mvc.perform(asAdmin(get(PATH + "/not-a-uuid"))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
	}

	private void insertUser(UUID id, String role, String state) {
		jdbc.update("""
				insert into users (id, name, role, status, created_at, updated_at)
				values (?, 'GD143 사용자', ?, ?, ?, ?)
				""", bytes(id), role, state, utc(AT), utc(AT));
	}

	private void insertCase(String scope, boolean withEvent) {
		if (withEvent) {
			jdbc.update("""
					insert into events (id, title, description, event_type, weighting_enabled, starts_at, ends_at,
					  status, created_at, updated_at, membership_rule)
					values (?, '저장 전 제목', '설명', 'NO_TICKET', false, ?, ?, 'CLOSED', ?, ?, 'excellent')
					""", bytes(eventId), utc(AT.minusSeconds(3600)), utc(AT), utc(AT), utc(AT));
		}
		jdbc.update("""
				insert into abuse_cases (id, user_id, event_id, source_type, detection_type, detection_reason,
				  evidence, occurred_at, detected_at, blocked_at, blocked_until)
				values (?, ?, ?, 'ENTRY', 'REPEATED_RATE_LIMIT_EXCEEDED',
				  '응모 요청 한도 초과가 반복되어 일시 차단되었습니다.',
				  JSON_OBJECT('ruleVersion', 'stored-v2', 'triggerScope', ?,
				    'windowStartedAt', ?, 'windowEndsAt', ?, 'requestLimit', 37,
				    'rejectedCount', 39, 'rejectionThreshold', 37, 'token', 'private-token'), ?, ?, ?, ?)
				""", bytes(caseId), bytes(userId), withEvent ? bytes(eventId) : null, scope,
				AT.minusSeconds(30).toString(), AT.plusSeconds(30).toString(),
				utc(AT), utc(AT), utc(AT), utc(AT.plusSeconds(600)));
	}

	private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", adminId).header("X-User-Role", "ADMIN");
	}

	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
