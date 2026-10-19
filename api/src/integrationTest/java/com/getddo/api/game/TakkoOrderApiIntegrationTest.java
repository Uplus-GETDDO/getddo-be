package com.getddo.api.game;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.db.common.util.UuidBinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MySQL에서 주문 G02~G05와 재시도·통계·일일 지급을 연결한다. */
@ApiIntegrationTest
@TestPropertySource(properties = "getddo.drawing.retry-worker.enabled=false")
class TakkoOrderApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
	private static final String RULES = "{\"initialLives\":3,\"startLength\":3,\"maxLength\":8}";
	private final JsonMapper json = JsonMapper.builder().build();
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private Clock clock;
	private UUID user;
	private UUID game;

	@BeforeEach
	void seed() {
		doReturn(NOW).when(clock).instant();
		doReturn(ZoneOffset.UTC).when(clock).getZone();
		user = UUID.randomUUID();
		game = UUID.randomUUID();
		LocalDateTime at = LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC);
		jdbc.update("""
				insert into users(id,name,role,status,created_at,updated_at)
				values(?,'주문 사용자','USER','ACTIVE',?,?)
				""", bytes(user), at, at);
		jdbc.update("""
				insert into games(id,code,name,rules,rule_version,is_active,created_at,updated_at)
				values(?,'TAKKO_ORDER','주문',?,'takko-order-v1',true,?,?)
				""", bytes(game), RULES, at, at);
		jdbc.update("""
				insert into reward_policies(id,created_by,reward_type,game_id,reward_ticket_count,effective_from,created_at)
				values(?,?,'GAME',?,1,?,?)
				""", bytes(UUID.randomUUID()), bytes(user), bytes(game), at, at);
	}

	@AfterEach
	void clean() {
		jdbc.update("delete h from ticket_histories h join tickets t on t.id=h.ticket_id where t.user_id=?", bytes(user));
		jdbc.update("delete from tickets where user_id=?", bytes(user));
		jdbc.update("delete from game_reward_claims where game_id=?", bytes(game));
		jdbc.update("delete from user_game_stats where game_id=?", bytes(game));
		jdbc.update("delete from game_plays where game_id=?", bytes(game));
		jdbc.update("delete from reward_policies where game_id=?", bytes(game));
		jdbc.update("delete from games where id=?", bytes(game));
		jdbc.update("delete from users where id=?", bytes(user));
	}

	private static final String ENOUGH = """
			[{"sequence":1,"picks":["scallion","scallion","shrimp"]},
			 {"sequence":2,"picks":["scallion","shrimp","scallion","scallion"]},
			 {"sequence":3,"picks":["scallion","cheese","shrimp","shrimp","cheese"]},
			 {"sequence":4,"picks":["shrimp","scallion","scallion","nori","scallion","nori"]},
			 {"sequence":5,"picks":["cheese"]},{"sequence":6,"picks":["scallion"]},
			 {"sequence":7,"picks":["cheese"]}]
			""";

	@Test
	@DisplayName("주문 상세·시작은 목숨·길이 규칙과 seed·생성 버전을 제공한다")
	void readsAndStartsOrder() throws Exception {
		// given / when / then
		mvc.perform(get("/api/v1/games/{gameId}", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("TAKKO_ORDER"))
				.andExpect(jsonPath("$.data.rules.initialLives").value(3))
				.andExpect(jsonPath("$.data.rules.startLength").value(3))
				.andExpect(jsonPath("$.data.rules.maxLength").value(8));
		mvc.perform(post("/api/v1/games/{gameId}/plays", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.gameData.generatorVersion").value("takko-order-generator-v1"))
				.andExpect(jsonPath("$.data.gameData.seed").isNumber()).andExpect(jsonPath("$.data.gameData.tickMs").doesNotExist());
	}

	@Test
	@DisplayName("18점은 하루1장 지급하며 동일 결과 재시도·변경409·후속 플레이 중복 보상을 막는다")
	void preservesStatisticsRewardAndRetry() throws Exception {
		// given
		JsonNode enough = start();
		JsonNode later = start();
		// when / then
		JsonNode original = data(submit(enough, ENOUGH).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.score").value(18)).andExpect(jsonPath("$.data.reward.ticketCount").value(1)));
		assertThat(data(submit(enough, ENOUGH).andExpect(status().isOk()))).isEqualTo(original);
		submit(enough, ENOUGH.replace("\"sequence\":7", "\"sequence\":8")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("GAME-006"));
		submit(later, ENOUGH).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(18))
				.andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(jdbc.queryForObject("select count(*) from tickets where user_id=?", Long.class, bytes(user))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from game_reward_claims where game_id=?", Long.class, bytes(game))).isEqualTo(1);
		mvc.perform(get("/api/v1/games/{gameId}/statistics/me", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.bestScore").value(18))
				.andExpect(jsonPath("$.data.totalScore").value(36)).andExpect(jsonPath("$.data.validPlayCount").value(2))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(true));
	}

	@Test
	@DisplayName("주문 플레이에 타이밍 입력을 보내면400이고 미완성 주문은 INVALID로 확정해 통계·보상에서 제외한다")
	void rejectsWrongShapeAndIncompleteOrder() throws Exception {
		// given
		JsonNode play = start();
		String wrong = "{\"playToken\":\"" + play.path("playToken").asString() + "\",\"validationData\":{\"taps\":[]}}";
		// when / then
		mvc.perform(put("/api/v1/games/{gameId}/plays/{playId}/result", game, play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(wrong))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		assertThat(jdbc.queryForObject("select completed_at is null from game_plays where id=?", Boolean.class,
				bytes(UUID.fromString(play.path("playId").asString())))).isTrue();
		submit(play, "[{\"sequence\":1,\"picks\":[\"scallion\"]}]")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVALID"))
				.andExpect(jsonPath("$.data.score").isEmpty()).andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(jdbc.queryForObject("select count(*) from user_game_stats where user_id=?", Long.class, bytes(user))).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tickets where user_id=?", Long.class, bytes(user))).isZero();
	}

	/** 고정 예제는 테스트가 만든 행에만 seed1을 지정한다. 응답 seed를 쓰지 않는 고정 입력 검증에만 사용한다. */
	private JsonNode start() throws Exception {
		JsonNode play = data(mvc.perform(post("/api/v1/games/{gameId}/plays", game)
				.header("X-User-ID", user).header("X-User-Role", "USER")).andExpect(status().isCreated()));
		jdbc.update("update game_plays set generation_seed=1 where id=?",
				bytes(UUID.fromString(play.path("playId").asString())));
		return play;
	}

	private ResultActions submit(JsonNode play, String rounds) throws Exception {
		String body = "{\"playToken\":\"" + play.path("playToken").asString()
				+ "\",\"validationData\":{\"rounds\":" + rounds + "}}";
		return mvc.perform(put("/api/v1/games/{gameId}/plays/{playId}/result", game, play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private JsonNode data(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
	}

	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}
}
