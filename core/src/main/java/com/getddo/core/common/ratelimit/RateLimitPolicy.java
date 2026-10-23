package com.getddo.core.common.ratelimit;

import java.time.Duration;

import lombok.Getter;

/** 저장소 구현과 공유하는 일반 요청 한도다. 외부 설정 바인딩은 API에서 수행한다. */
@Getter
public final class RateLimitPolicy {
	private final int limit;
	private final Duration window;
	private final Integer scopedLimit;

	/** 일반 한도와 선택적 범위 한도, 집계 구간이 유효한지 검사한다. */
	public RateLimitPolicy(int limit, Duration window, Integer scopedLimit) {
		if (limit < 1 || window == null || window.isNegative() || window.isZero()
				|| (scopedLimit != null && scopedLimit < 1)) {
			throw new IllegalArgumentException("빈도 제한 정책은 한도 1 이상, 창 0보다 큼이어야 한다.");
		}
		this.limit = limit;
		this.window = window;
		this.scopedLimit = scopedLimit;
	}
}
