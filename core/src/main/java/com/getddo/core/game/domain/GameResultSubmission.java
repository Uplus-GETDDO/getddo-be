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
	private final TakkoTimingSubmission takkoTiming;
	private final TakkoOrderSubmission takkoOrder;
	private final String resultHash;

	public GameResultSubmission(UUID gameId, UUID playId, String playToken, List<ColorWordAnswer> answers) {
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.playId = Objects.requireNonNull(playId, "playId");
		this.playToken = Objects.requireNonNull(playToken, "playToken");
		this.answers = List.copyOf(answers);
		this.takkoRun = null;
		this.takkoTiming = null;
		this.takkoOrder = null;
		this.resultHash = hash(this.answers);
	}

	/** 색깔 게임의 기존 해시는 유지하고 타꼬런은 종류·종료 tick·배열 순서를 함께 해시한다. */
	public GameResultSubmission(UUID gameId, UUID playId, String playToken, TakkoRunSubmission takkoRun) {
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.playId = Objects.requireNonNull(playId, "playId");
		this.playToken = Objects.requireNonNull(playToken, "playToken");
		this.answers = List.of();
		this.takkoRun = Objects.requireNonNull(takkoRun, "takkoRun");
		this.takkoTiming = null;
		this.takkoOrder = null;
		this.resultHash = hash(takkoRun);
	}

	/** 기존 게임의 해시는 보존하고 타이밍의 판 번호·정수 ms·배열 순서를 별도 종류로 해시한다. */
	public GameResultSubmission(UUID gameId, UUID playId, String playToken, TakkoTimingSubmission takkoTiming) {
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.playId = Objects.requireNonNull(playId, "playId");
		this.playToken = Objects.requireNonNull(playToken, "playToken");
		this.answers = List.of();
		this.takkoRun = null;
		this.takkoTiming = Objects.requireNonNull(takkoTiming, "takkoTiming");
		this.takkoOrder = null;
		this.resultHash = hash(takkoTiming);
	}

	/** 메뉴 순서뿐 아니라 각 주문의 번호·선택 개수도 해시해 주문 경계를 보존한다. */
	public GameResultSubmission(UUID gameId, UUID playId, String playToken, TakkoOrderSubmission takkoOrder) {
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.playId = Objects.requireNonNull(playId, "playId");
		this.playToken = Objects.requireNonNull(playToken, "playToken");
		this.answers = List.of();
		this.takkoRun = null;
		this.takkoTiming = null;
		this.takkoOrder = Objects.requireNonNull(takkoOrder, "takkoOrder");
		this.resultHash = hash(takkoOrder);
	}

	private static String hash(TakkoOrderSubmission submission) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((TakkoOrderRules.RULE_VERSION + ":rounds:sequence/picks").getBytes(StandardCharsets.US_ASCII));
			for (TakkoOrderRoundInput round : submission.getRounds()) {
				digest.update(ByteBuffer.allocate(8).putInt(round.getSequence()).putInt(round.getPicks().size()).array());
				for (TakkoOrderMenu pick : round.getPicks()) {
					digest.update(ByteBuffer.allocate(4).putInt(pick.ordinal()).array());
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
		}
	}

	private static String hash(TakkoTimingSubmission submission) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((TakkoTimingRules.RULE_VERSION + ":taps:sequence/elapsedMs").getBytes(StandardCharsets.US_ASCII));
			for (TakkoTimingTap tap : submission.getTaps()) {
				digest.update(ByteBuffer.allocate(12).putInt(tap.getSequence()).putLong(tap.getElapsedMs()).array());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
		}
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
