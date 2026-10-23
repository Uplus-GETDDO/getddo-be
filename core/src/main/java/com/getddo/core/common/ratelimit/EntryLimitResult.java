package com.getddo.core.common.ratelimit;

/** 저장소와 호출자가 공유하는 응모 제한 판정이다. */
public enum EntryLimitResult {
	ALLOWED, RATE_LIMITED, BLOCKED
}
