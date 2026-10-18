package com.getddo.core.game.domain;

/** 같은 tick에서는 제출 배열 순서대로 적용하는 점프 입력. 해시 코드는 enum 순서와 분리한다. */
public enum TakkoRunAction {

	PRESS(1),
	RELEASE(2);

	private final int hashCode;

	TakkoRunAction(int hashCode) {
		this.hashCode = hashCode;
	}

	public int getHashCode() {
		return hashCode;
	}
}
