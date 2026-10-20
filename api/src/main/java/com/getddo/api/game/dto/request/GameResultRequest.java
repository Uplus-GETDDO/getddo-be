package com.getddo.api.game.dto.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.game.domain.ColorWordAnswer;
import com.getddo.core.game.domain.ColorWordColor;
import com.getddo.core.game.domain.GameResultSubmission;
import com.getddo.core.game.domain.TakkoRunAction;
import com.getddo.core.game.domain.TakkoRunInput;
import com.getddo.core.game.domain.TakkoRunSubmission;
import com.getddo.core.game.domain.TakkoTimingSubmission;
import com.getddo.core.game.domain.TakkoTimingTap;
import com.getddo.core.game.domain.TakkoOrderMenu;
import com.getddo.core.game.domain.TakkoOrderRoundInput;
import com.getddo.core.game.domain.TakkoOrderSubmission;
import com.getddo.core.game.exception.GameErrorCode;

/** G04의 게임별 입력 형식만 검증한다. 게임별 입력은 분리하며 점수 판정은 하지 않는다. */
@Builder
public record GameResultRequest(@NotBlank String playToken, @NotNull JsonNode validationData) {

	public static final int MAX_ANSWERS = 10_000;
	public static final int MAX_INPUTS = 10_000;
	public static final int MAX_TAPS = 10_000;
	public static final int MAX_ROUNDS = 10_000;

	public GameResultSubmission toDomain(UUID gameId, UUID playId) {
		if (validationData == null || !validationData.isObject()) throw format();
		if (validationData.has("rounds")) {
			if (validationData.has("answers") || validationData.has("inputs") || validationData.has("taps")
					|| validationData.has("endTick") || validationData.has("totalTicks")) throw format();
			return takkoOrder(gameId, playId);
		}
		if (validationData.has("taps")) {
			if (validationData.has("answers") || validationData.has("inputs")
					|| validationData.has("endTick") || validationData.has("totalTicks")) throw format();
			return takkoTiming(gameId, playId);
		}
		if (validationData.has("inputs") || validationData.has("endTick")) {
			if (validationData.has("answers") || validationData.has("totalTicks")) {
				throw format();
			}
			return takkoRun(gameId, playId);
		}
		JsonNode answers = validationData.get("answers");
		if (answers == null || !answers.isArray()) throw format();
		if (answers.size() > MAX_ANSWERS) throw new BusinessException(GameErrorCode.RESULT_TOO_LARGE);
		List<ColorWordAnswer> parsed = new ArrayList<>(answers.size());
		for (JsonNode answer : answers) {
			if (!answer.isObject()) throw format();
			JsonNode sequence = answer.get("sequence");
			JsonNode elapsed = answer.get("elapsedMs");
			JsonNode color = answer.get("answer");
			if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToInt()
					|| elapsed == null || !elapsed.isIntegralNumber() || !elapsed.canConvertToLong() || color == null) {
				throw format();
			}
			ColorWordColor choice = null;
			if (!color.isNull()) {
				if (!color.isString()) throw format();
				choice = switch (color.asString()) {
					case "red" -> ColorWordColor.RED;
					case "blue" -> ColorWordColor.BLUE;
					case "yellow" -> ColorWordColor.YELLOW;
					case "green" -> ColorWordColor.GREEN;
					default -> throw format();
				};
			}
			parsed.add(new ColorWordAnswer(sequence.intValue(), choice, elapsed.longValue()));
		}
		return new GameResultSubmission(gameId, playId, playToken, parsed);
	}

	private GameResultSubmission takkoOrder(UUID gameId, UUID playId) {
		JsonNode rounds = validationData.get("rounds");
		if (!rounds.isArray()) throw format();
		if (rounds.size() > MAX_ROUNDS) throw new BusinessException(GameErrorCode.RESULT_TOO_LARGE);

		List<TakkoOrderRoundInput> parsed = new ArrayList<>(rounds.size());
		for (JsonNode round : rounds) {
			if (!round.isObject()) throw format();
			JsonNode sequence = round.get("sequence");
			JsonNode picks = round.get("picks");
			if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToInt()
					|| picks == null || !picks.isArray()) throw format();
			List<TakkoOrderMenu> choices = new ArrayList<>(picks.size());
			for (JsonNode pick : picks) {
				if (!pick.isString()) throw format();
				choices.add(switch (pick.asString()) {
					case "scallion" -> TakkoOrderMenu.SCALLION;
					case "cheese" -> TakkoOrderMenu.CHEESE;
					case "shrimp" -> TakkoOrderMenu.SHRIMP;
					case "nori" -> TakkoOrderMenu.NORI;
					default -> throw format();
				});
			}
			parsed.add(new TakkoOrderRoundInput(sequence.intValue(), choices));
		}
		return new GameResultSubmission(gameId, playId, playToken, new TakkoOrderSubmission(parsed));
	}

	private GameResultSubmission takkoTiming(UUID gameId, UUID playId) {
		JsonNode taps = validationData.get("taps");
		if (!taps.isArray()) throw format();
		if (taps.size() > MAX_TAPS) throw new BusinessException(GameErrorCode.RESULT_TOO_LARGE);

		List<TakkoTimingTap> parsed = new ArrayList<>(taps.size());
		for (JsonNode tap : taps) {
			if (!tap.isObject()) throw format();
			JsonNode sequence = tap.get("sequence");
			JsonNode elapsed = tap.get("elapsedMs");
			if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToInt()
					|| elapsed == null || !elapsed.isIntegralNumber() || !elapsed.canConvertToLong()) throw format();
			parsed.add(new TakkoTimingTap(sequence.intValue(), elapsed.longValue()));
		}
		return new GameResultSubmission(gameId, playId, playToken, new TakkoTimingSubmission(parsed));
	}

	private GameResultSubmission takkoRun(UUID gameId, UUID playId) {
		JsonNode endTick = validationData.get("endTick");
		JsonNode inputs = validationData.get("inputs");
		if (endTick == null || !endTick.isIntegralNumber() || !endTick.canConvertToInt()
				|| inputs == null || !inputs.isArray()) {
			throw format();
		}
		if (inputs.size() > MAX_INPUTS) {
			throw new BusinessException(GameErrorCode.RESULT_TOO_LARGE);
		}
		List<TakkoRunInput> parsed = new ArrayList<>(inputs.size());
		for (JsonNode input : inputs) {
			if (!input.isObject()) {
				throw format();
			}
			JsonNode tick = input.get("tick");
			JsonNode type = input.get("type");
			if (tick == null || !tick.isIntegralNumber() || !tick.canConvertToInt()
					|| type == null || !type.isString() || input.has("action")) {
				throw format();
			}
			TakkoRunAction choice = switch (type.asString()) {
				case "press" -> TakkoRunAction.PRESS;
				case "release" -> TakkoRunAction.RELEASE;
				default -> throw format();
			};
			parsed.add(new TakkoRunInput(tick.intValue(), choice));
		}
		return new GameResultSubmission(gameId, playId, playToken,
				new TakkoRunSubmission(endTick.intValue(), parsed));
	}

	private static BusinessException format() {
		return new BusinessException(CommonErrorCode.INVALID_FORMAT);
	}
}
