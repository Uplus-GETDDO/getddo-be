package com.getddo.api.event;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.getddo.api.support.ApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ApiIntegrationTest
class AdminEventRegistrationTest {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	@Test
	void registersEventAndEveryPrizeTogether() throws Exception {
		UUID adminId = insertUser("ADMIN");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		String response = mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").exists())
				.andExpect(jsonPath("$.data.status").value("SCHEDULED"))
				.andExpect(jsonPath("$.data.startsAt").value("2099-09-30T09:00:00Z"))
				.andExpect(jsonPath("$.data.publicationScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.drawScheduledAt").value("2099-09-30T10:01:00Z"))
				.andExpect(jsonPath("$.data.createdBy").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedFromStatus").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.imageKey").value("events/autumn.png"))
				.andExpect(jsonPath("$.data.prizes.length()").value(2))
				.andExpect(jsonPath("$.data.prizes[0].rank").value(1))
				.andExpect(jsonPath("$.data.prizes[1].rank").value(2))
				.andExpect(jsonPath("$.data.prizes[0].id").exists())
				.andExpect(jsonPath("$.data.prizeImages[0].imageKey").value("prizes/first.png"))
				.andReturn().getResponse().getContentAsString();
		String eventId = com.jayway.jsonpath.JsonPath.read(response, "$.data.id");

		assertThat(count("events")).isEqualTo(previousEvents + 1);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes + 2);
		assertThat(countPrizesForEvent(UUID.fromString(eventId))).isEqualTo(2);
	}

	@Test
	@DisplayName("DTO 변환을 거친 등록 요청의 경품 ID도 Service에서 400으로 거절하고 저장하지 않는다")
	void registrationWithPrizeIdIsRejectedWithoutAnyWrite() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		String request = validRequest().replace("\"rank\": 2", "\"id\": \"" + UUID.randomUUID() + "\", \"rank\": 2");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");
		// when / then
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVENT-004"));
		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	@Test
	void duplicateRankAndNonAdminDoNotCreatePartialData() throws Exception {
		UUID adminId = insertUser("ADMIN");
		UUID userId = insertUser("USER");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest().replace("\"rank\": 2", "\"rank\": 1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-004"));
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", userId.toString()).header("X-User-Role", "USER")
				.header("X-User-Membership", "vip")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("EVENT-006"));

		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	@Test
	void missingPrizesInvalidPeriodAndTicketLimitAreRejected() throws Exception {
		UUID adminId = insertUser("ADMIN");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest().replace("\"prizes\": [", "\"otherPrizes\": [")))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest().replace("2099-09-30T19:00:00+09:00", "2099-09-30T17:00:00+09:00")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-002"));
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validRequest().replace("\"maxTicketsPerUser\": 5", "\"maxTicketsPerUser\": 4")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-003"));

		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	@ParameterizedTest
	@CsvSource({
			"2099-10-01T00:00:00.000000001Z,2099-10-01T00:00:00.000000002Z",
			"2099-10-01T00:00:00.000000501Z,2099-10-01T00:00:00.000000999Z",
			"+10000-10-01T00:00:00Z,+10000-10-02T00:00:00Z",
			"1000-01-01T00:00:00+09:00,1000-01-02T00:00:00+09:00",
			"9999-12-31T23:00:00-09:00,9999-12-31T23:30:00-09:00",
			"9999-12-31T23:59:59Z,9999-12-31T23:59:59.500000Z"
	})
	@DisplayName("UTC 저장 범위·정밀도에 맞지 않는 기간은 400을 반환하고 이벤트·경품을 저장하지 않는다")
	void unstorablePeriodsAreBadRequestsWithoutAnyWrite(String startsAt, String endsAt) throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		// when / then
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(requestWithPeriod(startsAt, endsAt)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-002"));
		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	@Test
	@DisplayName("나노초 입력은 마이크로초로 정규화해 등록 응답·DB·상세 조회가 같은 시각을 제공한다")
	void storesAndReadsNormalizedMicrosecondPeriod() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		String startsAt = "2099-10-01T09:00:00.123456Z";
		String endsAt = "2099-10-01T10:00:00.654321Z";

		// when
		String body = mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestWithPeriod("2099-10-01T18:00:00.123456789+09:00",
						"2099-10-01T19:00:00.654321987+09:00")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.startsAt").value(startsAt))
				.andExpect(jsonPath("$.data.endsAt").value(endsAt))
				.andReturn().getResponse().getContentAsString();
		String eventId = com.jayway.jsonpath.JsonPath.read(body, "$.data.id");

		// then: 상세 조회는 DB에서 시각을 다시 읽는다.
		mvc.perform(get("/api/v1/admin/events/" + eventId)
				.header("X-User-ID", adminId).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.startsAt").value(startsAt))
				.andExpect(jsonPath("$.data.endsAt").value(endsAt));
	}

	@Test
	@DisplayName("영문·한글·이모지가 섞인 65,535바이트 설명을 MySQL에 저장한다")
	void storesDescriptionsAtUtf8ByteLimit() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		String description = "A가😀".repeat(8_191) + "x".repeat(7);
		assertThat(description.getBytes(StandardCharsets.UTF_8)).hasSize(65_535);
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		// when / then
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestWithDescriptions(description, description)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.description").value(description))
				.andExpect(jsonPath("$.data.prizes[0].description").value(description));

		assertThat(count("events")).isEqualTo(previousEvents + 1);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes + 2);
	}

	@Test
	@DisplayName("65,536바이트 이벤트·경품 설명은 400을 반환하고 데이터가 생성되지 않는다")
	void oversizedDescriptionsAreBadRequestsWithoutAnyWrite() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		String description = "A가😀".repeat(8_192);
		assertThat(description.getBytes(StandardCharsets.UTF_8)).hasSize(65_536);
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		// when / then
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestWithDescriptions(description, "경품 설명")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-001"));
		mvc.perform(post("/api/v1/admin/events")
				.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestWithDescriptions("이벤트 설명", description)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-004"));

		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	@Test
	@DisplayName("정상 입력이라도 경품 저장 중 DB 오류가 발생하면 이벤트와 모든 경품이 롤백된다")
	void databaseFailureWhileSavingPrizeRollsBackEvent() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");
		String constraintName = "test_prize_failure_" + adminId.toString().replace("-", "");
		String failingPrizeName = "rollback-prize-" + adminId;
		// 임시 테스트 DB에서만 특정 경품의 INSERT를 실패시켜 입력 검증과 롤백 검증을 분리한다.
		executeSql("ALTER TABLE event_prizes ADD CONSTRAINT " + constraintName
				+ " CHECK (name <> '" + failingPrizeName + "')");

		try {
			// when / then
			Exception failure = mvc.perform(post("/api/v1/admin/events")
					.header("X-User-ID", adminId.toString()).header("X-User-Role", "ADMIN")
					.contentType(MediaType.APPLICATION_JSON)
					.content(validRequest().replace("경품 A", failingPrizeName)))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("COMMON-001"))
					.andReturn().getResolvedException();

			assertThat(failure).hasStackTraceContaining(constraintName);
			assertThat(count("events")).isEqualTo(previousEvents);
			assertThat(count("event_prizes")).isEqualTo(previousPrizes);
		} finally {
			executeSql("ALTER TABLE event_prizes DROP CHECK " + constraintName);
		}
	}

	@Test
	@DisplayName("관리자 이벤트 등록도 공통 MVC에서 역할 헤더 누락과 DB 불일치를 거절한다")
	void requiresMatchingUserContextBeforeRegisteringEvent() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		UUID userId = insertUser("USER");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		// when / then
		mvc.perform(post("/api/v1/admin/events").header("X-User-ID", adminId)
				.contentType(MediaType.APPLICATION_JSON).content(validRequest()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
		mvc.perform(post("/api/v1/admin/events").header("X-User-ID", userId).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(validRequest()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
		mvc.perform(post("/api/v1/admin/events").header("X-User-ID", UUID.randomUUID())
				.header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(validRequest()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-002"));
		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	@Test
	@DisplayName("공통 MVC 검증 후에도 비활성 관리자의 등록은 서비스가 거절한다")
	void inactiveAdminStillCannotRegisterEvent() throws Exception {
		// given
		UUID adminId = insertUser("ADMIN");
		executeSql("UPDATE users SET status = 'INACTIVE' WHERE id = UNHEX(REPLACE('" + adminId + "', '-', ''))");
		long previousEvents = count("events");
		long previousPrizes = count("event_prizes");

		// when / then
		mvc.perform(post("/api/v1/admin/events").header("X-User-ID", adminId).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(validRequest()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EVENT-006"));
		assertThat(count("events")).isEqualTo(previousEvents);
		assertThat(count("event_prizes")).isEqualTo(previousPrizes);
	}

	private void executeSql(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private String requestWithDescriptions(String description, String prizeDescription) {
		return validRequest().replace("유효한 응모권으로 응모", description)
				.replace("\"name\": \"경품 A\"",
						"\"name\": \"경품 A\", \"description\": \"" + prizeDescription + "\"");
	}

	private String requestWithPeriod(String startsAt, String endsAt) {
		return validRequest().replace("2099-09-30T18:00:00+09:00", startsAt)
				.replace("2099-09-30T19:00:00+09:00", endsAt);
	}

	private UUID insertUser(String role) throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO users (id, name, role, status, membership, created_at, updated_at)
				VALUES (UNHEX(REPLACE(?, '-', '')), '관리자', ?, 'ACTIVE', 'VIP', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""")) {
			statement.setString(1, id.toString());
			statement.setString(2, role);
			statement.executeUpdate();
		}
		return id;
	}

	private long count(String table) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
				ResultSet result = statement.executeQuery()) {
			result.next();
			return result.getLong(1);
		}
	}

	private long countPrizesForEvent(UUID eventId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
				SELECT COUNT(*) FROM event_prizes p
				WHERE p.event_id = UNHEX(REPLACE(?, '-', ''))
				""")) {
			statement.setString(1, eventId.toString());
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private String validRequest() {
		return """
				{
				  "title": "가을 경품 이벤트",
				  "description": "유효한 응모권으로 응모",
				  "imageKey": "events/autumn.png",
				  "eventType": "TICKET",
				  "weightingEnabled": true,
				  "maxTicketsPerUser": 5,
				  "membershipRule": "vip",
				  "startsAt": "2099-09-30T18:00:00+09:00",
				  "endsAt": "2099-09-30T19:00:00+09:00",
				  "prizes": [
				    { "rank": 2, "name": "경품 B", "winnerCount": 3 },
				    { "rank": 1, "name": "경품 A", "imageKey": "prizes/first.png", "winnerCount": 1 }
				  ]
				}
				""";
	}
}
