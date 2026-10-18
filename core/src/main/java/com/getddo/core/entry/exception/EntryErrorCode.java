package com.getddo.core.entry.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.getddo.core.common.exception.ErrorCode;

/**
 * 이벤트 응모 도메인 오류.
 *
 * <p>상수 이름은 공용 스펙(응모 API)의 업무 오류 이름과 같고, 자격 조회(E07)의 {@code reasons}에도 이 이름을 그대로 쓴다.
 * 등급별 보유량이 모자란 경우는 응모권 도메인의 {@code TICKET-006}을 그대로 전파한다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum EntryErrorCode implements ErrorCode {
	/** 등급별 장수가 음수이거나 이벤트 유형이 허용하지 않는 응모권을 담은 요청. */
	INVALID_ENTRY_REQUEST(400, "ENTRY-001", "응모 요청이 올바르지 않습니다."),
	/** 관리자는 이벤트에 응모할 수 없다. */
	ADMIN_ENTRY_FORBIDDEN(403, "ENTRY-002", "관리자는 이벤트에 응모할 수 없습니다."),
	/** 사용자의 멤버십 등급이 이벤트의 최소 허용 등급보다 낮다. */
	ENTRY_MEMBERSHIP_NOT_MET(403, "ENTRY-003", "이벤트에 응모할 수 있는 멤버십 등급이 아닙니다."),
	/** 이벤트가 없거나 삭제됐다. */
	EVENT_NOT_FOUND(404, "ENTRY-004", "이벤트를 찾을 수 없습니다."),
	/** 시작 전이거나 마감됐거나 취소·종료되어 응모를 받지 않는 이벤트. */
	EVENT_NOT_OPEN(409, "ENTRY-005", "응모할 수 있는 기간이 아닙니다."),
	/** 사용자당 한 번만 응모할 수 있는 이벤트에 이미 응모했다. */
	ALREADY_ENTERED(409, "ENTRY-006", "이미 응모한 이벤트입니다."),
	/** 이벤트별 누적 사용 상한을 넘는 요청. */
	TICKET_LIMIT_EXCEEDED(409, "ENTRY-007", "이벤트에서 사용할 수 있는 응모권 수량을 초과했습니다."),
	/** 가중치를 적용하지 않는 이벤트는 브론즈 응모권만 쓸 수 있는데 사용할 수 있는 브론즈가 없다. */
	BRONZE_REQUIRED(409, "ENTRY-008", "이 이벤트는 브론즈 응모권으로만 응모할 수 있습니다."),
	/** 이미 접수된 응모 ID로 다른 이벤트·사용자·응모권 구성의 요청이 왔다. */
	IDEMPOTENCY_CONFLICT(409, "ENTRY-009", "같은 응모 요청 키로 다른 내용의 요청을 보낼 수 없습니다."),
	/** 관리자 응모 조회(AO06·AO07)를 활성 관리자가 아닌 사용자가 호출했다. */
	ADMIN_REQUIRED(403, "ENTRY-010", "관리자만 조회할 수 있습니다.");

	private final int status;
	private final String code;
	private final String message;
}
