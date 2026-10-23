package com.getddo.core.game.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoOrderGenerator;
import com.getddo.core.game.domain.TakkoOrderMenu;
import com.getddo.core.game.domain.TakkoOrderRoundInput;
import com.getddo.core.game.domain.TakkoOrderRules;
import com.getddo.core.game.domain.TakkoOrderSubmission;

/** 시작 seed로 주문과 목숨·길이를 재현한다. 세 번째 실패가 마지막인 자연 종료만 인정한다. */
@Service
public class TakkoOrderValidator {

	public Long validate(GamePlay play, TakkoOrderSubmission submission) {
		Objects.requireNonNull(play, "play");
		Objects.requireNonNull(submission, "submission");
		if (!play.hasVerificationContext(TakkoOrderRules.RULE_VERSION, TakkoOrderRules.GENERATOR_VERSION)) {
			throw new IllegalStateException("지원하는 주문 게임의 시작 스냅샷이 필요합니다.");
		}
		TakkoOrderRules rules = TakkoOrderRules.fromStoredSnapshot(play.getRulesSnapshot());
		TakkoOrderGenerator generator = new TakkoOrderGenerator(play.getGenerationSeed());
		int lives = rules.getInitialLives();
		int length = rules.getStartLength();
		long score = 0;
		List<TakkoOrderRoundInput> rounds = submission.getRounds();
		for (int index = 0; index < rounds.size(); index++) {
			TakkoOrderRoundInput round = rounds.get(index);
			List<TakkoOrderMenu> picks = round.getPicks();
			if (round.getSequence() != index + 1 || picks.isEmpty() || picks.size() > length) return null;
			List<TakkoOrderMenu> expected = generator.next(length);
			boolean failed = false;
			for (int pick = 0; pick < picks.size(); pick++) {
				if (picks.get(pick) != expected.get(pick)) {
					// 첫 오답 뒤의 추가 선택은 실제 FE가 생성할 수 없는 기록이다.
					if (pick != picks.size() - 1) return null;
					failed = true;
					break;
				}
			}
			if (failed) {
				if (--lives == 0) return index == rounds.size() - 1 ? score : null;
			} else {
				if (picks.size() != length) return null;
				score += length;
				length = Math.min(rules.getMaxLength(), length + 1);
			}
		}
		return null;
	}
}
