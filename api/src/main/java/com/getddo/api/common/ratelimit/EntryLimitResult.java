package com.getddo.api.common.ratelimit;

/** 응모 평가의 HTTP 변환 기준이다. */
public enum EntryLimitResult {
	ALLOWED, RATE_LIMITED, BLOCKED
}
