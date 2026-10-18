package com.getddo.api.game.dto.response;

import lombok.Builder;

import com.getddo.core.game.domain.TakkoRunRules;

/** FE 계약의 보상 기준과 사용자 선택으로 보완한 최대 활성 tick 수만 공개한다. */
@Builder
public record TakkoRunRulesResponse(int minimumRewardScore, int maxTicks) implements GameRulesResponse {

	public static TakkoRunRulesResponse from(TakkoRunRules rules) {
		return TakkoRunRulesResponse.builder().minimumRewardScore(rules.getMinimumRewardScore())
				.maxTicks(rules.getMaxTicks()).build();
	}
}
