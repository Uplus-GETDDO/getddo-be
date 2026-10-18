package com.getddo.api.game;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.api.support.ApiIntegrationTest;
import com.getddo.core.game.domain.ColorWordRoundGenerator;
import com.getddo.core.ticket.service.TicketGrantService;
import com.getddo.db.common.util.UuidBinary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 시작→종료→통계→청구→지급과 재시도・롤백・잠금 경합을 MySQL에서 확인한다. */
@ApiIntegrationTest
@TestPropertySource(properties = {
		"getddo.rate-limit.enabled=true",
		// 게임 테스트의 시계 설정과 추첨 재시도 폴링이 경쟁하지 않게 한다.
		"getddo.drawing.retry-worker.enabled=false"
})
class GameResultApiIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-15T14:59:59Z");
	private final JsonMapper json = JsonMapper.builder().build();
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private Clock clock;
	@MockitoSpyBean private TicketGrantService grants;
	private UUID user;
	private UUID other;
	private UUID game;

	@BeforeEach
	void seed() {
		doReturn(NOW).when(clock).instant();
		doReturn(ZoneOffset.UTC).when(clock).getZone();
		user = UUID.randomUUID(); other = UUID.randomUUID(); game = UUID.randomUUID();
		LocalDateTime at = utc(NOW.minusSeconds(3600));
		for (UUID id : List.of(user, other)) jdbc.update("""
			insert into users(id,name,role,status,created_at,updated_at) values(?,'게임 사용자','USER','ACTIVE',?,?)
			""", bytes(id), at, at);
		jdbc.update("""
			insert into games(id,code,name,rules,rule_version,is_active,created_at,updated_at)
			values(?,'COLOR_WORD','색깔 게임',?, 'color-word-v1',true,?,?)
			""", bytes(game), "{\"initialLives\":3,\"pointsPerCorrect\":10,\"initialTimeLimitMs\":3000,\"timeLimitDecreaseMs\":80,\"minimumTimeLimitMs\":1200}", at, at);
		jdbc.update("""
			insert into reward_policies(id,created_by,reward_type,game_id,reward_ticket_count,effective_from,created_at)
			values(?,?,'GAME',?,1,?,?)
			""", bytes(UUID.randomUUID()), bytes(user), bytes(game), at, at);
	}

	@AfterEach
	void clean() {
		jdbc.update("delete h from ticket_histories h join tickets t on t.id=h.ticket_id where t.user_id in (?,?)", bytes(user), bytes(other));
		jdbc.update("delete from tickets where user_id in (?,?)", bytes(user), bytes(other));
		jdbc.update("delete from game_reward_claims where game_id=?", bytes(game));
		jdbc.update("delete from user_game_stats where game_id=?", bytes(game));
		jdbc.update("delete from game_plays where game_id=?", bytes(game));
		jdbc.update("delete from reward_policies where game_id=?", bytes(game));
		jdbc.update("delete from games where id=?", bytes(game));
		jdbc.update("delete from users where id in (?,?)", bytes(user), bytes(other));
	}

	@Test
	@DisplayName("0점·40점은 유효 통계만 반영하고 50점에서 하루 한 장 지급하며 재시도 응답은 최초 값이다")
	void scoresRewardsAndReplay() throws Exception {
		// given
		JsonNode zero = start(); JsonNode low = start(); JsonNode enough = start(); JsonNode later = start();
		// when / then
		submit(user, zero, body(zero, 0)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("VALID"))
				.andExpect(jsonPath("$.data.score").value(0)).andExpect(jsonPath("$.data.reward").isEmpty());
		String lowBody = body(low, 4);
		submit(user, low, lowBody).andExpect(status().isOk()).andExpect(jsonPath("$.data.score").value(40));
		String enoughBody = body(enough, 5);
		JsonNode first = result(submit(user, enough, enoughBody).andExpect(status().isOk()));
		assertThat(first.path("reward").path("ticketCount").intValue()).isEqualTo(1);
		assertThat(first.path("reward").path("grade").asString()).isIn("GOLD", "SILVER", "BRONZE");
		submit(user, later, body(later, 6)).andExpect(status().isOk()).andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(result(submit(user, enough, enoughBody).andExpect(status().isOk()))).isEqualTo(first);
		submit(user, low, lowBody).andExpect(status().isOk()).andExpect(jsonPath("$.data.reward").isEmpty());
		assertThat(count("tickets")).isEqualTo(1);
		assertThat(count("game_reward_claims")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select total_score from user_game_stats where user_id=? and game_id=?", Long.class, bytes(user), bytes(game)))
				.isEqualTo(150L);
		assertThat(jdbc.queryForObject("select valid_play_count from user_game_stats where user_id=? and game_id=?", Long.class, bytes(user), bytes(game)))
				.isEqualTo(4L);
		submit(user, enough, body(enough, 4)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME-006"));
	}

	@Test
	@DisplayName("자정 이후 재시도는 최초 보상을 유지하고 새 플레이는 새로운 KST 보상일에 지급한다")
	void kstMidnightPreservesOriginalReward() throws Exception {
		// given
		JsonNode first = start(); String body = body(first, 5);
		JsonNode original = result(submit(user, first, body).andExpect(status().isOk()));
		// when
		doReturn(NOW.plusSeconds(2)).when(clock).instant();
		assertThat(result(submit(user, first, body).andExpect(status().isOk()))).isEqualTo(original);
		JsonNode second = start();
		submit(user, second, body(second, 5)).andExpect(status().isOk()).andExpect(jsonPath("$.data.reward.ticketCount").value(1));
		// then
		assertThat(count("tickets")).isEqualTo(2);
		assertThat(jdbc.queryForList("select reward_date from game_reward_claims where game_id=? order by reward_date", java.sql.Date.class, bytes(game)))
				.extracting(java.sql.Date::toString).containsExactly("2026-10-15", "2026-10-16");
	}

	@Test
	@DisplayName("타인·없는 플레이·게임 불일치·잘못된 토큰은 같은 404이며 형식 오류는 400, 미종료 기록은 INVALID다")
	void ownershipFormatAndInvalidAreDistinct() throws Exception {
		// given
		JsonNode play = start(); String body = body(play, 0);
		// when / then
		submit(other, play, body).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME-005"));
		submit(user, play, body.replace(play.path("playToken").asString(), "x".repeat(43)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME-005"));
		mvc.perform(put("/api/v1/games/{game}/plays/{play}/result", UUID.randomUUID(), play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME-005"));
		mvc.perform(put("/api/v1/games/{game}/plays/{play}/result", game, UUID.randomUUID())
				.header("X-User-ID", user).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME-005"));
		submit(user, play, "{\"playToken\":\"x\",\"validationData\":{\"answers\":[{\"sequence\":1,\"elapsedMs\":3000}]}}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		submit(user, play, "{\"playToken\":\"" + play.path("playToken").asString() + "\",\"validationData\":{\"answers\":[]}}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVALID"));
		assertThat(count("user_game_stats")).isZero();
		assertThat(count("tickets")).isZero();
	}

	@Test
	@DisplayName("지급 도중 실패하면 완료·통계·청구·응모권을 전부 롤백하고 같은 요청으로 재시도할 수 있다")
	void grantFailureRollsBackEntireResult() throws Exception {
		// given
		JsonNode play = start(); String body = body(play, 5);
		TicketGrantService target = AopTestUtils.getUltimateTargetObject(grants);
		doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("의도한 지급 실패"); }).when(target).grant(any());
		// when / then
		submit(user, play, body).andExpect(status().isInternalServerError());
		assertThat(count("tickets")).isZero(); assertThat(count("game_reward_claims")).isZero();
		assertThat(count("user_game_stats")).isZero();
		assertThat(jdbc.queryForObject("select completed_at is null from game_plays where id=?", Boolean.class, bytes(UUID.fromString(play.path("playId").asString())))).isTrue();
		doCallRealMethod().when(target).grant(any());
		submit(user, play, body).andExpect(status().isOk()).andExpect(jsonPath("$.data.reward.ticketCount").value(1));
	}

	@Test
	@DisplayName("서로 다른 두 플레이가 동시에 기준을 충족해도 통계는 두 번, 일일 지급은 한 번이다")
	void concurrentPlaysGrantOnce() throws Exception {
		// given
		JsonNode first = start(); JsonNode second = start();
		CountDownLatch begin = new CountDownLatch(1);
		// when
		try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (JsonNode play : List.of(first, second)) results.add(executor.submit(() -> {
				if (!begin.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 시간 초과");
				return submit(user, play, body(play, 5)).andReturn().getResponse().getStatus();
			}));
			begin.countDown();
			for (Future<Integer> response : results) assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(200);
		}
		// then
		assertThat(count("tickets")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select total_score from user_game_stats where user_id=? and game_id=?", Long.class, bytes(user), bytes(game)))
				.isEqualTo(100L);
	}

	@Test
	@DisplayName("실제 플레이 잠금 경합만 202를 반환하고 큰 요청·기록은 413으로 저장 전에 거절한다")
	void handlesBusyPlayAndBounds() throws Exception {
		// given
		JsonNode play = start(); String body = body(play, 5);
		CountDownLatch locked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
		try (java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> held = executor.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
				jdbc.queryForList("select id from game_plays where id=? for update", bytes(UUID.fromString(play.path("playId").asString())));
				locked.countDown();
				try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("해제 대기 시간 초과"); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
				return null;
			}));
			try {
				assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
				submit(user, play, body).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PROCESSING"))
						.andExpect(jsonPath("$.data.score").isEmpty()).andExpect(jsonPath("$.data.reward").isEmpty());
			} finally { release.countDown(); }
			held.get(30, TimeUnit.SECONDS);
		}
		// when / then
		submit(user, play, body).andExpect(status().isOk());
		submit(user, play, " ".repeat(1_048_577)).andExpect(status().isPayloadTooLarge());
		String answer = "{\"sequence\":1,\"answer\":null,\"elapsedMs\":3000}";
		String large = "{\"playToken\":\"x\",\"validationData\":{\"answers\":[" + String.join(",", java.util.Collections.nCopies(10_001, answer)) + "]}}";
		submit(user, play, large).andExpect(status().isPayloadTooLarge());
	}

	@Test
	@DisplayName("내 통계는 비활성 게임에서도 조회하며 타인 통계·당일 청구를 섞지 않고 다음 KST 자정을 반환한다")
	void readsOwnStatisticsAndDailyReset() throws Exception {
		// given / when / then: 없는 통계 조회로 행을 생성하지 않는다.
		statistics(user, game).andExpect(status().isOk()).andExpect(jsonPath("$.data.validPlayCount").value(0))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(false));
		assertThat(count("user_game_stats")).isZero();
		JsonNode play = start();
		submit(user, play, body(play, 5)).andExpect(status().isOk());
		jdbc.update("update games set is_active=false where id=?", bytes(game));
		statistics(user, game).andExpect(status().isOk()).andExpect(jsonPath("$.data.bestScore").value(50))
				.andExpect(jsonPath("$.data.totalScore").value(50)).andExpect(jsonPath("$.data.validPlayCount").value(1))
				.andExpect(jsonPath("$.data.rewardDate").value("2026-10-15"))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(true))
				.andExpect(jsonPath("$.data.nextResetAt").value("2026-10-15T15:00:00Z"));
		statistics(other, game).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalScore").value(0))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(false));
		doReturn(NOW.plusSeconds(2)).when(clock).instant();
		statistics(user, game).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalScore").value(50))
				.andExpect(jsonPath("$.data.rewardDate").value("2026-10-16"))
				.andExpect(jsonPath("$.data.rewardClaimedToday").value(false));
		statistics(user, UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME-002"));
		jdbc.update("update users set status='INACTIVE' where id=?", bytes(user));
		statistics(user, game).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GAME-007"));
		jdbc.update("update users set status='ACTIVE',role='ADMIN' where id=?", bytes(user));
		mvc.perform(get("/api/v1/games/{gameId}/statistics/me", game).header("X-User-ID", user).header("X-User-Role", "ADMIN"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GAME-007"));
	}

	@Test
	@DisplayName("결과 제출은 사용자별 1분 10회이며 초과 거절·시간 경과 후 재시도에도 통계와 보상을 중복 반영하지 않는다")
	void limitsResultSubmissionsWithoutRepeatingEffects() throws Exception {
		// given: 최초 완료와 같은 결과 재조회도 결과 제출 요청 한도에 포함한다.
		JsonNode play = start();
		String submitted = body(play, 5);
		JsonNode original = result(submit(user, play, submitted).andExpect(status().isOk()));
		for (int i = 1; i < 10; i++) {
			assertThat(result(submit(user, play, submitted).andExpect(status().isOk()))).isEqualTo(original);
		}
		// when / then: 같은 사용자의 다른 게임 경로도 한도를 공유하며 조회·타인은 영향받지 않는다.
		submit(user, play, submitted).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("COMMON-006"))
				.andExpect(jsonPath("$.data").isEmpty()).andExpect(header().string("Retry-After", "60"));
		mvc.perform(put("/api/v1/games/{game}/plays/{play}/result", UUID.randomUUID(), play.path("playId").asString())
				.header("X-User-ID", user).header("X-User-Role", "USER")
				.contentType(MediaType.APPLICATION_JSON).content(submitted))
				.andExpect(status().isTooManyRequests());
		submit(other, play, submitted).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GAME-005"));
		statistics(user, game).andExpect(status().isOk()).andExpect(jsonPath("$.data.validPlayCount").value(1));
		doReturn(NOW.plusSeconds(59)).when(clock).instant();
		submit(user, play, submitted).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
		doReturn(NOW.plusSeconds(60)).when(clock).instant();
		assertThat(result(submit(user, play, submitted).andExpect(status().isOk()))).isEqualTo(original);
		assertThat(count("tickets")).isEqualTo(1);
		assertThat(count("game_reward_claims")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select valid_play_count from user_game_stats where user_id=? and game_id=?",
				Long.class, bytes(user), bytes(game))).isEqualTo(1L);
	}

	private ResultActions statistics(UUID actor, UUID gameId) throws Exception {
		return mvc.perform(get("/api/v1/games/{gameId}/statistics/me", gameId).header("X-User-ID", actor).header("X-User-Role", "USER"));
	}

	private JsonNode start() throws Exception {
		return result(mvc.perform(post("/api/v1/games/{gameId}/plays", game).header("X-User-ID", user).header("X-User-Role", "USER"))
				.andExpect(status().isCreated()));
	}

	private ResultActions submit(UUID actor, JsonNode play, String body) throws Exception {
		return mvc.perform(put("/api/v1/games/{game}/plays/{play}/result", game, play.path("playId").asString())
				.header("X-User-ID", actor).header("X-User-Role", "USER").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String body(JsonNode play, int correct) {
		ColorWordRoundGenerator generator = new ColorWordRoundGenerator(play.path("gameData").path("seed").longValue());
		List<String> answers = new ArrayList<>();
		for (int i = 0; i < correct; i++) answers.add("{\"sequence\":" + (i + 1) + ",\"answer\":\""
				+ generator.next().getInk().name().toLowerCase(Locale.ROOT) + "\",\"elapsedMs\":1}");
		for (int i = 0; i < 3; i++) answers.add("{\"sequence\":" + (correct + i + 1) + ",\"answer\":null,\"elapsedMs\":"
				+ Math.max(1200, 3000 - correct * 80) + "}");
		return "{\"playToken\":\"" + play.path("playToken").asString() + "\",\"validationData\":{\"answers\":[" + String.join(",", answers) + "]}}";
	}

	private JsonNode result(ResultActions response) throws Exception {
		return json.readTree(response.andReturn().getResponse().getContentAsString()).path("data");
	}
	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, bytes(user));
	}
	private static byte[] bytes(UUID id) { return UuidBinary.toBytes(id); }
	private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
}
