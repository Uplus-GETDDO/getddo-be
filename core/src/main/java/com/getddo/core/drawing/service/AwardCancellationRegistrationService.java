package com.getddo.core.drawing.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.audit.domain.AuditLogCommand;
import com.getddo.core.audit.service.AuditLogService;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.AwardCancellation;
import com.getddo.core.drawing.domain.CancellationTarget;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.repository.AwardCancellationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.drawing.repository.ReplacementDrawRepository;
import com.getddo.core.event.domain.EventStatus;

import static com.getddo.core.drawing.exception.AwardCancellationErrorCode.*;
import static com.getddo.core.drawing.exception.DrawingErrorCode.ADMIN_REQUIRED;

/** 개별 당첨 취소와 빈 자리 재추첨 등록을 함께 저장한다. 실제 추첨 실행은 이 트랜잭션이 끝난 뒤 시작한다. */
@Service
@RequiredArgsConstructor
public class AwardCancellationRegistrationService {
	private final AwardCancellationRepository cancellations;
	private final ReplacementDrawRepository replacements;
	private final DrawSnapshotRepository snapshots;
	private final DrawResultRepository results;
	private final DrawAdminAuthorization admins;
	private final AuditLogService audit;
	private final TimeProvider time;

	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public AwardCancellation register(UUID actorId, UUID winId, UUID requestKey, String reason) {
		requireAdmin(actorId);
		validateRequest(winId, requestKey, reason);
		String normalized = reason.strip();
		Optional<AwardCancellation> existing = cancellations.findByRequestKey(requestKey);
		if (existing.isPresent()) return matching(existing.get(), actorId, winId, normalized);
		CancellationTarget target = replacements.findTarget(winId).orElseThrow(() -> new BusinessException(NOT_FOUND));
		DrawSnapshotSource.Event event = snapshots.lockEvent(target.eventId()).orElseThrow(() -> new BusinessException(NOT_FOUND));
		// 이벤트 잠금 대기 뒤 이전 요청이 커밋한 멱등 키를 다시 읽는다.
		existing = cancellations.findByRequestKey(requestKey);
		if (existing.isPresent()) return matching(existing.get(), actorId, winId, normalized);
		DrawSnapshot source = results.lockSnapshot(target.runId()).orElseThrow(() -> new BusinessException(NOT_FOUND));
		Instant now = time.now().truncatedTo(ChronoUnit.MICROS);
		if (event.deleted() || event.status() == EventStatus.CANCELED || event.status() == EventStatus.SCHEDULED
				|| now.isBefore(event.endsAt().plusSeconds(60)) || source.status() != DrawRunStatus.CONFIRMED
				|| !target.eventId().equals(source.eventId()) || target.resultType() != DrawSelectionResultType.SELECTED
				|| cancellations.findByDrawResultId(winId).isPresent()
				|| replacements.hasPendingReplacement(target.eventId())) throw new BusinessException(NOT_CANCELABLE);
		if (results.findConfirmed(source).getResults().stream().noneMatch(result -> result.getId().equals(winId)
				&& result.getSelection().getResultType() == DrawSelectionResultType.SELECTED)) throw new BusinessException(NOT_CANCELABLE);
		UUID runId = replacements.register(target.eventId());
		UUID id = cancellations.nextId();
		AwardCancellation saved = cancellations.save(new AwardCancellation(id, winId,
				runId, actorId, normalized, now, requestKey));
		audit.record(new AuditLogCommand(actorId, "AWARD_CANCEL", "DRAW_RESULT", winId, normalized,
				Map.of("isCanceled", false), Map.of("isCanceled", true, "cancellationId", saved.id().toString(),
				"replacementRunId", runId.toString()), requestKey.toString()));
		replacements.markRedrawing(target.eventId(), now);
		return saved;
	}

	/** 다른 이벤트의 동일 키 INSERT 경합은 등록을 롤백한 뒤 별도 조회로 판정한다. */
	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Optional<AwardCancellation> replay(UUID actorId, UUID winId, UUID requestKey, String reason) {
		requireAdmin(actorId);
		validateRequest(winId, requestKey, reason);
		return cancellations.findByRequestKey(requestKey).map(existing -> matching(existing,
				actorId, winId, reason.strip()));
	}

	private static AwardCancellation matching(AwardCancellation existing, UUID actorId, UUID winId, String reason) {
		if (!existing.canceledBy().equals(actorId) || !existing.drawResultId().equals(winId) || !existing.reason().equals(reason))
		throw new BusinessException(KEY_CONFLICT);
		if (existing.replacementRunId() == null) throw new BusinessException(NOT_CANCELABLE);
		return existing;
	}

	private static void validateRequest(UUID winId, UUID requestKey, String reason) {
		if (winId == null || requestKey == null || reason == null || reason.isBlank() || reason.strip().length() > 1000)
		throw new BusinessException(INVALID_REQUEST);
	}

	/** 현재 DB 역할·활성 상태를 공통 검사로 대조하고 취소 업무의 권한 오류를 유지한다. */
	private void requireAdmin(UUID actorId) {
		admins.requireActiveAdmin(actorId, ADMIN_REQUIRED);
	}
}
