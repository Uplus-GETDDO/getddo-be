package com.getddo.core.event.domain;

import java.util.Arrays;

import com.getddo.core.event.exception.EventErrorCode;
import com.getddo.core.event.exception.EventException;

/** 관리자 목록에서 허용한 단일 정렬 조건. 원본 입력을 SQL 정렬식으로 사용하지 않는다. */
public enum EventSort {
	CREATED_AT_DESC("createdAt,desc"),
	CREATED_AT_ASC("createdAt,asc"),
	STARTS_AT_DESC("startsAt,desc"),
	STARTS_AT_ASC("startsAt,asc"),
	ENDS_AT_DESC("endsAt,desc"),
	ENDS_AT_ASC("endsAt,asc"),
	TITLE_DESC("title,desc"),
	TITLE_ASC("title,asc");

	private final String queryValue;

	EventSort(String queryValue) {
		this.queryValue = queryValue;
	}

	/** 생략 시 기존 최신 등록순을 유지하며, 알 수 없는 컬럼·방향은 업무 입력 오류다. */
	public static EventSort fromQuery(String value) {
		if (value == null) {
			return CREATED_AT_DESC;
		}
		return Arrays.stream(values()).filter(sort -> sort.queryValue.equals(value))
				.findFirst().orElseThrow(() -> new EventException(EventErrorCode.INVALID_QUERY));
	}
}
