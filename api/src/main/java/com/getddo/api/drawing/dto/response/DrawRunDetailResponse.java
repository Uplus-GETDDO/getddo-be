package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;

/** 실행 및 실패·재시도·취소 근거의 관리자 상세 응답. */
public record DrawRunDetailResponse(UUID id, UUID eventId, int runNumber, DrawType drawType,
		DrawRunStatus status, UUID originalDrawId, Instant startedAt, Instant confirmedAt, Instant createdAt,
		String algorithmVersion, DrawRulesResponse rulesSnapshot, Instant snapshotFixedAt,
		List<DrawResultResponse> results, DrawExecutionSummaryResponse execution) {
	public DrawRunDetailResponse {
		results = List.copyOf(results);
	}

	/** 실행 메타데이터·저장 조건·결과와 운영 요약을 HTTP 전용 응답으로 변환한다. */
	public static DrawRunDetailResponse from(DrawInspectionView.Detail detail, Map<UUID, String> names) {
		DrawInspectionView.Run run = detail.getRun();
		Map<UUID, Integer> ranks = new HashMap<>();
		if (run.getRules() != null) {
			for (DrawSnapshotSource.Prize prize : run.getRules().prizes()) ranks.put(prize.id(), prize.rank());
		}
		return new DrawRunDetailResponse(run.getId(), run.getEventId(), run.getRunNumber(), run.getDrawType(),
				run.getStatus(), run.getOriginalDrawId(), run.getStartedAt(), run.getConfirmedAt(), run.getCreatedAt(),
				run.getAlgorithmVersion(), DrawRulesResponse.from(run, names), run.getSnapshotFixedAt(), detail.getResults().stream()
				.map(result -> DrawResultResponse.from(result, ranks.get(result.getPrizeId()),
				result.getUserId(), names)).toList(), DrawExecutionSummaryResponse.from(detail.getExecution(), names));
	}
}
