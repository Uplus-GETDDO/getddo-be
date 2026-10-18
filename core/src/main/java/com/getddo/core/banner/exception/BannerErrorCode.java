package com.getddo.core.banner.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

/** 사용자와 채택한 배너 업무 오류 계약. */
@Getter
@RequiredArgsConstructor
public enum BannerErrorCode implements ErrorCode {
	INVALID_SETTINGS(400, "BANNER-001", "배너 설정이 올바르지 않습니다."),
	ADMIN_REQUIRED(403, "BANNER-002", "활성 관리자만 사용할 수 있습니다."),
	BANNER_NOT_FOUND(404, "BANNER-003", "배너를 찾을 수 없습니다."),
	LIMIT_EXCEEDED(409, "BANNER-004", "배너는 최대 5개까지 등록할 수 있습니다."),
	LIST_CHANGED(409, "BANNER-005", "배너 목록이 변경되었습니다. 다시 조회해 주세요."),
	EVENT_NOT_FOUND(404, "BANNER-006", "연결할 이벤트를 찾을 수 없습니다."),
	EVENT_CANCELED(409, "BANNER-007", "취소된 이벤트에는 배너를 연결할 수 없습니다."),
	USER_INACTIVE(403, "BANNER-008", "활성 사용자만 배너를 조회할 수 있습니다.");

	private final int status;
	private final String code;
	private final String message;
}
