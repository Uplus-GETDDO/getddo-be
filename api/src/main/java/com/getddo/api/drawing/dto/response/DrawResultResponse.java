package com.getddo.api.drawing.dto.response;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawSelectionResultType;

/** 실제 공개 명단 포함 이력과 당첨 취소 여부를 독립적으로 제공한다. */
public record DrawResultResponse(UUID id, UUID prizeId, Integer prizeRank, int slotNumber, Integer selectionOrder,
		DrawSelectionResultType resultType, UUID candidateId, UUID userId, String userName, boolean wasPublished,
		@JsonProperty("isCanceled") boolean isCanceled) {
	/** 선정 또는 미충원 결과를 API 응답으로 변환한다. */
	public static DrawResultResponse from(DrawInspectionView.Result result, Integer rank, UUID userId,
			Map<UUID, String> names) {
		return new DrawResultResponse(result.getId(), result.getPrizeId(), rank, result.getSlotNumber(),
				result.getSelectionOrder(), result.getResultType(), result.getCandidateId(), userId,
				userId == null ? null : names.get(userId),
				result.isWasPublished(), result.isCanceled());
	}
}
