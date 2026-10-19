package com.getddo.api.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.event.domain.EventQueryFilter;
import com.getddo.core.event.domain.EventStatus;
import com.getddo.core.event.domain.EventView;
import com.getddo.core.event.repository.EventQueryRepository;
import com.getddo.db.common.util.UuidBinary;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ApiIntegrationTest
@Transactional
class EventQueryIntegrationTest {
	private static final String CREATED_AT = "2199-01-01T00:00:00Z";
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private EventQueryRepository events;
	private UUID admin;
	private UUID user;
	private String prefix;

	@BeforeEach
	void setUp() {
		// given: 각 테스트의 데이터는 종료 시 롤백하며 다른 API 테스트의 시드는 보존한다.
		admin = insertUser("ADMIN", null, "ACTIVE");
		user = insertUser("USER", "vip", "ACTIVE");
		prefix = "query-" + UUID.randomUUID();
	}

	@Test
	@DisplayName("Swagger에 관리자 검색 조건과 날짜 형식을 공개하고 내부 검증용 입력은 숨긴다")
	void adminSearchDtoPreservesOpenApiParameters() throws Exception {
		// given / when
		String response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> parameters = JsonPath.read(response,
				"$.paths['/api/v1/admin/events'].get.parameters");
		// then
		assertThat(parameters.stream().filter(parameter -> "query".equals(parameter.get("in"))).toList())
				.extracting(parameter -> parameter.get("name"))
				.containsExactlyInAnyOrder("page", "size", "keyword", "status", "eventType", "from", "to",
						"weightingEnabled", "createdFrom", "createdTo", "sort");
		for (String date : new String[]{"from", "to", "createdFrom", "createdTo"}) {
			Map<?, ?> schema = (Map<?, ?>) parameters.stream()
					.filter(parameter -> date.equals(parameter.get("name"))).findFirst().orElseThrow().get("schema");
			assertThat(schema.get("type")).isEqualTo("string");
			assertThat(schema.get("format")).isEqualTo("date");
		}
	}

	@Test
	@DisplayName("등록 API로 저장한 오프셋 시각은 두 상세 API에서 같은 UTC 시각으로 조회된다")
	void registrationAndQueryUseSameTimeInterpretation() throws Exception {
		// given
		String body = """
				{"title":"조회 연계","description":"등록 후 조회","eventType":"NO_TICKET",
				"weightingEnabled":false,"maxTicketsPerUser":null,"membershipRule":"excellent",
				"startsAt":"2099-10-10T18:00:00+09:00","endsAt":"2099-10-10T19:00:00+09:00",
				"prizes":[{"rank":1,"name":"경품","winnerCount":2}]}
				""";
		// when
		String response = mvc.perform(post("/api/v1/admin/events").header("X-User-ID", admin.toString())
				.header("X-User-Role", "ADMIN")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(response, "$.data.id");
		// then
		for (String path : new String[]{"/api/v1/events/", "/api/v1/admin/events/"}) {
			request(admin, path + id).andExpect(status().isOk())
					.andExpect(jsonPath("$.data.startsAt").value("2099-10-10T09:00:00Z"))
					.andExpect(jsonPath("$.data.endsAt").value("2099-10-10T10:00:00Z"))
					.andExpect(jsonPath("$.data.maxTicketsPerUser").value(nullValue()))
					.andExpect(jsonPath("$.data.prizes[0].winnerCount").value(2));
		}
	}

	@Test
	@DisplayName("JDBC 조회는 DB 세션 시간대와 무관하게 UTC 마이크로초·UUID·NULL을 유지한다")
	void jdbcQueryPreservesUtcPrecisionAndNullableValues() throws Exception {
		// given
		String startsAt = "2099-10-09T15:00:00.123456Z";
		String endsAt = "2099-10-10T15:00:00.654321Z";
		UUID event = insertEvent(prefix, "SCHEDULED", "NO_TICKET", "excellent", startsAt, endsAt);
		UUID prize = insertPrize(event, 1, 2);
		insertEvent(prefix + "-before", "SCHEDULED", "NO_TICKET", "excellent",
				"2099-10-09T14:59:59Z", "2099-10-09T15:00:00Z");
		insertEvent(prefix + "-after", "SCHEDULED", "NO_TICKET", "excellent",
				"2099-10-10T15:00:00Z", "2099-10-10T15:00:01Z");
		jdbc.update("update events set image_key=null, created_at=?, updated_at=? where id=?",
				at(startsAt), at(endsAt), bytes(event));
		jdbc.update("update event_prizes set description=null, image_key=null where id=?", bytes(prize));
		String originalTimeZone = jdbc.queryForObject("select @@session.time_zone", String.class);
		try {
			// when: 현재 트랜잭션의 연결만 바꾸며 풀에 반환하기 전에 복원한다.
			jdbc.update("set session time_zone = ?", "+09:00");
			// then
			request(admin, "/api/v1/admin/events/" + event)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.id").value(event.toString()))
					.andExpect(jsonPath("$.data.createdBy").doesNotHaveJsonPath())
					.andExpect(jsonPath("$.data.startsAt").value(startsAt))
					.andExpect(jsonPath("$.data.endsAt").value(endsAt))
					.andExpect(jsonPath("$.data.createdAt").value(startsAt))
					.andExpect(jsonPath("$.data.updatedAt").value(endsAt))
					.andExpect(jsonPath("$.data.maxTicketsPerUser").value(nullValue()))
					.andExpect(jsonPath("$.data.suspendedFromStatus").doesNotHaveJsonPath())
					.andExpect(jsonPath("$.data.suspendedAt").doesNotHaveJsonPath())
					.andExpect(jsonPath("$.data.canceledAt").value(nullValue()))
					.andExpect(jsonPath("$.data.imageKey").value(nullValue()))
					.andExpect(jsonPath("$.data.prizes[0].id").value(prize.toString()))
					.andExpect(jsonPath("$.data.prizes[0].description").value(nullValue()))
					.andExpect(jsonPath("$.data.prizeImages[0].imageKey").value(nullValue()));
			request(admin, get("/api/v1/admin/events").param("keyword", prefix)
					.param("from", "2099-10-10").param("to", "2099-10-10"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.totalElements").value(1))
					.andExpect(jsonPath("$.data.items[0].id").value(event.toString()))
					.andExpect(jsonPath("$.data.items[0].startsAt").value(startsAt));
		} finally {
			jdbc.update("set session time_zone = ?", originalTimeZone);
		}
	}

	@Test
	@DisplayName("JDBC 페이지 오프셋은 int 범위를 넘겨도 전체 건수를 유지하며 빈 목록을 반환한다")
	void jdbcPaginationKeepsLongOffset() throws Exception {
		// given
		insertEvent(prefix, "SCHEDULED", "NO_TICKET", "excellent",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		// when / then
		request(admin, get("/api/v1/admin/events").param("keyword", prefix)
				.param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isEmpty())
				.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	@DisplayName("사용자·관리자 상세는 등수순 경품·UTC 시각을 반환하고 사용자에게 관리 정보를 노출하지 않는다")
	void detailProvidesSortedPrizesAndSeparatesPublicFields() throws Exception {
		// given
		UUID event = insertEvent(prefix, "CANCELED", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		UUID second = insertPrize(event, 2, 3);
		UUID first = insertPrize(event, 1, 1);
		jdbc.update("update events set canceled_at=? where id=?", at("2099-10-07T00:00:00Z"), bytes(event));
		// when / then
		request(user, "/api/v1/events/" + event)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(event.toString()))
				.andExpect(jsonPath("$.data.status").value("CANCELED"))
				.andExpect(jsonPath("$.data.description").value("상세 설명"))
				.andExpect(jsonPath("$.data.startsAt").value("2099-10-05T00:00:00Z"))
				.andExpect(jsonPath("$.data.publicationScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.drawScheduledAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.serverTime").isString())
				.andExpect(jsonPath("$.data.maxTicketsPerUser").value(5))
				.andExpect(jsonPath("$.data.prizes[0].id").value(first.toString()))
				.andExpect(jsonPath("$.data.prizes[0].rank").value(1))
				.andExpect(jsonPath("$.data.prizes[1].id").value(second.toString()))
				.andExpect(jsonPath("$.data.prizes[1].winnerCount").value(3))
				.andExpect(jsonPath("$.data.prizes[0].imageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data.prizes[0].imageKey").doesNotExist())
				.andExpect(jsonPath("$.data.imageKey").doesNotExist())
				.andExpect(jsonPath("$.data.createdBy").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedFromStatus").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.canceledAt").doesNotHaveJsonPath());
		request(admin, "/api/v1/admin/events/" + event)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELED"))
				.andExpect(jsonPath("$.data.createdBy").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.imageKey").value("events/image.png"))
				.andExpect(jsonPath("$.data.suspendedFromStatus").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.suspendedAt").doesNotHaveJsonPath())
				.andExpect(jsonPath("$.data.canceledAt").value("2099-10-07T00:00:00Z"))
				.andExpect(jsonPath("$.data.prizeImages[0].prizeId").value(first.toString()))
				.andExpect(jsonPath("$.data.prizeImages[0].imageKey").value("prizes/image.png"));
	}

	@Test
	@DisplayName("목록은 기본 페이징을 적용하고 생성 시각이 같아도 UUID 순서로 페이지가 안정적으로 나뉜다")
	void stablePaginationAndAdminListPrizeBatch() throws Exception {
		// given
		long previous = jdbc.queryForObject("select count(*) from events where deleted_at is null", Long.class);
		UUID low = UUID.fromString("2199abcd-1234-7000-8000-000000000001");
		UUID high = UUID.fromString("2199abcd-1234-7000-8000-000000000002");
		insertEvent(low, prefix + "-low", "SCHEDULED", "NO_TICKET", "excellent",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertEvent(high, prefix + "-high", "SCHEDULED", "NO_TICKET", "excellent",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertPrize(high, 2, 4);
		insertPrize(high, 1, 2);
		// when / then
		request(user, "/api/v1/events")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page").value(1))
				.andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(previous + 2))
				.andExpect(jsonPath("$.data.items[0].id").value(high.toString()))
				.andExpect(jsonPath("$.data.items[0].prizes").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].imageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data.totalPages").doesNotExist());
		request(user, get("/api/v1/events").param("size", "1").param("page", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(low.toString()))
				.andExpect(jsonPath("$.data.totalElements").value(previous + 2));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.items[0].id").value(high.toString()))
				.andExpect(jsonPath("$.data.items[0].prizes[0].rank").value(1))
				.andExpect(jsonPath("$.data.items[0].prizes[1].winnerCount").value(4));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("page", "3").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isEmpty())
				.andExpect(jsonPath("$.data.totalElements").value(2));
	}

	@Test
	@DisplayName("사용자 목록의 상태·유형·최소 멤버십 필터는 함께 적용된다")
	void combinesPublicFiltersWithoutTreatingBrowsingAsEntry() throws Exception {
		// given
		long previous = jdbc.queryForObject("""
				select count(*) from events where deleted_at is null
				and status='OPEN' and event_type='NO_TICKET' and membership_rule='vvip'
				""", Long.class);
		UUID matched = insertEvent(prefix + "-matched", "OPEN", "NO_TICKET", "vvip",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertEvent(prefix + "-status", "CLOSED", "NO_TICKET", "vvip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertEvent(prefix + "-type", "OPEN", "TICKET", "vvip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertEvent(prefix + "-membership", "OPEN", "NO_TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		// when / then: vip 사용자도 vvip 이벤트 정보를 조회할 수 있다. 응모 자격 검사와는 구분한다.
		request(user, get("/api/v1/events").param("status", "OPEN").param("eventType", "NO_TICKET")
				.param("membershipRule", "vvip"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(previous + 1))
				.andExpect(jsonPath("$.data.items[0].id").value(matched.toString()));
	}

	@Test
	@DisplayName("삭제된 이벤트는 두 목록과 상세에서 제외하며 기존 경품 데이터는 변경하지 않는다")
	void deletedEventsAreInvisibleToBothViews() throws Exception {
		// given
		long previous = jdbc.queryForObject("select count(*) from events where deleted_at is null", Long.class);
		UUID deleted = insertEvent(prefix, "SCHEDULED", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		UUID prize = insertPrize(deleted, 1, 2);
		jdbc.update("update events set deleted_at=? where id=?", at(CREATED_AT), bytes(deleted));
		// when / then
		request(user, "/api/v1/events").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(previous));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isEmpty())
				.andExpect(jsonPath("$.data.totalElements").value(0));
		for (String path : new String[]{"/api/v1/events/", "/api/v1/admin/events/"}) {
			request(admin, path + deleted).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("EVENT-007"));
			request(admin, path + UUID.randomUUID()).andExpect(status().isNotFound());
		}
		assertThat(jdbc.queryForObject("select count(*) from event_prizes where id=?", Integer.class, bytes(prize))).isOne();
	}

	@Test
	@DisplayName("관리자 날짜 검색은 KST 기준 전체 날짜와 겹치는 이벤트만 반환한다")
	void periodOverlapsAndExcludesTouchingBoundaries() throws Exception {
		// given
		insertEvent(prefix + "-early", "OPEN", "TICKET", "vip", "2099-10-04T15:00:00Z", "2099-10-14T15:00:00Z");
		insertEvent(prefix + "-inside", "OPEN", "TICKET", "vip", "2099-10-11T15:00:00Z", "2099-10-17T15:00:00Z");
		insertEvent(prefix + "-late", "OPEN", "TICKET", "vip", "2099-10-18T15:00:00Z", "2099-10-24T15:00:00Z");
		insertEvent(prefix + "-endsAtFrom", "OPEN", "TICKET", "vip", "2099-10-01T15:00:00Z", "2099-10-09T15:00:00Z");
		insertEvent(prefix + "-startsAtTo", "OPEN", "TICKET", "vip", "2099-10-19T15:00:00Z", "2099-10-24T15:00:00Z");
		insertEvent(prefix + "-wrongStatus", "CLOSED", "TICKET", "vip", "2099-10-11T15:00:00Z", "2099-10-17T15:00:00Z");
		insertEvent(prefix + "-wrongType", "OPEN", "NO_TICKET", "vip", "2099-10-11T15:00:00Z", "2099-10-17T15:00:00Z");
		// when / then
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("status", "OPEN").param("eventType", "TICKET")
				.param("from", "2099-10-10").param("to", "2099-10-19"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(3))
				.andExpect(jsonPath("$.data.items.length()").value(3));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("from", "2099-10-20"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("to", "2099-10-09"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
	}

	@ParameterizedTest
	@CsvSource({"from,0999-12-31", "to,0999-12-31", "from,1000-01-01", "to,0000-01-01"})
	@DisplayName("KST 검색 날짜를 변환한 UTC 경계가 지원 범위를 벗어나면 400 EVENT-008을 반환한다")
	void unsupportedSearchDatesAreBadRequests(String parameter, String value) throws Exception {
		// given / when / then
		request(admin, get("/api/v1/admin/events").param(parameter, value))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVENT-008"));
	}

	@Test
	@DisplayName("지원 범위 안의 날짜는 MySQL에서 단일·양쪽 조건 모두 정상 조회한다")
	void supportedSearchBoundariesWorkWithDatabase() throws Exception {
		// given
		insertEvent(prefix, "OPEN", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		String from = "1000-01-02";
		String to = "9999-12-31";
		// when / then
		for (MockHttpServletRequestBuilder builder : new MockHttpServletRequestBuilder[]{
				get("/api/v1/admin/events").param("from", from),
				get("/api/v1/admin/events").param("to", to),
				get("/api/v1/admin/events").param("from", from).param("to", to)}) {
			request(admin, builder.param("keyword", prefix)).andExpect(status().isOk())
					.andExpect(jsonPath("$.data.totalElements").value(1));
		}
	}

	@Test
	@DisplayName("같은 날짜 검색은 KST 자정부터 마지막 마이크로초까지 포함하고 맞닿는 이벤트는 제외한다")
	void sameDateIncludesWholeKstDay() throws Exception {
		// given: 10월 10일 KST는 UTC 10월 9일 15시부터 10월 10일 15시 직전까지다.
		UUID first = insertEvent(prefix + "-first", "OPEN", "TICKET", "vip",
				"2099-10-09T15:00:00Z", "2099-10-09T15:00:00.000001Z");
		UUID last = insertEvent(prefix + "-last", "OPEN", "TICKET", "vip",
				"2099-10-10T14:59:59.999999Z", "2099-10-10T15:00:00Z");
		UUID spanning = insertEvent(prefix + "-spanning", "OPEN", "TICKET", "vip",
				"2099-10-08T15:00:00Z", "2099-10-11T15:00:00Z");
		insertEvent(prefix + "-before", "OPEN", "TICKET", "vip",
				"2099-10-08T15:00:00Z", "2099-10-09T15:00:00Z");
		insertEvent(prefix + "-after", "OPEN", "TICKET", "vip",
				"2099-10-10T15:00:00Z", "2099-10-11T15:00:00Z");
		// when / then
		request(admin, get("/api/v1/admin/events").param("keyword", prefix)
				.param("from", "2099-10-10").param("to", "2099-10-10"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
				.andExpect(jsonPath("$.data.items[*].id", org.hamcrest.Matchers.containsInAnyOrder(
						first.toString(), last.toString(), spanning.toString())));
	}

	@ParameterizedTest
	@ValueSource(strings = {"2028-02-29", "2026-09-30", "2026-12-31"})
	@DisplayName("종료일 포함은 윤일·월말·연말에도 다음 날 경계를 정확히 계산한다")
	void inclusiveEndDateHandlesCalendarBoundaries(String date) throws Exception {
		// given: 종료일 KST의 23시와 다음 날 00시에 시작하는 이벤트를 구분한다.
		LocalDate selected = LocalDate.parse(date);
		String startsBeforeEnd = selected.atTime(14, 0).toInstant(ZoneOffset.UTC).toString();
		String exclusiveEnd = selected.atTime(15, 0).toInstant(ZoneOffset.UTC).toString();
		String laterEnd = selected.atTime(16, 0).toInstant(ZoneOffset.UTC).toString();
		UUID included = insertEvent(prefix + "-included", "OPEN", "TICKET", "vip", startsBeforeEnd, exclusiveEnd);
		insertEvent(prefix + "-excluded", "OPEN", "TICKET", "vip", exclusiveEnd, laterEnd);
		// when / then
		request(admin, get("/api/v1/admin/events").param("keyword", prefix)
				.param("from", date).param("to", date)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].id").value(included.toString()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"2099-02-29", "2099-02-30", "2099-1-1", "", "invalid",
			"2099-10-10T00:00:00", "2099-10-10T00:00:00Z", "2099-10-10T00:00:00+09:00",
			"2099-10-10T00:00:00.000000001Z", "+10000-10-01", "+999999999-10-01"})
	@DisplayName("검색은 YYYY-MM-DD의 실제 날짜만 허용하며 시각·나노초·확장 연도는 400으로 거절한다")
	void invalidDatesAndTimestampFiltersAreBadRequests(String value) throws Exception {
		// given / when / then
		for (String parameter : new String[]{"from", "to"}) {
			request(admin, get("/api/v1/admin/events").param(parameter, value))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		}
	}

	@Test
	@DisplayName("관리자 제목 검색의 %·_·!는 와일드카드가 아닌 실제 문자로 처리한다")
	void keywordWildcardsAreEscaped() throws Exception {
		// given
		insertEvent(prefix + " 100%_!", "OPEN", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertEvent(prefix + " 100xy!", "OPEN", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		// when / then
		request(admin, get("/api/v1/admin/events").param("keyword", "  " + prefix + " 100%_!  "))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	@DisplayName("재추첨 중 사용자는 발표 이력에 따른 공개 상태로 조회하고 관리자는 실제 상태로 조회한다")
	void redrawStateAndFiltersRespectPriorPublication() throws Exception {
		// given
		long publishedBefore = jdbc.queryForObject("select count(*) from events where deleted_at is null and status='PUBLISHED'", Long.class);
		long waitingBefore = jdbc.queryForObject("select count(*) from events where deleted_at is null and status='DRAW_CONFIRMED'", Long.class);
		UUID published = insertEvent(prefix + "-published", "REDRAWING", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		UUID waiting = insertEvent(prefix + "-waiting", "REDRAWING", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertPublication(published);
		// when / then
		request(user, "/api/v1/events/" + published).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("PUBLISHED"));
		request(user, "/api/v1/events/" + waiting).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("DRAW_CONFIRMED"));
		request(user, get("/api/v1/events").param("status", "PUBLISHED")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(published.toString()))
				.andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"))
				.andExpect(jsonPath("$.data.totalElements").value(publishedBefore + 1));
		request(user, get("/api/v1/events").param("status", "DRAW_CONFIRMED")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].id").value(waiting.toString()))
				.andExpect(jsonPath("$.data.totalElements").value(waitingBefore + 1));
		request(user, get("/api/v1/events").param("status", "REDRAWING")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isEmpty());
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("status", "REDRAWING"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.items[0].status").value("REDRAWING"));
		request(admin, "/api/v1/admin/events/" + published).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REDRAWING"));
		assertThat(jdbc.queryForObject("select count(*) from draw_publications where event_id=?", Integer.class, bytes(published))).isOne();
	}

	@ParameterizedTest
	@CsvSource({"SCHEDULED,1", "OPEN,1", "CLOSED,1", "DRAW_CONFIRMED,2", "PUBLISHED,2",
			"CANCELED,1", "REDRAWING,0", "NO_ENTRANTS,1"})
	@DisplayName("사용자 상태 검색의 목록과 건수는 실제 상태·재추첨 공개 상태를 함께 반영하고 삭제 행은 제외한다")
	void publicStatusFiltersIncludeDirectAndRedrawingStates(EventStatus filterStatus, int addedCount) {
		// given
		// 신규 계약의 상태만 생성한다. DB에 남은 과거 상태를 정상 조회 계약으로 간주하지 않는다.
		for (EventStatus state : List.of(EventStatus.SCHEDULED, EventStatus.OPEN, EventStatus.CLOSED,
				EventStatus.DRAW_CONFIRMED, EventStatus.PUBLISHED, EventStatus.CANCELED, EventStatus.NO_ENTRANTS)) {
			insertEvent(prefix + "-" + state, state.name(), "TICKET", "vip",
					"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		}
		UUID published = insertEvent(prefix + "-published-redraw", "REDRAWING", "TICKET", "vip",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		insertPublication(published);
		insertEvent(prefix + "-waiting-redraw", "REDRAWING", "TICKET", "vip",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		for (String state : new String[]{"PUBLISHED", "DRAW_CONFIRMED", "REDRAWING"}) {
			UUID deleted = insertEvent(prefix + "-deleted-" + state, state, "TICKET", "vip",
					"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
			if ("REDRAWING".equals(state)) {
				insertPublication(deleted);
			}
			jdbc.update("update events set deleted_at = ? where id = ?", at(CREATED_AT), bytes(deleted));
		}
		// when: 고유 제목 조건으로 현재 테스트의 행만 검증한다.
		PageResult<EventView> result = events.findAll(new EventQueryFilter(filterStatus, null, null, prefix, null, null),
				new PageQuery(1, 20), true);
		// then
		assertThat(result.getTotalElements()).isEqualTo(addedCount);
		assertThat(result.getItems()).hasSize(addedCount)
				.allSatisfy(event -> assertThat(event.getPublicStatus()).isEqualTo(filterStatus));
	}

	@Test
	@DisplayName("모든 조회는 사용자 문맥을 요구하고 미등록·비활성 사용자와 일반 사용자의 관리자 접근을 거절한다")
	void authorizationOnAllFourEndpoints() throws Exception {
		// given
		UUID event = insertEvent(prefix, "OPEN", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		UUID inactive = insertUser("USER", "vip", "INACTIVE");
		// when / then
		for (String path : new String[]{"/api/v1/events", "/api/v1/events/" + event,
				"/api/v1/admin/events", "/api/v1/admin/events/" + event}) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
			request(UUID.randomUUID(), path).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("USER-002"));
			request(inactive, path).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("EVENT-010"));
			mvc.perform(get(path).header("X-User-ID", user.toString()))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
			mvc.perform(get(path).header("X-User-ID", user.toString()).header("X-User-Role", "USER"))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
			mvc.perform(get(path).header("X-User-ID", user.toString()).header("X-User-Role", "ADMIN"))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
			mvc.perform(get(path).header("X-User-ID", "1-1-1-1-1")).andExpect(status().isBadRequest());
		}
		request(user, "/api/v1/admin/events").andExpect(status().isForbidden());
		request(user, "/api/v1/admin/events/" + event).andExpect(status().isForbidden());
		request(admin, "/api/v1/events/" + event).andExpect(status().isOk());
		request(admin, "/api/v1/admin/events").andExpect(status().isOk());
	}

	@Test
	@DisplayName("공통 사용자 문맥은 USER 멤버십을 DB와 대조하고 ADMIN에게 요구하지 않는다")
	void commonMembershipHeaderIsValidated() throws Exception {
		// given
		UUID event = insertEvent(prefix, "OPEN", "TICKET", "vip", "2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		// when / then
		for (String path : new String[]{"/api/v1/events", "/api/v1/events/" + event}) {
			request(user, get(path).header("X-User-Membership", "vvip")).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("USER-006"));
			request(user, get(path).header("X-User-Membership", "vip")).andExpect(status().isOk());
			request(user, get(path).header("X-User-Membership", "gold")).andExpect(status().isBadRequest());
			request(admin, get(path).header("X-User-Membership", "vip")).andExpect(status().isOk());
		}
	}

	@ParameterizedTest
	@CsvSource({"EXCELLENT,excellent", "VIP,vip", "VVIP,vvip"})
	@DisplayName("DB의 대문자 사용자 멤버십은 소문자 헤더와 대조해 정상 조회한다")
	void uppercaseStoredMembershipMatchesHeader(String storedMembership, String selectedMembership) throws Exception {
		// given
		UUID actor = insertUser("USER", storedMembership, "ACTIVE");
		UUID event = insertEvent(prefix, "SCHEDULED", "NO_TICKET", "excellent",
				"2099-10-05T00:00:00Z", "2099-10-15T00:00:00Z");
		assertThat(jdbc.queryForObject("select membership from users where id=?", String.class, bytes(actor)))
				.isEqualTo(storedMembership);
		// when / then
		request(actor, get("/api/v1/events").header("X-User-Membership", selectedMembership))
				.andExpect(status().isOk());
		request(actor, get("/api/v1/events/" + event).header("X-User-Membership", selectedMembership))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(event.toString()));
	}

	@Test
	@DisplayName("잘못된 페이지·필터·시각·UUID는 5xx가 아닌 400을 반환한다")
	void malformedQueriesAndIdsAreBadRequests() throws Exception {
		// given / when / then
		for (String path : new String[]{"/api/v1/events", "/api/v1/admin/events"}) {
			for (String[] param : new String[][]{{"page", "0"}, {"size", "0"}, {"size", "101"},
					{"page", "abc"}, {"status", "INVALID"}, {"status", "SUSPENDED"}, {"eventType", "INVALID"}}) {
				request(admin, get(path).param(param[0], param[1])).andExpect(status().isBadRequest());
			}
			request(admin, path + "/invalid").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		}
		request(admin, get("/api/v1/admin/events").param("from", "2099-10-20")
				.param("to", "2099-10-10")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT-008"));
		request(user, get("/api/v1/events").param("membershipRule", "gold")).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("관리자 정렬은 전체 결과에 적용하고 동률 ID 순서와 다음 페이지를 안정적으로 유지한다")
	void newSortOptionsApplyBeforePagination() throws Exception {
		// given: 등록일·제목과 모집 시작/마감 순서가 서로 다르며 B·C의 등록일은 같다.
		UUID a = UUID.fromString("22600000-0000-4000-8000-000000000001");
		UUID b = UUID.fromString("22600000-0000-4000-8000-000000000002");
		UUID c = UUID.fromString("22600000-0000-4000-8000-000000000003");
		insertEvent(a, prefix + "-a", "SCHEDULED", "NO_TICKET", "excellent", "2099-10-03T00:00:00Z", "2099-10-04T00:00:00Z");
		insertEvent(b, prefix + "-b", "SCHEDULED", "NO_TICKET", "excellent", "2099-10-01T00:00:00Z", "2099-10-02T00:00:00Z");
		insertEvent(c, prefix + "-c", "SCHEDULED", "NO_TICKET", "excellent", "2099-10-02T00:00:00Z", "2099-10-03T00:00:00Z");
		jdbc.update("update events set created_at=? where id=?", at("2099-09-01T00:00:00Z"), bytes(a));
		jdbc.update("update events set created_at=? where id in (?,?)", at("2099-09-02T00:00:00Z"), bytes(b), bytes(c));
		Map<String, List<UUID>> expected = Map.of(
				"createdAt,asc", List.of(a, b, c), "createdAt,desc", List.of(c, b, a),
				"startsAt,asc", List.of(b, c, a), "startsAt,desc", List.of(a, c, b),
				"endsAt,asc", List.of(b, c, a), "endsAt,desc", List.of(a, c, b),
				"title,asc", List.of(a, b, c), "title,desc", List.of(c, b, a));
		// when / then
		for (Map.Entry<String, List<UUID>> sort : expected.entrySet()) {
			request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("size", "2").param("sort", sort.getKey()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
					.andExpect(jsonPath("$.data.items[*].id", org.hamcrest.Matchers.contains(
							sort.getValue().getFirst().toString(), sort.getValue().get(1).toString())));
			request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("size", "2").param("page", "2")
					.param("sort", sort.getKey())).andExpect(status().isOk())
					.andExpect(jsonPath("$.data.items[0].id").value(sort.getValue().getLast().toString()));
		}
	}

	@Test
	@DisplayName("등록일 KST 양끝 포함·가중치 true/false·기존 모집 기간을 같은 count와 목록에 적용한다")
	void creationDatesAndWeightingCombineWithRecruitmentDates() throws Exception {
		// given
		UUID first = insertEvent(prefix + "-first", "OPEN", "TICKET", "vip", "2099-10-09T15:00:00Z", "2099-10-10T15:00:00Z");
		UUID last = insertEvent(prefix + "-last", "OPEN", "NO_TICKET", "vip", "2099-10-09T15:00:00Z", "2099-10-10T15:00:00Z");
		UUID after = insertEvent(prefix + "-after", "OPEN", "TICKET", "vip", "2099-10-09T15:00:00Z", "2099-10-10T15:00:00Z");
		UUID before = insertEvent(prefix + "-before", "OPEN", "TICKET", "vip", "2099-10-09T15:00:00Z", "2099-10-10T15:00:00Z");
		UUID outsidePeriod = insertEvent(prefix + "-outside", "OPEN", "TICKET", "vip", "2099-11-01T00:00:00Z", "2099-11-02T00:00:00Z");
		jdbc.update("update events set created_at=? where id=?", at("2099-09-30T15:00:00Z"), bytes(first));
		jdbc.update("update events set created_at=? where id=?", at("2099-10-01T14:59:59.999999Z"), bytes(last));
		jdbc.update("update events set created_at=? where id=?", at("2099-10-01T15:00:00Z"), bytes(after));
		jdbc.update("update events set created_at=? where id=?", at("2099-09-30T14:59:59.999999Z"), bytes(before));
		jdbc.update("update events set created_at=? where id=?", at("2099-10-01T00:00:00Z"), bytes(outsidePeriod));
		// when / then
		request(admin, get("/api/v1/admin/events").param("keyword", prefix)
				.param("createdFrom", "2099-10-01").param("createdTo", "2099-10-01")
				.param("from", "2099-10-10").param("to", "2099-10-10"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2));
		for (String weighting : new String[]{"true", "false"}) {
			request(admin, get("/api/v1/admin/events").param("keyword", prefix)
					.param("createdFrom", "2099-10-01").param("createdTo", "2099-10-01")
					.param("from", "2099-10-10").param("to", "2099-10-10").param("weightingEnabled", weighting))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
					.andExpect(jsonPath("$.data.items[0].id").value(("true".equals(weighting) ? first : last).toString()));
		}
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("createdFrom", "2099-10-02"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix).param("createdTo", "2099-09-30"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	@DisplayName("등록일·가중치 형식과 잘못된 정렬·역전 범위는 400을 반환한다")
	void malformedAdditionalFiltersAreBadRequests() throws Exception {
		// given / when / then
		for (String[] parameter : new String[][]{
				{"createdFrom", "2026-02-30"}, {"createdTo", "2026/10/17"}, {"createdFrom", ""},
				{"createdTo", "+10000-01-01"}, {"weightingEnabled", "invalid"},
				{"sort", "id,asc"}, {"sort", "createdAt,INVALID"}, {"sort", ""},
				{"createdFrom", "0999-01-01"}}) {
			request(admin, get("/api/v1/admin/events").param(parameter[0], parameter[1])).andExpect(status().isBadRequest());
		}
		request(admin, get("/api/v1/admin/events").param("createdFrom", "2099-10-17").param("createdTo", "2099-10-16"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EVENT-008"));
	}

	@Test
	@DisplayName("내 응모 여부는 응모권 미사용 정상 접수도 포함하고 타인·거절만 있는 이벤트·관리자를 구분한다")
	void userListReturnsParticipationWithoutDependingOnSpentTickets() throws Exception {
		// given
		UUID free = insertEvent(prefix + "-free", "OPEN", "NO_TICKET", "excellent", "2099-10-01T00:00:00Z", "2099-10-20T00:00:00Z");
		UUID paid = insertEvent(prefix + "-paid", "OPEN", "TICKET", "vip", "2099-10-01T00:00:00Z", "2099-10-20T00:00:00Z");
		UUID rejected = insertEvent(prefix + "-rejected", "OPEN", "TICKET", "vip", "2099-10-01T00:00:00Z", "2099-10-20T00:00:00Z");
		UUID untouched = insertEvent(prefix + "-untouched", "OPEN", "TICKET", "vip", "2099-10-01T00:00:00Z", "2099-10-20T00:00:00Z");
		UUID other = insertUser("USER", "vip", "ACTIVE");
		insertParticipation(free, user, 0);
		insertParticipation(paid, user, 3);
		insertParticipation(untouched, other, 1);
		jdbc.update("""
				insert into event_entries(id,event_id,user_id,status,rejection_code,rejection_reason,
				requested_ticket_count,deducted_ticket_count,created_at)
				values(?,?,?,'REJECTED','TICKET-006','응모권 부족',1,0,?)
				""", bytes(UUID.randomUUID()), bytes(rejected), bytes(user), at(CREATED_AT));
		// when / then: 현재 테스트의 네 이벤트를 사용자의 목록에서 골라 확인한다.
		String response = request(user, get("/api/v1/events").param("size", "100"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> items = JsonPath.read(response, "$.data.items");
		Map<String, Boolean> entered = new java.util.HashMap<>();
		items.forEach(item -> entered.put((String) item.get("id"), (Boolean) item.get("myEntered")));
		assertThat(entered).containsEntry(free.toString(), true).containsEntry(paid.toString(), true)
				.containsEntry(rejected.toString(), false).containsEntry(untouched.toString(), false);
		request(other, get("/api/v1/events").param("size", "100"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.id=='" + free + "')].myEntered", org.hamcrest.Matchers.contains(false)))
				.andExpect(jsonPath("$.data.items[?(@.id=='" + untouched + "')].myEntered", org.hamcrest.Matchers.contains(true)));
		request(admin, get("/api/v1/events").param("size", "100"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].myEntered", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
		request(admin, get("/api/v1/admin/events").param("keyword", prefix))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].myEntered").doesNotHaveJsonPath());
		request(user, "/api/v1/events/" + free)
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.myEntered").doesNotHaveJsonPath());
		assertThat(events.findEnteredEventIds(user, List.of())).isEmpty();
	}

	private void insertParticipation(UUID event, UUID actor, int spent) {
		UUID participant = UUID.randomUUID();
		jdbc.update("insert into event_participants(id,event_id,user_id,created_at,used_ticket_count) values(?,?,?,?,?)",
				bytes(participant), bytes(event), bytes(actor), at(CREATED_AT), spent);
		jdbc.update("""
				insert into event_entries(id,event_id,participant_id,user_id,status,requested_ticket_count,deducted_ticket_count,created_at)
				values(?,?,?,?,'ACCEPTED',?,?,?)
				""", bytes(UUID.randomUUID()), bytes(event), bytes(participant), bytes(actor), spent, spent, at(CREATED_AT));
	}

	private ResultActions request(UUID actor, String path) throws Exception {
		return request(actor, get(path));
	}

	private ResultActions request(UUID actor, MockHttpServletRequestBuilder builder) throws Exception {
		List<Map<String, Object>> contexts = jdbc.queryForList("select role,membership from users where id=?", bytes(actor));
		String role = contexts.isEmpty() ? "ADMIN" : (String) contexts.getFirst().get("role");
		String membership = contexts.isEmpty() ? null : (String) contexts.getFirst().get("membership");
		return mvc.perform(builder.header("X-User-ID", actor.toString()).header("X-User-Role", role)
				.with(request -> {
					if ("USER".equals(role) && request.getHeader("X-User-Membership") == null && membership != null) {
						request.addHeader("X-User-Membership", membership.toLowerCase(Locale.ROOT));
					}
					return request;
				}));
	}

	private UUID insertUser(String role, String membership, String state) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into users(id,name,role,status,membership,created_at,updated_at) values(?,?,?,?,?,?,?)
				""", bytes(id), "조회 테스트", role, state, membership, at(CREATED_AT), at(CREATED_AT));
		return id;
	}

	private UUID insertEvent(String title, String state, String type, String membership, String from, String to) {
		UUID id = UUID.randomUUID();
		insertEvent(id, title, state, type, membership, from, to);
		return id;
	}

	private void insertEvent(UUID id, String title, String state, String type, String membership, String from, String to) {
		jdbc.update("""
				insert into events(id,title,description,image_key,event_type,weighting_enabled,
				max_tickets_per_user,membership_rule,starts_at,ends_at,status,created_at,updated_at)
				values(?,?,'상세 설명','events/image.png',?,?,?,?,?,?,?,?,?)
				""", bytes(id), title, type, "TICKET".equals(type), "TICKET".equals(type) ? 5 : null,
				membership, at(from), at(to), state, at(CREATED_AT), at(CREATED_AT));
	}

	private UUID insertPrize(UUID event, int rank, int winners) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into event_prizes(id,event_id,prize_rank,name,description,image_key,winner_count,created_at,updated_at)
				values(?,?,?,'경품','경품 설명','prizes/image.png',?,?,?)
				""", bytes(id), bytes(event), rank, winners, at(CREATED_AT), at(CREATED_AT));
		return id;
	}

	private void insertPublication(UUID event) {
		UUID run = UUID.randomUUID();
		jdbc.update("""
				insert into draw_runs(id,event_id,run_number,draw_type,status,algorithm_version,
				                     rules_snapshot,snapshot_fixed_at,confirmed_at,created_at)
				values(?,?,0,'INITIAL','CONFIRMED','query-test-v1','{}',?,?,?)
				""", bytes(run), bytes(event), at(CREATED_AT), at(CREATED_AT), at(CREATED_AT));
		jdbc.update("""
				insert into draw_publications(id,event_id,source_draw_id,revision,publication_type,published_at)
				values(?,?,?,1,'INITIAL',?)
				""", bytes(UUID.randomUUID()), bytes(event), bytes(run), at(CREATED_AT));
	}

	private static LocalDateTime at(String value) {
		// DATETIME 시드에 JVM 기본 시간대가 적용되지 않도록 UTC의 날짜·시간을 직접 전달한다.
		return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
	}

	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}
}
