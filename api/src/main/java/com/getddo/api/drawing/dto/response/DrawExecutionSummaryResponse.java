package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawInspectionView;

/** 관리자 실행 상세의 요약. 전체 실패와 감사 이력은 페이징한다. */
public record DrawExecutionSummaryResponse(long failureCount, DrawFailureResponse latestFailure, Instant retryAt,
		boolean waitingForOperator, List<DrawCancellationResponse> cancellations) {
	/** 요약과 연결 취소 목록을 HTTP 전용 응답으로 변환한다. */
	public static DrawExecutionSummaryResponse from(DrawInspectionView.ExecutionSummary summary, Map<UUID, String> names) {
		return summary == null ? null : new DrawExecutionSummaryResponse(summary.getFailureCount(),
				summary.getLatestFailure() == null ? null : DrawFailureResponse.from(summary.getLatestFailure()),
				summary.getRetryAt(), summary.isWaitingForOperator(), summary.getCancellations().stream()
				.map(cancellation -> DrawCancellationResponse.from(cancellation, names)).toList());
	}
}
