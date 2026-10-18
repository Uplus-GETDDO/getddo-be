package com.getddo.core.event.domain;

import java.time.LocalDate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 관리자 목록 검색 입력. 모집 기간·등록일의 각 날짜 범위는 양끝을 포함하는 KST 날짜다. */
@Getter
@RequiredArgsConstructor
public final class AdminEventQuery {
	private final EventStatus status;
	private final EventType eventType;
	private final String keyword;
	private final LocalDate fromDate;
	private final LocalDate toDate;
	private final Boolean weightingEnabled;
	private final LocalDate createdFromDate;
	private final LocalDate createdToDate;
	private final String sort;

	public AdminEventQuery(EventStatus status, EventType eventType, String keyword,
			LocalDate fromDate, LocalDate toDate) {
		this(status, eventType, keyword, fromDate, toDate, null, null, null, null);
	}
}
