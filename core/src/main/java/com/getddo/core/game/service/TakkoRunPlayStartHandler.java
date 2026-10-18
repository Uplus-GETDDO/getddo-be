package com.getddo.core.game.service;

import org.springframework.stereotype.Component;

import com.getddo.core.game.domain.Game;
import com.getddo.core.game.domain.GameStartConfiguration;
import com.getddo.core.game.domain.TakkoRunRules;

/** 기존 상세·시작 처리 경계에서 타꼬런 규칙 버전과 저장 설정을 확인한다. */
@Component
public class TakkoRunPlayStartHandler implements GamePlayStartHandler {

	@Override
	public boolean supports(Game game) {
		return TakkoRunRules.GAME_CODE.equals(game.getCode())
				&& TakkoRunRules.RULE_VERSION.equals(game.getRuleVersion());
	}

	@Override
	public GameStartConfiguration prepare(Game game) {
		return new GameStartConfiguration(TakkoRunRules.fromStoredSnapshot(game.getRules()),
				TakkoRunRules.GENERATOR_VERSION);
	}
}
