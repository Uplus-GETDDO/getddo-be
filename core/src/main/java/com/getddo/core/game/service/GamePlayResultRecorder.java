package com.getddo.core.game.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.game.domain.ColorWordRules;
import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.GamePlayCredentials;
import com.getddo.core.game.domain.GamePlayResult;
import com.getddo.core.game.domain.GameResultSubmission;
import com.getddo.core.game.domain.GameRewardClaim;
import com.getddo.core.game.domain.TakkoRunRules;
import com.getddo.core.game.exception.GameErrorCode;
import com.getddo.core.game.exception.GameResultFormatException;
import com.getddo.core.game.repository.GamePlayRepository;
import com.getddo.core.game.repository.GameRewardClaimRepository;
import com.getddo.core.game.repository.GameStatisticsRepository;
import com.getddo.core.ticket.domain.GrantCommand;
import com.getddo.core.ticket.domain.GrantResult;
import com.getddo.core.ticket.domain.GrantSource;
import com.getddo.core.ticket.domain.GrantSourceType;
import com.getddo.core.ticket.service.TicketGrantService;

/** 결과·통계·일일 청구·응모권 지급을 한 번에 확정한다. 실패는 전체 롤백한다. */
@Service
@RequiredArgsConstructor
public class GamePlayResultRecorder {
	private final GamePlayRepository plays;
	private final ColorWordValidator validator;
	private final TakkoRunValidator takkoRunValidator;
	private final GameStatisticsRepository statistics;
	private final GameRewardClaimRepository claims;
	private final TicketGrantService grants;
	private final TimeProvider time;

	/** 플레이 → 사용자별 게임 통계 → 청구 → 응모권 순서. 호출자는 별도 Bean이다. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public GamePlayResult record(UUID userId, GameResultSubmission submission) {
		GamePlay play = plays.findForUpdateNowait(submission.getPlayId(), userId, submission.getGameId())
				.filter(found -> GamePlayCredentials.matches(found, userId, submission.getGameId(), submission.getPlayToken()))
				.orElseThrow(() -> new BusinessException(GameErrorCode.PLAY_NOT_FOUND));
		if (play.isCompleted()) {
			if (!Objects.equals(play.getResultHash(), submission.getResultHash())) {
				throw new BusinessException(GameErrorCode.RESULT_CONFLICT);
			}
			return GamePlayResult.completed(play, previousReward(play));
		}
		Long score = validate(play, submission);
		if (score != null) {
			statistics.lockOrCreate(userId, play.getGameId());
		}
		Instant completedAt = time.now().truncatedTo(ChronoUnit.MICROS);
		GamePlay completed = plays.complete(play.complete(score, submission.getResultHash(), completedAt));
		GrantResult reward = null;
		if (completed.isValid()) {
			statistics.addScore(userId, completed.getGameId(), completed.getScore());
			if (completed.getScore() >= minimumRewardScore(play)) {
				reward = claimAndGrant(completed);
			}
		}
		return GamePlayResult.completed(completed, reward);
	}

	/** 저장된 규칙으로 종류를 선택한다. 클라이언트의 입력 형태만으로 게임을 결정하지 않는다. */
	private Long validate(GamePlay play, GameResultSubmission submission) {
		if (ColorWordRules.RULE_VERSION.equals(play.getRuleVersion())) {
			if (submission.getTakkoRun() != null) {
				throw new GameResultFormatException();
			}
			return validator.validate(play, submission.getAnswers()).getScore();
		}
		if (TakkoRunRules.RULE_VERSION.equals(play.getRuleVersion())) {
			if (submission.getTakkoRun() == null) {
				throw new GameResultFormatException();
			}
			return takkoRunValidator.validate(play, submission.getTakkoRun());
		}
		throw new IllegalStateException("지원하는 게임의 시작 스냅샷이 필요합니다.");
	}

	private static long minimumRewardScore(GamePlay play) {
		return switch (play.getRuleVersion()) {
			case ColorWordRules.RULE_VERSION -> ColorWordRules.MINIMUM_REWARD_SCORE;
			case TakkoRunRules.RULE_VERSION -> TakkoRunRules.MINIMUM_REWARD_SCORE;
			default -> throw new IllegalStateException("지원하는 게임의 보상 규칙이 필요합니다.");
		};
	}

	private GrantResult claimAndGrant(GamePlay completed) {
		LocalDate date = time.businessDate(completed.getCompletedAt());
		// 다른 플레이가 이미 지급받은 날이면 이번 플레이의 보상은 null로 고정한다.
		if (claims.findByDate(completed.getUserId(), completed.getGameId(), date).isPresent()) return null;
		GameRewardClaim claim = claims.insert(GameRewardClaim.of(completed,
				claims.requirePolicy(completed.getGameId(), completed.getCompletedAt()), date));
		String reason = switch (completed.getRuleVersion()) {
			case ColorWordRules.RULE_VERSION -> "글자색깔 맞추기 일일 보상";
			case TakkoRunRules.RULE_VERSION -> "타꼬런 일일 보상";
			default -> throw new IllegalStateException("지원하는 게임의 보상 규칙이 필요합니다.");
		};
		return grants.grant(new GrantCommand(completed.getUserId(), source(claim), 1, reason));
	}

	private GrantResult previousReward(GamePlay play) {
		return claims.findByPlay(play.getId()).map(claim -> grants.findGrant(source(claim))
				.orElseThrow(() -> new IllegalStateException("확정된 게임 청구의 지급 결과가 없습니다."))).orElse(null);
	}

	private static GrantSource source(GameRewardClaim claim) {
		return new GrantSource(GrantSourceType.GAME, claim.getId());
	}
}
