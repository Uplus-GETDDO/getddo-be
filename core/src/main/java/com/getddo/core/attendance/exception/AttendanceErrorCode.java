package com.getddo.core.attendance.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum AttendanceErrorCode implements ErrorCode {
	/**
	 * 출석 시각에 적용할 일일 보상 정책이나 그 달의 연속 출석 정책 묶음이 없는 경우.
	 * 사용자가 해결할 수 없는 운영 설정 누락이라 서버 오류로 응답한다.
	 */
	ATTENDANCE_POLICY_NOT_FOUND(500, "ATTENDANCE-001", "출석 보상 정책이 설정되지 않았습니다."),
	/** 관리자 출석 정책 API를 활성 관리자가 아닌 사용자가 호출했다. */
	ADMIN_REQUIRED(403, "ATTENDANCE-002", "관리자만 출석 정책을 관리할 수 있습니다."),
	/** 보상 수량이 1 미만이거나 연속 출석 단계가 비었거나 범위(1~28)·순서·중복 규칙을 어겼다. */
	INVALID_POLICY(400, "ATTENDANCE-003", "출석 정책 값이 올바르지 않습니다."),
	/** 다음 일일 기준일에 적용할 일일 출석 정책이 이미 예약돼 있다. */
	DAILY_POLICY_ALREADY_SCHEDULED(409, "ATTENDANCE-004", "다음 적용일에 이미 예약된 일일 출석 정책이 있습니다."),
	/** 다음 기준월에 적용할 연속 출석 정책이 이미 예약돼 있다. */
	STREAK_POLICY_ALREADY_SCHEDULED(409, "ATTENDANCE-005", "다음 적용월에 이미 예약된 연속 출석 정책이 있습니다.");

	private final int status;
	private final String code;
	private final String message;
}
