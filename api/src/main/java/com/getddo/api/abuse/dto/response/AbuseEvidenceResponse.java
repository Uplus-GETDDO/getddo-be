package com.getddo.api.abuse.dto.response;

import java.time.Instant;

import com.getddo.core.abuse.domain.AbuseEvidence;

/** 관리자 상세에서 허용하는 최초 판정 근거만 응답한다. */
public record AbuseEvidenceResponse(String ruleVersion, String triggerScope, Instant windowStartedAt,
		Instant windowEndsAt, int requestLimit, int rejectedCount, int rejectionThreshold) {

	/** 저장된 범위·관측값·임계값을 바꾸지 않고 UTC instant로 제공한다. */
	public static AbuseEvidenceResponse from(AbuseEvidence evidence) {
		return new AbuseEvidenceResponse(evidence.getRuleVersion(), evidence.getTriggerScope().name(),
				evidence.getWindowStartedAt(), evidence.getWindowEndsAt(), evidence.getRequestLimit(),
				evidence.getRejectedCount(), evidence.getRejectionThreshold());
	}
}
