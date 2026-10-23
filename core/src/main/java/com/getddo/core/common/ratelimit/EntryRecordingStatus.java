package com.getddo.core.common.ratelimit;

/** 일반 거절 뒤 근거 기록의 성공 여부를 판정과 분리한다. */
public enum EntryRecordingStatus {
	NOT_APPLICABLE, RECORDED, DENY_RECORDING_FAILED
}
