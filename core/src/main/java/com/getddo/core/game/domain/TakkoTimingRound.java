package com.getddo.core.game.domain;

import lombok.Getter;

/** 한 번 꽂기를 시도할 구간의 중심·폭과 한쪽 끝까지의 이동 시간. 위치는 0~1 비율이다. */
@Getter
public final class TakkoTimingRound {

	private final double center;
	private final double width;
	private final int sweepMs;

	TakkoTimingRound(double center, double width, int sweepMs) {
		this.center = center;
		this.width = width;
		this.sweepMs = sweepMs;
	}

	/** FE와 같은 연산 순서·포함 경계로 판정한다. 음수 시간 검사는 호출자가 맡는다. */
	public int pointsAt(long elapsedMs) {
		double phase = (elapsedMs / (double) sweepMs) % 2;
		double position = phase <= 1 ? phase : 2 - phase;
		double distance = Math.abs(position - center);
		if (distance <= width / 2 * 0.3) return TakkoTimingRules.PERFECT_POINTS;
		return distance <= width / 2 ? TakkoTimingRules.POINTS : 0;
	}
}
