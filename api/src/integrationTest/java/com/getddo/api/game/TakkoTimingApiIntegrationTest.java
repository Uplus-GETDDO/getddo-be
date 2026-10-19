package com.getddo.api.game;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

/** 실제 MySQL에서 타이밍 G02~G05와 재시도·통계·일일 지급을 연결한다. */
@ApiIntegrationTest
@TestPropertySource(properties = "getddo.drawing.retry-worker.enabled=false")
class TakkoTimingApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
	private static final String RULES = "{\"points\":10,\"perfectPoints\":20,\"minimumRewardScore\":50}";
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
				values(?,'타이밍 사용자','USER','ACTIVE',?,?)
				""", bytes(user), at, at);
		jdbc.update("""
				insert into games(id,code,name,rules,rule_version,is_active,created_at,updated_at)
				values(?,'TAKKO_TIMING','타이밍',?,'takko-timing-v1',true,?,?)
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

	@Test
	@DisplayName("타이밍 상세·시작은 점수 규칙 세 개와 seed·생성 버전을 제공하고 응답 seed와 DB seed가 같다")
	void readsAndStartsTiming() throws Exception {
		// given / when / then
		mvc.perform(get("/api/v1/games/{gameId}", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("TAKKO_TIMING"))
				.andExpect(jsonPath("$.data.rules.points").value(10))
				.andExpect(jsonPath("$.data.rules.perfectPoints").value(20))
				.andExpect(jsonPath("$.data.rules.minimumRewardScore").value(50));
		JsonNode started = data(mvc.perform(post("/api/v1/games/{gameId}/plays", game)
				.header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.gameData.generatorVersion").value("takko-timing-generator-v1"))
				.andExpect(jsonPath("$.data.gameData.seed").isNumber()).andExpect(jsonPath("$.data.gameData.tickMs").doesNotExist()));

		Long storedSeed = jdbc.queryForObject("select generation_seed from game_plays where id=?", Long.class,
				bytes(UUID.fromString(started.path("playId").asString())));
		assertThat(storedSeed).isNotNull().isEqualTo(started.path("gameData").path("seed").longValue());
	}

	@Test
	@DisplayName("0·40·50·60점은 통계에 반영하고 재시도·변경 충돌·하루 한 장 지급을 유지한다")
	void preservesStatisticsRewardAndRetry() throws Exception {
		// given
		JsonNode zero = start();
		JsonNode low = start();
		JsonNode enough = start();
		JsonNode later = start();
		// when / then
		submit(zero, taps(0)).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(0))
				.andExpect(jsonPath("$.data.reward").isEmpty());
		submit(low, taps(304, 1240, 0)).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(40))
				.andExpect(jsonPath("$.data.reward").isEmpty());
		JsonNode original = data(submit(enough, taps(304, 1240, 954, 0)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.score").value(50)).andExpect(jsonPath("$.data.reward.ticketCount").value(1)));
		assertThat(data(submit(enough, taps(304, 1240, 954, 0)).andExpect(status().isOk()))).isEqualTo(original);
		submit(enough, taps(304, 1240, 955, 0)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME-006"));
		submit(later, taps(304, 1240, 850, 0)).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(60))
				.andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(jdbc.queryForObject("select count(*) from tickets where user_id=?", Long.class, bytes(user))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from game_reward_claims where game_id=?", Long.class, bytes(game))).isEqualTo(1);
		mvc.perform(get("/api/v1/games/{gameId}/statistics/me", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.bestScore").value(60))
				.andExpect(jsonPath("$.data.totalScore").value(150)).andExpect(jsonPath("$.data.validPlayCount").value(4))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(true));
	}

	@Test
	@DisplayName("미완료 입력은 INVALID이며 색깔 입력을 타이밍에 제출한 형식 오류는 판을 확정하지 않는다")
	void invalidDoesNotRewardOrCount() throws Exception {
		// given
		JsonNode play = start();
		String wrong = "{\"playToken\":\"" + play.path("playToken").asString() + "\",\"validationData\":{\"answers\":[]}}";
		// when / then
		mvc.perform(put("/api/v1/games/{gameId}/plays/{playId}/result", game, play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(wrong))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		assertThat(jdbc.queryForObject("select completed_at is null from game_plays where id=?", Boolean.class,
				bytes(UUID.fromString(play.path("playId").asString())))).isTrue();
		submit(play, taps(304)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVALID"))
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

	private ResultActions submit(JsonNode play, String taps) throws Exception {
		String body = "{\"playToken\":\"" + play.path("playToken").asString()
				+ "\",\"validationData\":{\"taps\":" + taps + "}}";
		return mvc.perform(put("/api/v1/games/{gameId}/plays/{playId}/result", game, play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String taps(long... times) {
		List<String> records = new ArrayList<>();
		for (long time : times) records.add("{\"sequence\":" + (records.size() + 1) + ",\"elapsedMs\":" + time + "}");
		return "[" + String.join(",", records) + "]";
	}

	private JsonNode data(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
	}

	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}
}
