package com.getddo.api.game.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import com.getddo.core.game.domain.GamePlay;
import com.getddo.core.game.domain.TakkoRunRules;

/** 저장된 생성 버전·seed와 타꼬런의 정확한 1/120초 간격. 색깔 게임 응답은 기존 두 필드를 유지한다. */
@Builder
public record GamePlayDataResponse(String generatorVersion, long seed,
		@JsonInclude(JsonInclude.Include.NON_NULL) Double tickMs) {

	public static GamePlayDataResponse from(GamePlay play) {
		return GamePlayDataResponse.builder().generatorVersion(play.getGeneratorVersion())
				.seed(play.getGenerationSeed())
				.tickMs(TakkoRunRules.RULE_VERSION.equals(play.getRuleVersion()) ? TakkoRunRules.TICK_MILLIS : null)
				.build();
	}
}
