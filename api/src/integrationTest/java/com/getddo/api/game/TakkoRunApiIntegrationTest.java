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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.db.common.util.UuidBinary;
import com.getddo.core.game.domain.TakkoRunAction;
import com.getddo.core.game.domain.TakkoRunReplay;
import com.getddo.core.game.domain.TakkoRunRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MySQL의 시작 스냅샷·공통 G04·일일 청구·응모권 지급 연결을 확인하는 테스트다. */
@ApiIntegrationTest
class TakkoRunApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
	private static final String RULES = "{\"minimumRewardScore\":400,\"maxTicks\":72000}";
	private final JsonMapper json = JsonMapper.builder().build();
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private Clock clock;
	private UUID user;
	private UUID game;

	@BeforeEach
	void seed() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		user = UUID.randomUUID();
		game = UUID.randomUUID();
		LocalDateTime at = LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC);
		jdbc.update("""
				insert into users(id,name,role,status,created_at,updated_at)
				values(?,'타꼬런 사용자','USER','ACTIVE',?,?)
				""", bytes(user), at, at);
		jdbc.update("""
				insert into games(id,code,name,rules,rule_version,is_active,created_at,updated_at)
				values(?,'TAKKO_RUN','타꼬런',?,'takko-run-v1',true,?,?)
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
	@DisplayName("기존 G02·G03가 타꼬런 공개 규칙과 시작 seed·토큰을 제공한다")
	void startsAndReadsTakkoRun() throws Exception {
		// given / when / then
		mvc.perform(get("/api/v1/games/{gameId}", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("TAKKO_RUN"))
				.andExpect(jsonPath("$.data.rules.minimumRewardScore").value(400))
				.andExpect(jsonPath("$.data.rules.maxTicks").value(72000));
		JsonNode play = start(1);
		assertThat(play.path("gameData").path("generatorVersion").asString()).isEqualTo("takko-run-generator-v1");
		assertThat(play.path("rules").path("minimumRewardScore").intValue()).isEqualTo(400);
		assertThat(play.path("gameData").path("tickMs").doubleValue()).isEqualTo(1000.0 / 120);
		assertThat(play.path("playToken").asString()).hasSize(43);
	}

	@Test
	@DisplayName("타꼬런 저득점은 통계만 반영하고 기준 이상은 하루 한 장이며 재제출은 최초 결과다")
	void recordsAndRewardsOnce() throws Exception {
		// given
		JsonNode low = start(1);
		String lowBody = body(low, 348, "[{\"tick\":0,\"type\":\"press\"}]");
		RewardFixture fixture = rewardFixture();
		// when / then
		submit(low, lowBody).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(122))
				.andExpect(jsonPath("$.data.reward").isEmpty());
		JsonNode enough = start(fixture.seed);
		String enoughBody = body(enough, fixture.endTick, fixture.inputs);
		JsonNode original = data(submit(enough, enoughBody).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.score").value(fixture.score))
				.andExpect(jsonPath("$.data.reward.ticketCount").value(1)));
		assertThat(data(submit(enough, enoughBody).andExpect(status().isOk()))).isEqualTo(original);
		submit(enough, body(enough, fixture.endTick - 1, fixture.inputs)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("GAME-006"));
		JsonNode later = start(fixture.seed);
		submit(later, body(later, fixture.endTick, fixture.inputs)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(jdbc.queryForObject("select count(*) from tickets where user_id=?", Long.class, bytes(user)))
				.isEqualTo(1);
		mvc.perform(get("/api/v1/games/{gameId}/statistics/me", game)
				.header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.bestScore").value(fixture.score))
				.andExpect(jsonPath("$.data.totalScore").value(122 + fixture.score * 2))
				.andExpect(jsonPath("$.data.validPlayCount").value(3))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(true));
	}

	@Test
	@DisplayName("게임별 입력 형식 불일치는400이며 총 tick 상한 초과는 재생 없이 INVALID다")
	void separatesFormatAndInvalid() throws Exception {
		// given
		JsonNode play = start(1);
		String color = "{\"playToken\":\"" + play.path("playToken").asString()
				+ "\",\"validationData\":{\"answers\":[]}}";
		// when / then
		submit(play, color).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		assertThat(jdbc.queryForObject("select completed_at is null from game_plays where id=?", Boolean.class,
				bytes(UUID.fromString(play.path("playId").asString())))).isTrue();
		submit(play, body(play, Integer.MAX_VALUE, "[{\"tick\":0,\"type\":\"press\"}]"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVALID"))
				.andExpect(jsonPath("$.data.score").isEmpty()).andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(jdbc.queryForObject("select count(*) from user_game_stats where user_id=?", Long.class, bytes(user)))
				.isZero();
	}

	@Test
	@DisplayName("관리자도 타꼬런 시작은 가능하지만 결과 제출은 기존403으로 거절한다")
	void preservesAdministratorBoundary() throws Exception {
		// given
		jdbc.update("update users set role='ADMIN' where id=?", bytes(user));
		// when
		JsonNode play = data(mvc.perform(post("/api/v1/games/{gameId}/plays", game)
				.header("X-User-ID", user).header("X-User-Role", "ADMIN")).andExpect(status().isCreated()));
		// then
		mvc.perform(put("/api/v1/games/{gameId}/plays/{playId}/result", game, play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
				.content(body(play, 348, "[{\"tick\":0,\"type\":\"press\"}]")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GAME-007"));
	}

	/** 통합 입력을 쓰기 위해 발급된 테스트 행의 seed를 고정한다. 운영 데이터에는 실행하지 않는다. */
	private JsonNode start(long seed) throws Exception {
		JsonNode play = data(mvc.perform(post("/api/v1/games/{gameId}/plays", game)
				.header("X-User-ID", user).header("X-User-Role", "USER")).andExpect(status().isCreated()));
		jdbc.update("update game_plays set generation_seed=? where id=?", seed,
				bytes(UUID.fromString(play.path("playId").asString())));
		return play;
	}

	private ResultActions submit(JsonNode play, String body) throws Exception {
		return mvc.perform(put("/api/v1/games/{gameId}/plays/{playId}/result", game, play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static String body(JsonNode play, int endTick, String inputs) {
		return "{\"playToken\":\"" + play.path("playToken").asString()
				+ "\",\"validationData\":{\"endTick\":" + endTick + ",\"inputs\":" + inputs + "}}";
	}

	/**
	 * 트랜잭션·일일 보상 검사용 유효 입력을 테스트 실행 시 준비한다.
	 * 85tick 주기로 점프하는 seed를 제한된 범위에서 찾는다. 이 helper는 FE/Java 교차 검증 근거가 아니다.
	 */
	private static RewardFixture rewardFixture() {
		for (long seed = 1; seed <= 128; seed++) {
			TakkoRunReplay replay = new TakkoRunReplay(seed);
			List<String> inputs = new ArrayList<>();
			for (int tick = 0; tick < TakkoRunRules.MAX_TICKS; tick++) {
				if (tick % 85 == 0) {
					replay.apply(TakkoRunAction.PRESS);
					inputs.add("{\"tick\":" + tick + ",\"type\":\"press\"}");
				}
				boolean collision = replay.step();
				if (collision || tick == TakkoRunRules.MAX_TICKS - 1) {
					if (replay.getScore() >= TakkoRunRules.MINIMUM_REWARD_SCORE) {
						return new RewardFixture(seed, tick, replay.getScore(), "[" + String.join(",", inputs) + "]");
					}
					break;
				}
			}
		}
		throw new AssertionError("보상 검사용 유효 입력을 seed1~128에서 찾지 못했습니다.");
	}

	private static final class RewardFixture {
		private final long seed;
		private final int endTick;
		private final long score;
		private final String inputs;

		private RewardFixture(long seed, int endTick, long score, String inputs) {
			this.seed = seed;
			this.endTick = endTick;
			this.score = score;
			this.inputs = inputs;
		}
	}

	private JsonNode data(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
	}

	private static byte[] bytes(UUID id) {
		return UuidBinary.toBytes(id);
	}
}
