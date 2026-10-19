package com.getddo.core.event.domain;

import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/** 목록의 선택 조건. 기간은 모집 기간과 검색 구간의 겹침 여부로 판정한다. */
@Getter
@EqualsAndHashCode
public final class EventQueryFilter {
	private final EventStatus status;
	private final EventType eventType;
	private final MembershipRule membershipRule;
	private final String keyword;
	/** 포함하는 UTC 하한. 관리자 조회 서비스가 KST 시작 날짜를 자정으로 변환한다. */
	private final Instant from;
	/** 제외하는 UTC 상한. 관리자 조회 서비스가 KST 종료 날짜 다음 날 자정을 변환한다. */
	private final Instant to;
	private final Boolean weightingEnabled;
	/** 등록 시각의 포함 하한과 제외 상한. 모집 기간 조건과 독립적으로 AND 적용한다. */
	private final Instant createdFrom;
	private final Instant createdTo;
	private final EventSort sort;

	public EventQueryFilter(EventStatus status, EventType eventType, MembershipRule membershipRule,
			String keyword, Instant from, Instant to) {
		this(status, eventType, membershipRule, keyword, from, to, null, null, null, EventSort.CREATED_AT_DESC);
	}

	public EventQueryFilter(EventStatus status, EventType eventType, MembershipRule membershipRule,
			String keyword, Instant from, Instant to, Boolean weightingEnabled,
			Instant createdFrom, Instant createdTo, EventSort sort) {
		this.status = status;
		this.eventType = eventType;
		this.membershipRule = membershipRule;
		this.keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
		this.from = from;
		this.to = to;
		this.weightingEnabled = weightingEnabled;
		this.createdFrom = createdFrom;
		this.createdTo = createdTo;
		this.sort = sort == null ? EventSort.CREATED_AT_DESC : sort;
	}
}
