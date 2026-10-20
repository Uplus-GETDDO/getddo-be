package com.getddo.core.game.domain;

import java.util.List;

import lombok.Getter;

/** 세 번째 실패까지의 주문 기록. 외부에서 입력 배열을 바꿔 재시도 해시와 판정을 어긋나게 하지 못하게 복사한다. */
@Getter
public final class TakkoOrderSubmission {

	private final List<TakkoOrderRoundInput> rounds;

	public TakkoOrderSubmission(List<TakkoOrderRoundInput> rounds) {
		this.rounds = List.copyOf(rounds);
	}
}
