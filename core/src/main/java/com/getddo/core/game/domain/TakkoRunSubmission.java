package com.getddo.core.game.domain;

import java.util.List;

import lombok.Getter;

/** 자연 종료한 0부터 시작하는 tick 번호와 입력. 게임 규칙 위반은 판정기가 INVALID로 구분한다. */
@Getter
public final class TakkoRunSubmission {

	private final int endTick;
	private final List<TakkoRunInput> inputs;

	public TakkoRunSubmission(int endTick, List<TakkoRunInput> inputs) {
		this.endTick = endTick;
		this.inputs = List.copyOf(inputs);
	}
}
