package com.getddo.core.abuse.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

/** 관리자 차단 이력 조회의 확정된 업무 오류다. */
@Getter
@RequiredArgsConstructor
public enum AbuseErrorCode implements ErrorCode {

	ADMIN_REQUIRED(403, "ABUSE-001", "활성 관리자 권한이 필요합니다.");

	private final int status;
	private final String code;
	private final String message;
}
