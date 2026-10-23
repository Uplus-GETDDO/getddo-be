package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawVerification;

public record DrawVerificationResponse(UUID id, UUID drawRunId, boolean passed, List<Item> checks,
		Instant verifiedAt, UUID verifiedBy, String verifiedByName) {
	public DrawVerificationResponse {
		checks = List.copyOf(checks);
	}
	/** 추첨 정합성 검사 결과를 API 응답으로 변환한다. */
	public static DrawVerificationResponse from(DrawVerification verification, Map<UUID, String> names) {
		return new DrawVerificationResponse(verification.getId(), verification.getDrawRunId(), verification.isPassed(),
				verification.getChecks().stream().map(item -> new Item(item.getCode(), item.isPassed(),
				item.getMessage())).toList(),
				verification.getVerifiedAt(), verification.getVerifiedBy(),
				verification.getVerifiedBy() == null ? null : names.get(verification.getVerifiedBy()));
	}
	public record Item(String code, boolean passed, String message) {
	}
}
