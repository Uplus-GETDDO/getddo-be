package com.getddo.core.game.service;

import org.springframework.stereotype.Component;

import com.getddo.core.game.domain.Game;
import com.getddo.core.game.domain.GameStartConfiguration;
import com.getddo.core.game.domain.TakkoOrderRules;

/** 기존 G02·G03 흐름에 주문 게임의 지원 버전과 저장 규칙을 연결한다. */
@Component
public class TakkoOrderPlayStartHandler implements GamePlayStartHandler {

	@Override
	public boolean supports(Game game) {
		return TakkoOrderRules.GAME_CODE.equals(game.getCode())
				&& TakkoOrderRules.RULE_VERSION.equals(game.getRuleVersion());
	}

	@Override
	public GameStartConfiguration prepare(Game game) {
		return new GameStartConfiguration(TakkoOrderRules.fromStoredSnapshot(game.getRules()),
				TakkoOrderRules.GENERATOR_VERSION);
	}
}
