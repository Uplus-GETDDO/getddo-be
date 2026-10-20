package com.getddo.core.mission.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

/** 미션 업무 오류 코드. 코드는 {@code MISSION-숫자} 형식이다. */
@Getter
@RequiredArgsConstructor
public enum MissionErrorCode implements ErrorCode {
	/** 미션의 기간·문항·선택지·정답 구성이 규칙에 맞지 않는 경우. */
	INVALID_MISSION_DEFINITION(400, "MISSION-001", "미션 구성이 올바르지 않습니다."),
	/** 제출한 답변의 형태가 미션 유형이나 문항 유형과 맞지 않는 경우. */
	INVALID_MISSION_ANSWER(400, "MISSION-002", "미션 답변 형식이 올바르지 않습니다."),
	/** 제출한 미션이 없는 경우. */
	MISSION_NOT_FOUND(404, "MISSION-003", "미션을 찾을 수 없습니다."),
	/** 제출을 받는 시각이 미션의 운영 기간 밖이거나 운영 중이 아닌 경우. */
	MISSION_NOT_OPEN(409, "MISSION-004", "제출할 수 있는 기간이 아닙니다."),
	/** 같은 제출 ID로 다른 사용자·미션·답변이 온 경우. */
	IDEMPOTENCY_CONFLICT(409, "MISSION-005", "같은 요청 키로 다른 내용의 제출이 접수되어 있습니다."),
	/** 관리자 미션 API를 활성 관리자가 아닌 사용자가 호출했다. */
	ADMIN_REQUIRED(403, "MISSION-006", "관리자만 미션을 관리할 수 있습니다.");

	private final int status;
	private final String code;
	private final String message;
}
