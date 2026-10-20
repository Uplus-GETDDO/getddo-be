package com.getddo.core.game.service;

import org.springframework.stereotype.Component;

import com.getddo.core.game.domain.Game;
import com.getddo.core.game.domain.GameStartConfiguration;
import com.getddo.core.game.domain.TakkoTimingRules;

/** 기존 G02·G03 흐름에 타이밍 게임의 지원 버전과 저장 규칙을 연결한다. */
@Component
public class TakkoTimingPlayStartHandler implements GamePlayStartHandler {

	@Override
	public boolean supports(Game game) {
		return TakkoTimingRules.GAME_CODE.equals(game.getCode())
				&& TakkoTimingRules.RULE_VERSION.equals(game.getRuleVersion());
	}

	@Override
	public GameStartConfiguration prepare(Game game) {
		return new GameStartConfiguration(TakkoTimingRules.fromStoredSnapshot(game.getRules()),
				TakkoTimingRules.GENERATOR_VERSION);
	}
}
