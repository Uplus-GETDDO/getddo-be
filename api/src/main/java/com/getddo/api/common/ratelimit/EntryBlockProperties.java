package com.getddo.api.common.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 응모 자동 차단의 거절 임계값·기간·근거 버전을 운영 설정으로 관리한다. */
@ConfigurationProperties("getddo.rate-limit.entry-block")
public record EntryBlockProperties(
		@DefaultValue("20") int userThreshold,
		@DefaultValue("10") int eventThreshold,
		@DefaultValue("10m") Duration duration,
		@DefaultValue("entry-v1") String ruleVersion) {

	public EntryBlockProperties {
		if (userThreshold < 1 || eventThreshold < 1 || duration == null || duration.toMillis() < 1
				|| ruleVersion == null || ruleVersion.isBlank()) {
			throw new IllegalArgumentException("차단 임계값·기간은 양수이고 근거 버전이 필요하다.");
		}
	}

	/** 일반 제한 구간보다 먼저 차단이 끝나 과거 집계가 재사용되는 설정을 거절한다. */
	public void validateWindow(Duration window) {
		if (window.toMillis() < 1 || window.compareTo(duration) > 0) {
			throw new IllegalArgumentException("응모 집계 구간은 1ms 이상이고 차단 기간 이하여야 한다.");
		}
	}
}
