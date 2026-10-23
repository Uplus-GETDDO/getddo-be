package com.getddo.core.game.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/**
 * 발급 당시의 규칙·문제 생성 기준과 최초 판정 결과.
 * 검증 기준이 없는 과거 행은 조회만 허용하며 새 결과로 확정하지 않는다.
 */
@Getter
public final class GamePlay {

	private final UUID id;
	private final UUID gameId;
	private final UUID userId;
	private final String ruleVersion;
	private final String rulesSnapshot;
	private final String playTokenHash;
	private final String generatorVersion;
	private final Long generationSeed;
	private final Instant startedAt;
	private final Long score;
	private final String resultHash;
	private final Instant completedAt;

	/** 저장된 행의 복원에도 사용한다. 과거 행의 검증 기준은 null일 수 있다. */
	public GamePlay(UUID id, UUID gameId, UUID userId, String ruleVersion, String rulesSnapshot,
			String playTokenHash, String generatorVersion, Long generationSeed, Instant startedAt,
			Long score, String resultHash, Instant completedAt) {
		boolean noContext = rulesSnapshot == null && playTokenHash == null
				&& generatorVersion == null && generationSeed == null;
		boolean completeContext = rulesSnapshot != null && playTokenHash != null
				&& generatorVersion != null && generationSeed != null;
		if (!noContext && !completeContext) {
			throw new IllegalArgumentException("검증 기준은 모두 null이거나 모두 설정되어야 합니다.");
		}
		if (completeContext) {
			boolean unfinished = completedAt == null && score == null && resultHash == null;
			boolean completed = completedAt != null && startedAt != null && !completedAt.isBefore(startedAt)
					&& resultHash != null
					&& (score == null || score >= 0);
			if (!unfinished && !completed) {
				throw new IllegalArgumentException("플레이 결과의 점수·해시·완료 시각 조합이 올바르지 않습니다.");
			}
			if (completed) {
				requireHash(resultHash);
			}
		}
		this.id = id;
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.userId = Objects.requireNonNull(userId, "userId");
		this.ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
		this.rulesSnapshot = rulesSnapshot;
		this.playTokenHash = playTokenHash;
		this.generatorVersion = generatorVersion;
		this.generationSeed = generationSeed;
		this.startedAt = startedAt;
		this.score = score;
		this.resultHash = resultHash;
		this.completedAt = completedAt;
	}

	/** 토큰 원문 대신 SHA-256 해시를 받는다. 시작 시각과 ID는 영속화 시 발급한다. */
	public static GamePlay start(Game game, UUID userId, String playTokenHash, String generatorVersion,
			long generationSeed) {
		if (!game.isActive()) {
			throw new IllegalStateException("비활성 게임은 시작할 수 없습니다.");
		}
		requireHash(playTokenHash);
		if (generatorVersion == null || generatorVersion.isBlank() || generatorVersion.length() > 50
				|| generationSeed < 1 || generationSeed > 0xffff_ffffL) {
			throw new IllegalArgumentException("문제 생성 버전과 0이 아닌 unsigned 32-bit seed가 필요합니다.");
		}
		return new GamePlay(null, Objects.requireNonNull(game.getId(), "game.id"), userId,
				game.getRuleVersion(), game.getRules(), playTokenHash, generatorVersion, generationSeed,
				null, null, null, null);
	}

	public boolean hasVerificationContext() {
		return rulesSnapshot != null && playTokenHash != null && generatorVersion != null && generationSeed != null;
	}

	/** 호출자가 지원하는 버전과 unsigned 32-bit seed를 함께 확인한다. 과거 행은 검증 대상으로 쓰지 않는다. */
	public boolean hasVerificationContext(String expectedRuleVersion, String expectedGeneratorVersion) {
		return hasVerificationContext() && ruleVersion.equals(expectedRuleVersion)
				&& generatorVersion.equals(expectedGeneratorVersion)
				&& generationSeed >= 1 && generationSeed <= 0xffff_ffffL;
	}

	public boolean isCompleted() {
		return completedAt != null;
	}

	/** 0점도 유효하다. 완료된 무효 결과의 score는 null이다. */
	public boolean isValid() {
		return isCompleted() && score != null;
	}

	/**
	 * 서버 판정을 확정한다. 무효 판정은 null 점수를 사용한다.
	 * 같은 해시 재시도는 최초 결과를 유지하고 다른 입력은 거절한다.
	 */
	public GamePlay complete(Long validatedScore, String submittedHash, Instant completionTime) {
		Objects.requireNonNull(id, "저장된 플레이만 완료할 수 있습니다.");
		if (!hasVerificationContext()) {
			throw new IllegalStateException("검증 기준이 없는 과거 플레이는 완료할 수 없습니다.");
		}
		requireHash(submittedHash);
		if (isCompleted()) {
			if (!Objects.equals(resultHash, submittedHash)) {
				throw new IllegalStateException("이미 확정한 플레이의 입력을 변경할 수 없습니다.");
			}
			return this;
		}
		Instant time = Objects.requireNonNull(completionTime, "completionTime").truncatedTo(ChronoUnit.MICROS);
		if (startedAt == null || time.isBefore(startedAt) || (validatedScore != null && validatedScore < 0)) {
			throw new IllegalArgumentException("완료 시각과 서버 판정 점수가 올바르지 않습니다.");
		}
		return new GamePlay(id, gameId, userId, ruleVersion, rulesSnapshot, playTokenHash, generatorVersion,
				generationSeed, startedAt, validatedScore, submittedHash, time);
	}

	private static void requireHash(String hash) {
		if (hash == null || !hash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("SHA-256 소문자 16진수 해시가 필요합니다.");
		}
	}
}
