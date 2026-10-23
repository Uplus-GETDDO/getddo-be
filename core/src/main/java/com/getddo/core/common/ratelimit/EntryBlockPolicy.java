package com.getddo.core.common.ratelimit;

import java.time.Duration;

import lombok.Getter;

/** 메모리·Redis 구현에 동일하게 전달하는 응모 차단 기준이다. */
@Getter
public final class EntryBlockPolicy {
	private final int userThreshold;
	private final int eventThreshold;
	private final Duration duration;
	private final String ruleVersion;

	/** 차단 임계값·기간·규칙 버전을 검증하고 최초 설정을 보관한다. */
	public EntryBlockPolicy(int userThreshold, int eventThreshold, Duration duration, String ruleVersion) {
		if (userThreshold < 1 || eventThreshold < 1 || duration == null || duration.toMillis() < 1
				|| ruleVersion == null || ruleVersion.isBlank()) {
			throw new IllegalArgumentException("차단 임계값·기간은 양수이고 근거 버전이 필요하다.");
		}
		this.userThreshold = userThreshold;
		this.eventThreshold = eventThreshold;
		this.duration = duration;
		this.ruleVersion = ruleVersion;
	}

	/** 일반 집계 구간이 차단보다 오래 남는 설정을 거절한다. */
	public void validateWindow(Duration window) {
		if (window.toMillis() < 1 || window.compareTo(duration) > 0) {
			throw new IllegalArgumentException("응모 집계 구간은 1ms 이상이고 차단 기간 이하여야 한다.");
		}
	}
}
