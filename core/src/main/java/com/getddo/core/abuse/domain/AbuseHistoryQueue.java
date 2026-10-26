package com.getddo.core.abuse.domain;

/** 일반 저장 재시도와 매일 오전 6시 배치의 대상을 분리한다. */
public enum AbuseHistoryQueue {
	NORMAL, BATCH
}
