package com.getddo.api.game.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.getddo.core.game.domain.ColorWordRules;
import com.getddo.core.game.domain.GameStartRules;
import com.getddo.core.game.domain.TakkoRunRules;
import com.getddo.core.game.domain.TakkoTimingRules;
import com.getddo.core.game.domain.TakkoOrderRules;

/** 게임별 공개 규칙 응답. 도메인 규칙 객체를 직접 직렬화하지 않는다. */
@Schema(oneOf = {ColorWordRulesResponse.class, TakkoRunRulesResponse.class,
		TakkoTimingRulesResponse.class, TakkoOrderRulesResponse.class})
public sealed interface GameRulesResponse permits ColorWordRulesResponse, TakkoRunRulesResponse,
		TakkoTimingRulesResponse, TakkoOrderRulesResponse {

	static GameRulesResponse from(GameStartRules rules) {
		if (rules instanceof ColorWordRules colorWordRules) {
			return ColorWordRulesResponse.from(colorWordRules);
		}
		if (rules instanceof TakkoRunRules takkoRunRules) {
			return TakkoRunRulesResponse.from(takkoRunRules);
		}
		if (rules instanceof TakkoTimingRules takkoTimingRules) {
			return TakkoTimingRulesResponse.from(takkoTimingRules);
		}
		if (rules instanceof TakkoOrderRules takkoOrderRules) {
			return TakkoOrderRulesResponse.from(takkoOrderRules);
		}
		throw new IllegalStateException("지원하지 않는 게임 시작 규칙 응답입니다.");
	}
}
