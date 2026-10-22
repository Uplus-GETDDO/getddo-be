package com.getddo.core.abuse.domain;

/** 최초 차단 당시 대표로 저장한 거절 집계 범위다. 조회 시 다시 선택하지 않는다. */
public enum AbuseTriggerScope {
	USER,
	EVENT
}
