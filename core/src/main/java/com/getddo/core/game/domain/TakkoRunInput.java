package com.getddo.core.game.domain;

import java.util.Objects;

import lombok.Getter;

/** 활성 물리 tick 시작 전에 발생한 입력. 같은 tick 입력의 배열 순서를 보존한다. */
@Getter
public final class TakkoRunInput {

	private final int tick;
	private final TakkoRunAction action;

	public TakkoRunInput(int tick, TakkoRunAction action) {
		this.tick = tick;
		this.action = Objects.requireNonNull(action, "action");
	}
}
