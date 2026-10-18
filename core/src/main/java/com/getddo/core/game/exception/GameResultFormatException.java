package com.getddo.core.game.exception;

/** HTTP DTO와 무관하게 저장된 게임 종류와 제출 기록 종류의 불일치를 표현한다. */
public class GameResultFormatException extends RuntimeException {

	public GameResultFormatException() {
		super("게임에 맞는 결과 기록 형식이 필요합니다.");
	}
}
