package com.getddo.core.game.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;

/** 게임별 정규화된 값을 고정 길이로 해시해 JSON 표현과 무관하게 동일 입력 재시도를 식별한다. */
@Getter
public final class GameResultSubmission {
	private final UUID gameId;
	private final UUID playId;
	private final String playToken;
	private final List<ColorWordAnswer> answers;
	private final TakkoRunSubmission takkoRun;
	private final String resultHash;

	public GameResultSubmission(UUID gameId, UUID playId, String playToken, List<ColorWordAnswer> answers) {
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.playId = Objects.requireNonNull(playId, "playId");
		this.playToken = Objects.requireNonNull(playToken, "playToken");
		this.answers = List.copyOf(answers);
		this.takkoRun = null;
		this.resultHash = hash(this.answers);
	}

	/** 색깔 게임의 기존 해시는 유지하고 타꼬런은 종류·종료 tick·배열 순서를 함께 해시한다. */
	public GameResultSubmission(UUID gameId, UUID playId, String playToken, TakkoRunSubmission takkoRun) {
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.playId = Objects.requireNonNull(playId, "playId");
		this.playToken = Objects.requireNonNull(playToken, "playToken");
		this.answers = List.of();
		this.takkoRun = Objects.requireNonNull(takkoRun, "takkoRun");
		this.resultHash = hash(takkoRun);
	}

	private static String hash(TakkoRunSubmission submission) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((TakkoRunRules.RULE_VERSION + ":endTick/type").getBytes(StandardCharsets.US_ASCII));
			digest.update(ByteBuffer.allocate(4).putInt(submission.getEndTick()).array());
			for (TakkoRunInput input : submission.getInputs()) {
				digest.update(ByteBuffer.allocate(8).putInt(input.getTick())
						.putInt(input.getAction().getHashCode()).array());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
		}
	}

	private static String hash(List<ColorWordAnswer> answers) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (ColorWordAnswer answer : answers) {
				digest.update(ByteBuffer.allocate(16).putInt(answer.getSequence())
						.putInt(answer.getAnswer() == null ? -1 : answer.getAnswer().ordinal())
						.putLong(answer.getElapsedMs()).array());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
		}
	}
}
