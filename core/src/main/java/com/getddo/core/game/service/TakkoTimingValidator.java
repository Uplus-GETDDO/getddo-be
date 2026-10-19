package com.getddo.core.game.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoTimingRound;
import com.getddo.core.game.domain.TakkoTimingRoundGenerator;
import com.getddo.core.game.domain.TakkoTimingRules;
import com.getddo.core.game.domain.TakkoTimingSubmission;
import com.getddo.core.game.domain.TakkoTimingTap;

/** 시작 스냅샷과 꽂기 시각으로 첫 실패 종료를 재현한다. 규칙 위반은 점수 null로 반환한다. */
@Service
public class TakkoTimingValidator {

	public Long validate(GamePlay play, TakkoTimingSubmission submission) {
		Objects.requireNonNull(play, "play");
		Objects.requireNonNull(submission, "submission");
		if (!play.hasVerificationContext() || !TakkoTimingRules.RULE_VERSION.equals(play.getRuleVersion())
				|| !TakkoTimingRules.GENERATOR_VERSION.equals(play.getGeneratorVersion())
				|| play.getGenerationSeed() < 1 || play.getGenerationSeed() > 0xffff_ffffL) {
			throw new IllegalStateException("지원하는 타이밍 게임의 시작 스냅샷이 필요합니다.");
		}
		TakkoTimingRules.fromStoredSnapshot(play.getRulesSnapshot());

		TakkoTimingRoundGenerator generator = new TakkoTimingRoundGenerator(play.getGenerationSeed());
		List<TakkoTimingTap> taps = submission.getTaps();
		long score = 0;
		for (int index = 0; index < taps.size(); index++) {
			TakkoTimingTap tap = taps.get(index);
			if (tap.getSequence() != index + 1 || tap.getElapsedMs() < 0) return null;
			TakkoTimingRound round = generator.next(index);
			int points = round.pointsAt(tap.getElapsedMs());
			if (points == 0) return index == taps.size() - 1 ? score : null;
			score += points;
		}
		// 비어 있거나 마지막까지 성공한 입력은 첫 실패로 끝난 판이 아니다.
		return null;
	}
}
