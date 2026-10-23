package com.getddo.api.abuse.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.getddo.core.abuse.domain.AbuseHistoryRetryPolicy;

/** 이력 소비의 처리량·임시 소유권·재시도 설정이다. 응모 차단 정책과 분리한다. */
@ConfigurationProperties("getddo.abuse.history-worker")
public record AbuseHistoryWorkerProperties(
		@DefaultValue("20") int batchSize,
		@DefaultValue("100") int scanSize,
		@DefaultValue("30s") Duration lease,
		@DefaultValue("10m") Duration retryBudget,
		@DefaultValue({"10s", "30s", "1m", "2m"}) List<Duration> retryDelays) {
	public AbuseHistoryWorkerProperties {
		if (batchSize < 1 || scanSize < 1 || lease == null || lease.toMillis() < 1) {
			throw new IllegalArgumentException("이력 작업자의 처리량과 소유권 기간은 양수여야 한다.");
		}
		new AbuseHistoryRetryPolicy(retryBudget, retryDelays);
		retryDelays = List.copyOf(retryDelays);
	}
}
