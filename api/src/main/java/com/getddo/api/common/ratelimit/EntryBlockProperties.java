package com.getddo.api.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.getddo.core.common.ratelimit.EntryBlockPolicy;

/** 응모 자동 차단의 거절 임계값·기간·근거 버전을 운영 설정으로 관리한다. */
@ConfigurationProperties("getddo.rate-limit.entry-block")
public record EntryBlockProperties(
		@DefaultValue("20") int userThreshold,
		@DefaultValue("10") int eventThreshold,
		@DefaultValue("10m") Duration duration,
		@DefaultValue("entry-v1") String ruleVersion) {

	public EntryBlockProperties {
		new EntryBlockPolicy(userThreshold, eventThreshold, duration, ruleVersion);
	}

	/** 외부 설정을 순수 차단 기준으로 변환한다. */
	public EntryBlockPolicy toDomain() {
		return new EntryBlockPolicy(userThreshold, eventThreshold, duration, ruleVersion);
	}

	/** 기존 부팅 단계에서 집계 시간과 차단 기간의 관계를 검증한다. */
	public void validateWindow(Duration window) {
		toDomain().validateWindow(window);
	}
}
