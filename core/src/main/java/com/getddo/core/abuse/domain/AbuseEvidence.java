package com.getddo.core.abuse.domain;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/** 현재 설정과 분리하여 최초 차단 당시 구간·관측값·임계값을 보존하는 근거다. */
@Getter
public final class AbuseEvidence {

	private final String ruleVersion;
	private final AbuseTriggerScope triggerScope;
	private final Instant windowStartedAt;
	private final Instant windowEndsAt;
	private final int requestLimit;
	private final int rejectedCount;
	private final int rejectionThreshold;

	/** 유효한 집계 구간과 실제 임계값에 도달한 관측값만 복원한다. */
	public AbuseEvidence(String ruleVersion, AbuseTriggerScope triggerScope, Instant windowStartedAt,
			Instant windowEndsAt, int requestLimit, int rejectedCount, int rejectionThreshold) {
		if (ruleVersion == null || ruleVersion.isBlank()) {
			throw new IllegalArgumentException("ruleVersion must not be blank");
		}
		Objects.requireNonNull(triggerScope, "triggerScope");
		Objects.requireNonNull(windowStartedAt, "windowStartedAt");
		Objects.requireNonNull(windowEndsAt, "windowEndsAt");
		if (!windowStartedAt.isBefore(windowEndsAt) || requestLimit < 1 || rejectionThreshold < 1
				|| rejectedCount < rejectionThreshold) {
			throw new IllegalArgumentException("Invalid abuse evidence window or counts");
		}
		this.ruleVersion = ruleVersion;
		this.triggerScope = triggerScope;
		this.windowStartedAt = windowStartedAt;
		this.windowEndsAt = windowEndsAt;
		this.requestLimit = requestLimit;
		this.rejectedCount = rejectedCount;
		this.rejectionThreshold = rejectionThreshold;
	}
}
