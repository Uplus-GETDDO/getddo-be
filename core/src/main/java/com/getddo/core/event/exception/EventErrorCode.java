package com.getddo.core.event.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {

	INVALID_DETAILS(400, "EVENT-001", "이벤트 정보가 올바르지 않습니다."),
	INVALID_PERIOD(400, "EVENT-002", "이벤트 기간이 올바르지 않습니다."),
	INVALID_CONFIGURATION(400, "EVENT-003", "이벤트 응모 조건이 올바르지 않습니다."),
	INVALID_PRIZES(400, "EVENT-004", "경품 구성이 올바르지 않습니다."),
	USER_CONTEXT_REQUIRED(401, "EVENT-005", "등록된 사용자 정보가 필요합니다."),
	ADMIN_REQUIRED(403, "EVENT-006", "관리자만 이벤트를 등록·수정·삭제·취소할 수 있습니다."),
	EVENT_NOT_FOUND(404, "EVENT-007", "이벤트를 찾을 수 없습니다."),
	INVALID_QUERY(400, "EVENT-008", "이벤트 조회 조건이 올바르지 않습니다."),
	MEMBERSHIP_MISMATCH(409, "EVENT-009", "선택한 멤버십이 사용자 정보와 일치하지 않습니다."),
	ACCESS_DENIED(403, "EVENT-010", "이벤트 조회 권한이 없습니다."),
	MODIFICATION_NOT_ALLOWED(409, "EVENT-011", "시작 전 진행 예정 이벤트만 수정할 수 있습니다."),
	START_CANNOT_ADVANCE(409, "EVENT-012", "이벤트 시작 시각을 앞당길 수 없습니다."),
	DELETION_NOT_ALLOWED(409, "EVENT-013", "시작 전이며 응모 이력이 없는 이벤트만 삭제할 수 있습니다."),
	STATUS_CHANGE_CONFLICT(409, "EVENT-014", "현재 이벤트 상태에서 요청한 상태 변경을 수행할 수 없습니다."),
	INVALID_STATUS_CHANGE(400, "EVENT-015", "이벤트 상태 변경 요청이 올바르지 않습니다."),
	INVALID_CANCELLATION_REASON(400, "EVENT-016", "이벤트 취소 사유가 올바르지 않습니다."),
	ALREADY_CANCELED(409, "EVENT-017", "이미 취소된 이벤트입니다."),
	INVALID_IMAGE(400, "EVENT-018", "5MB 이하의 JPG·PNG 이미지 파일이 필요합니다."),
	IMAGE_STORAGE_UNAVAILABLE(503, "EVENT-019", "이미지를 업로드할 수 없습니다. 잠시 후 다시 시도해 주세요.");

	private final int status;
	private final String code;
	private final String message;
}
