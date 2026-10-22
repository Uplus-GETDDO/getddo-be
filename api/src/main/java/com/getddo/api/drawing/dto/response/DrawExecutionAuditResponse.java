package com.getddo.api.drawing.dto.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawInspectionView;

/** 관리자에게 제공하는 실행 감사 이력. 내부 조회 모델과 HTTP 필드를 분리한다. */
public record DrawExecutionAuditResponse(UUID id, UUID actorId, String actorName, String action, String reason,
		String beforeData, String afterData, String requestId, Instant createdAt) {
	/** 저장된 감사 조회값을 기존 응답 필드로 변환한다. */
	public static DrawExecutionAuditResponse from(DrawInspectionView.Audit audit, Map<UUID, String> names) {
		return new DrawExecutionAuditResponse(audit.getId(), audit.getActorId(),
				audit.getActorId() == null ? null : names.get(audit.getActorId()), audit.getAction(), audit.getReason(),
				audit.getBeforeData(), audit.getAfterData(), audit.getRequestId(), audit.getCreatedAt());
	}
}
