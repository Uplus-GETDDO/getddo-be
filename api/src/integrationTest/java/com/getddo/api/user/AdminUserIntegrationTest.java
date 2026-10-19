package com.getddo.api.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.db.common.util.UuidBinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 사용자 문맥·MySQL·MapStruct·MVC를 연결해 관리자 조회 계약을 검증한다. */
@ApiIntegrationTest
class AdminUserIntegrationTest {
	private static final String PATH = "/api/v1/admin/users";
	private static final UUID ADMIN = id(1);
	private static final UUID USER = id(2);
	private static final UUID INACTIVE = id(3);
	private static final UUID PERCENT = id(4);
	private static final UUID UNDERSCORE = id(5);
	private static final UUID BANG = id(6);
	private static final UUID EXCELLENT = id(7);

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	private long activeRegularUsersBeforeSeed;

	@BeforeEach
	void seed() {
		activeRegularUsersBeforeSeed = jdbc.queryForObject(
				"select count(*) from users where role = 'USER' and status = 'ACTIVE'", Long.class);
		insert(ADMIN, "GD206 관리자", "ADMIN", "ACTIVE", null);
		insert(USER, "GD206 일반", "USER", "ACTIVE", "VIP");
		insert(INACTIVE, "GD206 비활성", "ADMIN", "INACTIVE", null);
		insert(PERCENT, "GD206 % 검색", "USER", "ACTIVE", "VIP");
		insert(UNDERSCORE, "GD206 _ 검색", "USER", "INACTIVE", "VVIP");
		insert(BANG, "GD206 ! 검색", "USER", "ACTIVE", "VIP");
		insert(EXCELLENT, "GD206 우수", "USER", "ACTIVE", "EXCELLENT");
	}

	@AfterEach
	void clean() {
		for (int n = 1; n <= 7; n++) jdbc.update("delete from users where id = ?", UuidBinary.toBytes(id(n)));
	}

	@Test
	@DisplayName("기본 목록은 1페이지 20명이고 같은 생성시각에서는 ID 내림차순이며 7개 필드만 공개한다")
	void defaultPageAndStableSort() throws Exception {
		// given
		long total = jdbc.queryForObject("select count(*) from users", Long.class);
		// when / then
		mvc.perform(admin(PATH)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(total));
		mvc.perform(admin(PATH).param("keyword", "GD206").param("size", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(7))
				.andExpect(jsonPath("$.data.items", hasSize(2)))
				.andExpect(jsonPath("$.data.items[0].id").value(EXCELLENT.toString()))
				.andExpect(jsonPath("$.data.items[1].id").value(BANG.toString()))
				.andExpect(jsonPath("$.data.items[0]", aMapWithSize(7)))
				.andExpect(jsonPath("$.data.items[0].membership").value("excellent"))
				.andExpect(jsonPath("$.data.items[0].createdAt").value("2040-01-01T00:00:00.123456Z"))
				.andExpect(jsonPath("$.data.items[0].updatedAt").value("2040-01-02T00:00:00.234567Z"));
		mvc.perform(admin(PATH).param("keyword", "GD206").param("size", "2").param("page", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(UNDERSCORE.toString()))
				.andExpect(jsonPath("$.data.items[1].id").value(PERCENT.toString()));
	}

	@Test
	@DisplayName("ID·이름·역할·상태·멤버십은 AND로 필터링하고 비활성 대상도 조회한다")
	void combinesEveryFilter() throws Exception {
		// given / when / then
		MockHttpServletRequestBuilder request = admin(PATH).param("userId", UNDERSCORE.toString())
				.param("keyword", "  GD206 _  ").param("role", "USER").param("status", "INACTIVE");
		mvc.perform(request.param("membership", "vvip")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(UNDERSCORE.toString()))
				.andExpect(jsonPath("$.data.items[0].status").value("INACTIVE"));
		mvc.perform(admin(PATH).param("userId", UNDERSCORE.toString()).param("membership", "vip"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0))
				.andExpect(jsonPath("$.data.items", hasSize(0)));
	}

	@Test
	@DisplayName("참여율 분모는 사용자 목록의 크기·집계 기간과 무관하며 비활성화 시 두 API에 반영된다")
	void dailyStatsMatchesActiveRegularUserTotal() throws Exception {
		// given
		// 시드 7명 중 활성 일반 사용자는 4명이다. 관리자 2명과 비활성 일반 사용자 1명은 제외한다.
		long total = activeRegularUsersBeforeSeed + 4;

		// when / then
		mvc.perform(admin(PATH).param("role", "USER").param("status", "ACTIVE").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].role").value("USER"))
				.andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.totalElements").value(total));
		mvc.perform(admin("/api/v1/admin/stats/daily").param("from", "2031-03-10").param("to", "2031-03-10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalUserCount").value(total));
		mvc.perform(admin("/api/v1/admin/stats/daily").param("from", "2031-04-01").param("to", "2031-04-30"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalUserCount").value(total));

		// when: 활성 일반 사용자를 비활성화하면 현재 사용자 수에서 빠진다.
		jdbc.update("update users set status = 'INACTIVE' where id = ?", UuidBinary.toBytes(USER));

		// then
		mvc.perform(admin(PATH).param("role", "USER").param("status", "ACTIVE").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(total - 1));
		mvc.perform(admin("/api/v1/admin/stats/daily").param("from", "2031-03-10").param("to", "2031-03-10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalUserCount").value(total - 1));
	}

	@ParameterizedTest
	@ValueSource(strings = {"%", "_", "!"})
	@DisplayName("이름의 %·_·!는 와일드카드가 아닌 문자 그대로 검색한다")
	void escapesNameCharacters(String keyword) throws Exception {
		// given / when / then
		mvc.perform(admin(PATH).param("keyword", keyword)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].name").value("GD206 " + keyword + " 검색"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"5", "2147483647"})
	@DisplayName("범위 밖 페이지와 int 오프셋 초과는 전체 건수를 유지한 빈 목록이다")
	void returnsEmptyPage(String page) throws Exception {
		// given / when / then
		mvc.perform(admin(PATH).param("keyword", "GD206").param("size", "100").param("page", page))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(7))
				.andExpect(jsonPath("$.data.items", hasSize(0)));
	}

	@Test
	@DisplayName("빈 이름 검색은 필터 없음이며 없는 ID 검색은 빈 목록이다")
	void handlesBlankAndMissingId() throws Exception {
		// given
		long total = jdbc.queryForObject("select count(*) from users", Long.class);
		// when / then
		mvc.perform(admin(PATH).param("keyword", "   ")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(total));
		mvc.perform(admin(PATH).param("userId", UUID.randomUUID().toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(0));
	}

	@Test
	@DisplayName("상세는 비활성 관리자와 null 멤버십도 반환하며 연락처·정지 정보를 공개하지 않는다")
	void detailAndReadOnly() throws Exception {
		// given
		List<Map<String, Object>> before = fixtureRows();
		// when / then
		mvc.perform(admin(PATH + "/" + INACTIVE)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data", aMapWithSize(7)))
				.andExpect(jsonPath("$.data.id").value(INACTIVE.toString()))
				.andExpect(jsonPath("$.data.role").value("ADMIN"))
				.andExpect(jsonPath("$.data.status").value("INACTIVE"))
				.andExpect(jsonPath("$.data.membership").value(nullValue()))
				.andExpect(jsonPath("$.data.phoneNum").doesNotExist())
				.andExpect(jsonPath("$.data.email").doesNotExist())
				.andExpect(jsonPath("$.data.suspendedAt").doesNotExist())
				.andExpect(jsonPath("$.data.suspensionReason").doesNotExist());
		mvc.perform(admin(PATH).param("keyword", "GD206")).andExpect(status().isOk());
		assertThat(fixtureRows()).usingRecursiveComparison().isEqualTo(before);
	}

	@Test
	@DisplayName("없는 상세와 잘못된 UUID는 각각 USER-010과 COMMON-005다")
	void rejectsMissingOrMalformedDetail() throws Exception {
		// given / when / then
		mvc.perform(admin(PATH + "/" + UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("USER-010"));
		mvc.perform(admin(PATH + "/invalid")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
	}

	@ParameterizedTest
	@CsvSource({"page,0", "page,-1", "size,0", "size,101"})
	@DisplayName("페이지 조건 위반은 USER-008이다")
	void rejectsInvalidPage(String key, String value) throws Exception {
		// given / when / then
		mvc.perform(admin(PATH).param(key, value)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("USER-008"));
	}

	@Test
	@DisplayName("20자를 넘는 이름 검색은 USER-008이다")
	void rejectsLongKeyword() throws Exception {
		// given / when / then
		mvc.perform(admin(PATH).param("keyword", "가".repeat(21))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("USER-008"));
	}

	@ParameterizedTest
	@CsvSource({"page,abc", "size,2147483648", "userId,abc", "role,user", "status,UNKNOWN", "membership,VIP"})
	@DisplayName("UUID·enum·숫자와 소문자 멤버십 형식 위반은 COMMON-005다")
	void rejectsMalformedFilters(String key, String value) throws Exception {
		// given / when / then
		mvc.perform(admin(PATH).param(key, value)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
	}

	@Test
	@DisplayName("일반 사용자·비활성 관리자는 두 API 모두 USER-009로 거절한다")
	void rejectsUnqualifiedActors() throws Exception {
		// given / when / then
		for (String path : List.of(PATH, PATH + "/" + USER)) {
			mvc.perform(get(path).header("X-User-ID", USER).header("X-User-Role", "USER"))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-009"));
			mvc.perform(get(path).header("X-User-ID", INACTIVE).header("X-User-Role", "ADMIN"))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-009"));
		}
	}

	@Test
	@DisplayName("공통 문맥의 누락·위장 역할·선택 멤버십 대조 오류를 유지한다")
	void preservesContextErrors() throws Exception {
		// given / when / then
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
		mvc.perform(get(PATH).header("X-User-ID", UUID.randomUUID()).header("X-User-Role", "ADMIN"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-002"));
		mvc.perform(get(PATH).header("X-User-ID", USER).header("X-User-Role", "ADMIN"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
		mvc.perform(admin(PATH).header("X-User-Membership", "vip"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER-006"));
	}

	private static MockHttpServletRequestBuilder admin(String path) {
		return get(path).header("X-User-ID", ADMIN).header("X-User-Role", "ADMIN");
	}

	private static UUID id(int value) {
		return UUID.fromString("00000000-0000-0000-0000-00000002060" + value);
	}

	private List<Map<String, Object>> fixtureRows() {
		return jdbc.queryForList("select * from users where name like 'GD206%' order by id");
	}

	private void insert(UUID id, String name, String role, String state, String membership) {
		jdbc.update("""
				insert into users (id, name, role, status, membership, phone_num, email,
				                   suspended_at, suspension_reason, created_at, updated_at)
				values (?, ?, ?, ?, ?, ?, ?,
				        '2039-12-31 00:00:00', '비공개 검증 자료',
				        '2040-01-01 00:00:00.123456', '2040-01-02 00:00:00.234567')
				""", UuidBinary.toBytes(id), name, role, state, membership,
				"0100002060" + id.getLeastSignificantBits() % 10, id + "@example.test");
	}
}
