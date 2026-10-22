package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawInspectionView;

/** 관리자 실행 상세에 포함하는 연결 취소 요약. */
public record DrawCancellationResponse(UUID id, UUID resultId, UUID replacementRunId, UUID canceledBy, String canceledByName,
		String reason, Instant canceledAt) {
	/** 내부 취소 조회값을 HTTP 응답으로 변환한다. */
	public static DrawCancellationResponse from(DrawInspectionView.Cancellation cancellation, Map<UUID, String> names) {
		return new DrawCancellationResponse(cancellation.getId(), cancellation.getResultId(),
				cancellation.getReplacementRunId(),
				cancellation.getCanceledBy(),
				cancellation.getCanceledBy() == null ? null : names.get(cancellation.getCanceledBy()),
				cancellation.getReason(), cancellation.getCanceledAt());
	}
}
