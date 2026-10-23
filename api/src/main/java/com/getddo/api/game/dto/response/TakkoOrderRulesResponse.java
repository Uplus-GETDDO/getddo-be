package com.getddo.api.game.dto.response;

import lombok.Builder;

import com.getddo.core.game.domain.TakkoOrderRules;

/** 주문 게임에서 공개하는 초기 목숨·주문 길이. */
@Builder
public record TakkoOrderRulesResponse(int initialLives, int startLength, int maxLength)
		implements GameRulesResponse {

	public static TakkoOrderRulesResponse from(TakkoOrderRules rules) {
		return TakkoOrderRulesResponse.builder().initialLives(rules.getInitialLives())
				.startLength(rules.getStartLength()).maxLength(rules.getMaxLength()).build();
	}
}
