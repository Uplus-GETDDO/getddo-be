package com.getddo.core.game.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.game.domain.ColorWordAnswer;
import com.getddo.core.game.domain.ColorWordValidationResult;
import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.GamePlayCredentials;
import com.getddo.core.game.domain.GameResultSubmission;
import com.getddo.core.game.domain.GameRewardClaim;
import com.getddo.core.game.domain.TakkoRunAction;
import com.getddo.core.game.domain.TakkoRunInput;
import com.getddo.core.game.domain.TakkoRunRules;
import com.getddo.core.game.domain.TakkoTimingRules;
import com.getddo.core.game.domain.TakkoTimingSubmission;
import com.getddo.core.game.domain.TakkoOrderRules;
import com.getddo.core.game.domain.TakkoOrderSubmission;
import com.getddo.core.game.domain.GamePlayResult;
import com.getddo.core.game.domain.TakkoRunSubmission;
import com.getddo.core.game.exception.GameResultFormatException;
import com.getddo.core.game.repository.GamePlayRepository;
import com.getddo.core.game.repository.GameRewardClaimRepository;
import com.getddo.core.game.repository.GameStatisticsRepository;
import com.getddo.core.ticket.service.TicketGrantService;
import com.getddo.core.ticket.domain.GrantResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GamePlayResultRecorderTest {
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	private final GamePlayRepository plays = mock(GamePlayRepository.class);
	private final ColorWordValidator validator = mock(ColorWordValidator.class);
	private final TakkoRunValidator takkoRunValidator = mock(TakkoRunValidator.class);
	private final TakkoTimingValidator takkoTimingValidator = mock(TakkoTimingValidator.class);
	private final TakkoOrderValidator takkoOrderValidator = mock(TakkoOrderValidator.class);
	private final GameStatisticsRepository stats = mock(GameStatisticsRepository.class);
	private final GameRewardClaimRepository claims = mock(GameRewardClaimRepository.class);
	private final TicketGrantService grants = mock(TicketGrantService.class);
	private final UUID user = UUID.randomUUID();
	private final UUID game = UUID.randomUUID();
	private final UUID id = UUID.randomUUID();
	private final GamePlayCredentials token = GamePlayCredentials.issue(new java.security.SecureRandom());
	private final GameResultSubmission submission = new GameResultSubmission(game, id, token.getPlayToken(),
			List.of(new ColorWordAnswer(1, null, 3000)));
	private final GamePlayResultRecorder recorder = new GamePlayResultRecorder(plays, validator, takkoRunValidator,
			takkoTimingValidator, takkoOrderValidator, stats, claims, grants,
			new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
	private GamePlay play;

	@BeforeEach
	void prepare() {
		play = new GamePlay(id, game, user, "color-word-v1", "{}", token.getPlayTokenHash(),
				"color-word-generator-v1", 1L, NOW.minusSeconds(1), null, null, null);
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(play));
		when(plays.complete(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	@DisplayName("정상 0점은 통계만 한 번 반영하고 일일 청구와 지급은 하지 않는다")
	void validZeroCountsWithoutConsumingReward() {
		// given
		when(validator.validate(play, submission.getAnswers())).thenReturn(ColorWordValidationResult.valid(0));
		// when
		assertThat(recorder.record(user, submission).getScore()).isZero();
		// then
		verify(stats).addScore(user, game, 0);
		verifyNoInteractions(claims, grants);
	}

	@Test
	@DisplayName("규칙 위반은 INVALID 확정이며 통계·청구·지급은 호출하지 않는다")
	void invalidDoesNotCount() {
		// given
		when(validator.validate(play, submission.getAnswers())).thenReturn(ColorWordValidationResult.invalid());
		// when
		assertThat(recorder.record(user, submission).getScore()).isNull();
		// then
		verifyNoInteractions(stats, claims, grants);
	}

	@Test
	@DisplayName("기존 낮은 점수 플레이 재시도는 나중에 받은 다른 플레이 보상을 붙이지 않는다")
	void replayLooksUpOnlyOriginalPlayClaim() {
		// given
		GamePlay stored = play.complete(10L, submission.getResultHash(), NOW);
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(stored));
		// when
		assertThat(recorder.record(user, submission).getReward()).isNull();
		// then
		verify(claims).findByPlay(id);
		verifyNoInteractions(stats, validator, grants);
	}

	@Test
	@DisplayName("완료된 플레이의 변경 입력은 409이고 기존 결과를 다시 저장하지 않는다")
	void rejectsChangedReplay() {
		// given
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(play.complete(10L, "a".repeat(64), NOW)));
		// when / then
		assertThatThrownBy(() -> recorder.record(user, submission)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode().getCode()).isEqualTo("GAME-006"));
		verify(plays, never()).complete(any());
		verifyNoInteractions(stats, validator, claims, grants);
	}

	@Test
	@DisplayName("당일 다른 플레이 청구가 있으면 기준 점수도 통계만 반영한다")
	void sameDayRewardIsNotGrantedTwice() {
		// given
		when(validator.validate(play, submission.getAnswers())).thenReturn(ColorWordValidationResult.valid(50));
		when(claims.findByDate(user, game, java.time.LocalDate.of(2026, 10, 15)))
				.thenReturn(Optional.of(mock(GameRewardClaim.class)));
		// when / then
		assertThat(recorder.record(user, submission).getReward()).isNull();
		verify(stats).addScore(user, game, 50);
		verifyNoInteractions(grants);
	}

	@ParameterizedTest
	@ValueSource(longs = {399, 400})
	@DisplayName("타꼬런 유효 점수는 통계에 반영하고 정확히400점부터 기존 지급 서비스를 호출한다")
	void appliesTakkoRunRewardBoundary(long score) {
		// given
		GamePlay takkoPlay = new GamePlay(id, game, user, TakkoRunRules.RULE_VERSION, "{}",
				token.getPlayTokenHash(), TakkoRunRules.GENERATOR_VERSION, 1L, NOW.minusSeconds(1), null, null, null);
		TakkoRunSubmission input = new TakkoRunSubmission(174, List.of(new TakkoRunInput(0, TakkoRunAction.PRESS)));
		GameResultSubmission request = new GameResultSubmission(game, id, token.getPlayToken(), input);
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(takkoPlay));
		when(takkoRunValidator.validate(takkoPlay, input)).thenReturn(score);
		GrantResult reward = mock(GrantResult.class);
		if (score == 400) {
			GameRewardClaim saved = mock(GameRewardClaim.class);
			when(saved.getId()).thenReturn(UUID.randomUUID());
			when(claims.requirePolicy(game, NOW)).thenReturn(UUID.randomUUID());
			when(claims.insert(any())).thenReturn(saved);
			when(grants.grant(any())).thenReturn(reward);
		}
		// when
		var result = recorder.record(user, request);
		// then
		assertThat(result.getScore()).isEqualTo(score);
		verify(stats).addScore(user, game, score);
		verifyNoInteractions(validator);
		if (score == 400) {
			assertThat(result.getReward()).isSameAs(reward);
			verify(grants).grant(any());
		} else {
			assertThat(result.getReward()).isNull();
			verifyNoInteractions(claims, grants);
		}
	}

	@Test
	@DisplayName("타꼬런 플레이에 색깔 답변을 보내면 형식 오류이며 결과를 저장하지 않는다")
	void rejectsWrongGameInputBeforeCompletion() {
		// given
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(new GamePlay(id, game, user,
				TakkoRunRules.RULE_VERSION, "{}", token.getPlayTokenHash(), TakkoRunRules.GENERATOR_VERSION,
				1L, NOW.minusSeconds(1), null, null, null)));
		// when / then
		assertThatThrownBy(() -> recorder.record(user, submission)).isInstanceOf(GameResultFormatException.class);
		verify(plays, never()).complete(any());
		verifyNoInteractions(stats, validator, takkoRunValidator, claims, grants);
	}
	@ParameterizedTest
	@CsvSource({"true,40,false", "true,50,true", "true,60,true", "false,12,false", "false,18,true", "false,25,true"})
	@DisplayName("타이밍50·주문18점 보상 경계는 기존 통계·청구·지급 서비스로 연결한다")
	void routesNewGamesAndRewardBoundary(boolean timing, long score, boolean eligible) {
		// given: 이 테스트는 판정 점수가 확정된 이후 Recorder의 연결 책임만 확인한다.
		String version = timing ? TakkoTimingRules.RULE_VERSION : TakkoOrderRules.RULE_VERSION;
		String generator = timing ? TakkoTimingRules.GENERATOR_VERSION : TakkoOrderRules.GENERATOR_VERSION;
		GamePlay started = new GamePlay(id, game, user, version, "{}", token.getPlayTokenHash(), generator,
				1L, NOW.minusSeconds(1), null, null, null);
		GameResultSubmission request;
		if (timing) {
			TakkoTimingSubmission input = new TakkoTimingSubmission(List.of());
			request = new GameResultSubmission(game, id, token.getPlayToken(), input);
			when(takkoTimingValidator.validate(started, input)).thenReturn(score);
		} else {
			TakkoOrderSubmission input = new TakkoOrderSubmission(List.of());
			request = new GameResultSubmission(game, id, token.getPlayToken(), input);
			when(takkoOrderValidator.validate(started, input)).thenReturn(score);
		}
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(started));
		GrantResult reward = mock(GrantResult.class);
		if (eligible) {
			GameRewardClaim saved = mock(GameRewardClaim.class);
			when(saved.getId()).thenReturn(UUID.randomUUID());
			when(claims.requirePolicy(game, NOW)).thenReturn(UUID.randomUUID());
			when(claims.insert(any())).thenReturn(saved);
			when(grants.grant(any())).thenReturn(reward);
		}
		// when
		GamePlayResult result = recorder.record(user, request);
		// then
		assertThat(result.getScore()).isEqualTo(score);
		verify(stats).addScore(user, game, score);
		verifyNoInteractions(validator, takkoRunValidator);
		if (eligible) assertThat(result.getReward()).isSameAs(reward);
		else verifyNoInteractions(claims, grants);
	}

	@ParameterizedTest
	@CsvSource({"color-word-v1,color-word-generator-v1", "takko-run-v1,takko-run-generator-v1"})
	@DisplayName("기존 색깔·타꼬런 플레이에 신규 게임 입력을 보내도 결과를 저장하지 않는다")
	void rejectsNewShapeForExistingGames(String version, String generator) {
		// given
		when(plays.findForUpdateNowait(id, user, game)).thenReturn(Optional.of(new GamePlay(id, game, user,
				version, "{}", token.getPlayTokenHash(), generator, 1L, NOW.minusSeconds(1), null, null, null)));
		List<GameResultSubmission> requests = List.of(
				new GameResultSubmission(game, id, token.getPlayToken(), new TakkoTimingSubmission(List.of())),
				new GameResultSubmission(game, id, token.getPlayToken(), new TakkoOrderSubmission(List.of())));
		// when / then
		for (GameResultSubmission request : requests) {
			assertThatThrownBy(() -> recorder.record(user, request)).isInstanceOf(GameResultFormatException.class);
		}
		verify(plays, never()).complete(any());
		verifyNoInteractions(stats, validator, takkoRunValidator, takkoTimingValidator, takkoOrderValidator, claims, grants);
	}

}
