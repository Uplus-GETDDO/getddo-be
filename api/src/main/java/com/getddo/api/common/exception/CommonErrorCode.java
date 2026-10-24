package com.getddo.api.common.exception;

import com.getddo.core.common.exception.ErrorCode;

/**
 * 요청 해석·검증 실패와 예상하지 못한 서버 오류처럼 특정 도메인에 속하지 않는 API 오류다.
 *
 * <p>업무별 오류는 해당 도메인에서 ErrorCode를 구현해 추가한다.
 * 이 enum에 모든 도메인의 오류를 모으지 않는다.
 * 405·415 등 그 밖의 표준 MVC 오류는 예외 처리기에서 HTTP-{상태값} 코드로 반환한다.</p>
 */
public enum CommonErrorCode implements ErrorCode {

	/** 예상하지 못한 서버 오류. 실제 예외 대신 고정 메시지를 사용자에게 보여준다. */
	INTERNAL_ERROR(500, "COMMON-001", "서버 오류가 발생했습니다."),
	/** DTO 제약 위반, 필수 파라미터·헤더 누락 등 입력 조건을 만족하지 못한 경우. */
	INVALID_INPUT(400, "COMMON-002", "요청 값이 올바르지 않습니다."),
	/** 깨진 JSON이나 숫자·UUID 등으로 변환할 수 없는 요청 값처럼 해석 단계에서 실패한 경우. */
	INVALID_FORMAT(400, "COMMON-005", "요청 형식이 올바르지 않습니다."),
	/** 같은 사용자의 요청이 정해진 빈도 한도를 넘은 경우. 응답의 {@code Retry-After}에 다시 시도할 수 있는 초가 담긴다. */
	RATE_LIMIT_EXCEEDED(429, "COMMON-006", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
	/** DB 연결 획득 실패·접속 단절. 요청이 커밋되지 않았음을 보장하지 않는다. */
	DB_TEMPORARILY_UNAVAILABLE(503, "COMMON-007", "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
	// 사용자 문맥 오류의 기존 응답 코드는 호출부 호환성을 위해 유지한다.
	/** 현재 사용자를 선택하는 필수 헤더가 누락된 경우. */
	USER_CONTEXT_REQUIRED(401, "USER-003", "사용자 정보 헤더가 필요합니다."),
	/** 헤더의 역할이 DB에 저장된 사용자 역할과 다른 경우. */
	USER_ROLE_MISMATCH(403, "USER-004", "사용자 역할이 일치하지 않습니다."),
	/** USER의 문맥을 확인하는 데 필요한 DB 멤버십이 없는 경우. */
	USER_MEMBERSHIP_REQUIRED(403, "USER-005", "사용자 멤버십을 확인할 수 없습니다."),
	/** 헤더의 멤버십이 DB 값과 다른 경우. 선택 멤버십을 대조하는 API에서는 ADMIN도 적용한다. */
	USER_MEMBERSHIP_MISMATCH(409, "USER-006", "사용자 멤버십이 일치하지 않습니다.");

	private final int status;
	private final String code;
	private final String message;

	CommonErrorCode(int status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	@Override
	public int getStatus() {
		return status;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
