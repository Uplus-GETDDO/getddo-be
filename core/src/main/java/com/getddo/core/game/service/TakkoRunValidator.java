package com.getddo.core.game.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoRunAction;
import com.getddo.core.game.domain.TakkoRunInput;
import com.getddo.core.game.domain.TakkoRunReplay;
import com.getddo.core.game.domain.TakkoRunRules;
import com.getddo.core.game.domain.TakkoRunSubmission;

/** 저장된 버전·seed로 충돌/10분 종료를 재현한다. 유효 점수 또는 무효를 뜻하는 null을 반환한다. */
@Service
public class TakkoRunValidator {

	public Long validate(GamePlay play, TakkoRunSubmission submission) {
		Objects.requireNonNull(play, "play");
		Objects.requireNonNull(submission, "submission");
		if (!play.hasVerificationContext() || !TakkoRunRules.RULE_VERSION.equals(play.getRuleVersion())
				|| !TakkoRunRules.GENERATOR_VERSION.equals(play.getGeneratorVersion())
				|| play.getGenerationSeed() < 1 || play.getGenerationSeed() > 0xffff_ffffL) {
			throw new IllegalStateException("지원하는 타꼬런의 시작 스냅샷이 필요합니다.");
		}
		TakkoRunRules rules = TakkoRunRules.fromStoredSnapshot(play.getRulesSnapshot());
		int endTick = submission.getEndTick();
		List<TakkoRunInput> inputs = submission.getInputs();
		if (endTick < 0 || endTick >= rules.getMaxTicks() || inputs.isEmpty()
				|| inputs.getFirst().getTick() != 0 || inputs.getFirst().getAction() != TakkoRunAction.PRESS) {
			return null;
		}
		int previousTick = -1;
		for (TakkoRunInput input : inputs) {
			if (input.getTick() < 0 || input.getTick() < previousTick || input.getTick() > endTick) {
				return null;
			}
			previousTick = input.getTick();
		}
		TakkoRunReplay replay = new TakkoRunReplay(play.getGenerationSeed());
		int nextInput = 0;
		for (int tick = 0; tick <= endTick; tick++) {
			while (nextInput < inputs.size() && inputs.get(nextInput).getTick() == tick) {
				replay.apply(inputs.get(nextInput++).getAction());
			}
			if (replay.step()) {
				return tick == endTick ? replay.getScore() : null;
			}
		}
		return endTick == rules.getMaxTicks() - 1 ? replay.getScore() : null;
	}
}
