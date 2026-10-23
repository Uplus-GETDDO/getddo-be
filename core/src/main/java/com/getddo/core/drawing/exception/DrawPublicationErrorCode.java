package com.getddo.core.drawing.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

/** 공개·관리자 확인 업무 오류. */
@Getter
@RequiredArgsConstructor
public enum DrawPublicationErrorCode implements ErrorCode {
	INVALID_PUBLICATION(409, "PUBLICATION-001", "공개 결과와 취소·재추첨 연결이 일치하지 않습니다."),
	NOT_CONFIRMABLE(409, "PUBLICATION-002", "추첨 결과가 아직 발표 가능한 상태가 아닙니다."),
	ADMIN_REQUIRED(403, "PUBLICATION-003", "활성 관리자만 결과를 확인할 수 있습니다."),
	RUN_NOT_FOUND(404, "PUBLICATION-004", "추첨 실행을 찾을 수 없습니다."),
	INVALID_PHASE(409, "PUBLICATION-005", "현재 공개 단계에서 허용되지 않는 요청입니다.");

	private final int status;
	private final String code;
	private final String message;
}
