package com.getddo.core.game.domain;

import lombok.Getter;

/** 1부터 시작하는 판 번호와 해당 판에서 실제 움직인 정수 ms. 규칙 위반은 판정기가 구분한다. */
@Getter
public final class TakkoTimingTap {

	private final int sequence;
	private final long elapsedMs;

	public TakkoTimingTap(int sequence, long elapsedMs) {
		this.sequence = sequence;
		this.elapsedMs = elapsedMs;
	}
}
