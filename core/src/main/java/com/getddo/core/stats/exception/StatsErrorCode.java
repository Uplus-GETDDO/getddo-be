package com.getddo.core.stats.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum StatsErrorCode implements ErrorCode {
	/** 조회 기간이 비었거나 끝 날짜가 시작 날짜보다 앞서거나 최대 일수를 넘는 경우. */
	INVALID_PERIOD(400, "STATS-001", "조회 기간이 올바르지 않습니다."),
	/** 관리자 집계 API를 활성 관리자가 아닌 사용자가 호출했다. */
	ADMIN_REQUIRED(403, "STATS-002", "관리자만 집계를 조회할 수 있습니다.");

	private final int status;
	private final String code;
	private final String message;
}
