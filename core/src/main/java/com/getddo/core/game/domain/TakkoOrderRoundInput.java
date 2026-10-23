package com.getddo.core.game.domain;

import java.util.List;

import lombok.Getter;

/** 주문 번호와 성공 전체 또는 첫 오답까지의 선택. 외부 배열 변경을 막는다. */
@Getter
public final class TakkoOrderRoundInput {

	private final int sequence;
	private final List<TakkoOrderMenu> picks;

	public TakkoOrderRoundInput(int sequence, List<TakkoOrderMenu> picks) {
		this.sequence = sequence;
		this.picks = List.copyOf(picks);
	}
}
