package com.getddo.core.game.domain;

/** FE 모델의 난수 소비·연산 순서를 유지해 같은 seed와 성공 횟수로 같은 구간을 재현한다. */
public final class TakkoTimingRoundGenerator {

	private int state;
	private TakkoTimingRound previous;

	public TakkoTimingRoundGenerator(long seed) {
		if (seed < 1 || seed > 0xffff_ffffL) {
			throw new IllegalArgumentException("0이 아닌 unsigned 32-bit seed가 필요합니다.");
		}
		this.state = (int) seed;
	}

	/** 판마다 난수를 한 번 소비하며 앞 구간과 가까우면 추가 추출 없이 반대편으로 옮긴다. */
	public TakkoTimingRound next(int successCount) {
		if (successCount < 0) {
			throw new IllegalArgumentException("성공 횟수는 음수일 수 없습니다.");
		}
		double width = Math.max(0.09, 0.22 - successCount * 0.01);
		double min = 0.08 + width / 2;
		double span = 1 - min * 2;
		double center = min + nextRandom() * span;
		if (previous != null && Math.abs(previous.getCenter() - center) < width) {
			center = 1 - center;
		}
		// 긴 성공 기록에서도 정수 곱셈이 넘치지 않도록 계산한 뒤 기존 최소값으로 제한한다.
		int sweepMs = (int) Math.max(650L, 1600L - (long) successCount * 60);
		previous = new TakkoTimingRound(center, width, sweepMs);
		return previous;
	}

	private double nextRandom() {
		state ^= state << 13;
		state ^= state >>> 17;
		state ^= state << 5;
		return Integer.toUnsignedLong(state) / 4294967296.0;
	}
}
