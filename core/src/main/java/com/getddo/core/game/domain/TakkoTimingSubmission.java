package com.getddo.core.game.domain;

import java.util.List;

import lombok.Getter;

/** 첫 실패까지의 꽂기 기록. 외부에서 입력 배열을 바꿔 재시도 해시와 판정을 어긋나게 하지 못하게 복사한다. */
@Getter
public final class TakkoTimingSubmission {

	private final List<TakkoTimingTap> taps;

	public TakkoTimingSubmission(List<TakkoTimingTap> taps) {
		this.taps = List.copyOf(taps);
	}
}
