package com.getddo.api.game.dto.response;

import lombok.Builder;

import com.getddo.core.game.domain.TakkoTimingRules;

/** 타이밍 게임에서 공개하는 일반 성공·퍼펙트·최소 보상 점수. */
@Builder
public record TakkoTimingRulesResponse(int points, int perfectPoints, int minimumRewardScore)
		implements GameRulesResponse {

	public static TakkoTimingRulesResponse from(TakkoTimingRules rules) {
		return TakkoTimingRulesResponse.builder().points(rules.getPoints())
				.perfectPoints(rules.getPerfectPoints()).minimumRewardScore(rules.getMinimumRewardScore()).build();
	}
}
