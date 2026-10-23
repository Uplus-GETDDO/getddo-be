package com.getddo.api.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.db.common.util.UuidBinary;
import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MySQL·JPA·JDBC를 통과하는 상세 이미지 저장·조회·수정 계약을 검증한다. */
@ApiIntegrationTest
@TestPropertySource(properties = "getddo.images.base-url=https://cdn.example.com/images/")
class EventDetailImageIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	private UUID admin;
	private UUID user;
	private UUID event;
	private UUID prize;

	@BeforeEach
	void setUp() {
		admin = insertUser("ADMIN", null);
		user = insertUser("USER", "vip");
	}

	@AfterEach
	void cleanUp() {
		if (event != null) {
			jdbc.update("delete from audit_logs where target_type='EVENT' and target_id=?", bytes(event));
			jdbc.update("delete from event_prizes where event_id=?", bytes(event));
			jdbc.update("delete from events where id=?", bytes(event));
		}
		for (UUID id : List.of(admin, user)) {
			jdbc.update("delete from users where id=?", bytes(id));
		}
	}

	@Test
	@DisplayName("상세 이미지만 수정·제거하고 동일 PUT은 감사 기록을 중복 생성하지 않는다")
	void storesReadsChangesAndRemovesDetailImage() throws Exception {
		// given
		create("events/detail.png");
		assertThat(storedKey()).isEqualTo("events/detail.png");
		assertThat(auditKey("EVENT_CREATE", "after_data")).isEqualTo("events/detail.png");
		mvc.perform(get("/api/v1/events/" + event).header("X-User-ID", user)
				.header("X-User-Role", "USER").header("X-User-Membership", "vip"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.detailImageUrl").value("https://cdn.example.com/images/events/detail.png"))
				.andExpect(jsonPath("$.data.detailImageKey").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/images/events/thumbnail.png"));
		mvc.perform(get("/api/v1/admin/events/" + event).header("X-User-ID", admin).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.detailImageKey").value("events/detail.png"));
		mvc.perform(get("/api/v1/admin/events").param("keyword", "상세 이미지 " + admin)
				.header("X-User-ID", admin).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].detailImageKey").value("events/detail.png"));
		String listResponse = mvc.perform(get("/api/v1/events").header("X-User-ID", user)
				.header("X-User-Role", "USER").header("X-User-Membership", "vip"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> items = JsonPath.read(listResponse, "$.data.items");
		assertThat(items).filteredOn(item -> event.toString().equals(item.get("id")))
				.singleElement()
				.satisfies(item -> assertThat(item).doesNotContainKey("detailImageUrl"));

		// when
		modify("events/changed.png");
		// then
		assertThat(storedKey()).isEqualTo("events/changed.png");
		assertThat(auditKey("EVENT_UPDATE", "before_data")).isEqualTo("events/detail.png");
		assertThat(auditKey("EVENT_UPDATE", "after_data")).isEqualTo("events/changed.png");
		modify("events/changed.png");
		assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id=? and action='EVENT_UPDATE'",
				Long.class, bytes(event))).isEqualTo(1);
		modify(null);
		assertThat(storedKey()).isNull();
		assertThat(jdbc.queryForObject("select image_key from events where id=?", String.class, bytes(event)))
				.isEqualTo("events/thumbnail.png");
		assertThat(jdbc.queryForObject("select count(*) from notification_jobs where event_id=?", Long.class, bytes(event)))
				.isZero();
	}

	@Test
	@DisplayName("상세 이미지가 없는 기존 요청은 null로 저장·조회하고 501자 입력은 저장 전에 거절한다")
	void supportsOmittedKeyAndRejectsOversizedKey() throws Exception {
		// given
		create(null);
		assertThat(storedKey()).isNull();
		mvc.perform(get("/api/v1/events/" + event).header("X-User-ID", user)
				.header("X-User-Role", "USER").header("X-User-Membership", "vip"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.detailImageUrl").value(nullValue()));

		// when / then
		String invalid = body("x".repeat(501));
		mvc.perform(post("/api/v1/admin/events").header("X-User-ID", admin).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(put("/api/v1/admin/events/" + event).header("X-User-ID", admin).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		assertThat(storedKey()).isNull();
		assertThat(jdbc.queryForObject("select count(*) from events where title=?", Long.class, "상세 이미지 " + admin))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id=?", Long.class, bytes(event)))
				.isEqualTo(1);
	}

	private void create(String key) throws Exception {
		String response = mvc.perform(post("/api/v1/admin/events").header("X-User-ID", admin).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(body(key)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		event = UUID.fromString(JsonPath.read(response, "$.data.id"));
		prize = UUID.fromString(JsonPath.read(response, "$.data.prizes[0].id"));
		assertThat(JsonPath.<String>read(response, "$.data.detailImageKey")).isEqualTo(key);
		assertThat(JsonPath.<String>read(response, "$.data.detailImageUrl"))
				.isEqualTo(key == null ? null : "https://cdn.example.com/images/" + key);
	}

	private void modify(String key) throws Exception {
		mvc.perform(put("/api/v1/admin/events/" + event).header("X-User-ID", admin).header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(body(key)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.prizes[0].id").value(prize.toString()));
	}

	private String body(String key) {
		return """
				{"title":"상세 이미지 %s","description":"텍스트 설명 유지","imageKey":"events/thumbnail.png",
				%s "eventType":"NO_TICKET","weightingEnabled":false,"membershipRule":"vip",
				"startsAt":"2099-10-23T10:00:00+09:00","endsAt":"2099-10-24T10:00:00+09:00",
				"prizes":[{%s "rank":1,"name":"경품","winnerCount":1}]}
				""".formatted(admin, key == null ? "" : "\"detailImageKey\":\"" + key + "\",",
				prize == null ? "" : "\"id\":\"" + prize + "\",");
	}

	private UUID insertUser(String role, String membership) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into users(id,name,role,status,membership,created_at,updated_at)
				values (?, '상세 이미지 테스트', ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(id), role, membership);
		return id;
	}

	private String storedKey() {
		return jdbc.queryForObject("select detail_image_key from events where id=?", String.class, bytes(event));
	}

	private String auditKey(String action, String column) {
		return jdbc.queryForObject("select json_unquote(json_extract(" + column
				+ ", '$.detailImageKey')) from audit_logs where target_id=? and action=?",
				String.class, bytes(event), action);
	}

	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}
}
