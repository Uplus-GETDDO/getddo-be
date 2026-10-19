package com.getddo.core.drawing.service;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.AwardCancellation;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.domain.ReplacementDrawInput;
import com.getddo.core.drawing.repository.AwardCancellationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.drawing.repository.ReplacementDrawRepository;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;

/** 취소로 빈 자리의 재추첨 입력을 준비한다. 공통 실행 서비스가 확보한 잠금과 트랜잭션을 사용한다. */
@Service
@RequiredArgsConstructor
public class DrawReplacementInputPreparation implements DrawInputPreparation {
	private final DrawSnapshotRepository snapshots;
	private final DrawResultRepository results;
	private final AwardCancellationRepository cancellations;
	private final ReplacementDrawRepository replacements;
	private final ReplacementDrawInputBuilder builder;
	private final TimeProvider time;

	@Override public boolean supports(DrawType type) {
		return type == DrawType.REPLACEMENT;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public DrawSnapshot prepare(UUID runId, UUID attemptId) {
		if (attemptId == null) throw new BusinessException(INVALID_RUN);
		results.validateAttempt(runId, attemptId);
		DrawSnapshot registered = snapshots.findRegistered(runId).orElseThrow(() -> new BusinessException(INVALID_RUN));
		if (registered.drawType() != DrawType.REPLACEMENT) throw new BusinessException(INVALID_RUN);
		if (registered.fixedAt() != null) return registered;
		DrawSnapshot original = snapshots.findInitial(registered.eventId()).orElseThrow(() -> new BusinessException(INVALID_RUN));
		List<DrawResult> canceled = replacements.findCanceledResults(registered.eventId(), original);
		List<AwardCancellation> linked = cancellations.findByReplacementRunId(runId);
		// 개별 취소 한 건이 실행 하나를 등록한다. 일괄 취소 기능을 추가하지 않는다.
		if (linked.size() != 1) throw new BusinessException(INVALID_RUN);
		DrawResult target = canceled.stream().filter(result -> result.getId().equals(linked.getFirst().drawResultId()))
				.findFirst().orElseThrow(() -> new BusinessException(INVALID_RUN));
		ReplacementDrawInput input = builder.build(original, target,
				replacements.findCurrentResults(registered.eventId(), original), canceled);
		DrawSnapshot.Rules rules = input.originalRules();
		DrawSnapshot.Rules fixedRules = new DrawSnapshot.Rules(rules.supportsPlatinum() ? 4 : 2, rules.weightingEnabled(),
				rules.eventType(), rules.membershipRule(),
				rules.bronzeWeight(), rules.silverWeight(), rules.goldWeight(), rules.platinumWeight(), rules.prizes(), input.originalRunId(),
				input.vacantSlots(),
				input.retainedResults().stream().map(result -> new DrawSnapshot.RetainedAward(result.getId(),
				result.getSelection().getUserId(), result.getSelection().getPrizeId(), result.getSelection().getSlotNumber())).toList());
		return snapshots.saveRegisteredReplacement(new DrawSnapshot(runId, registered.eventId(), DrawRunStatus.READY,
				time.now(), original.algorithmVersion(), fixedRules, input.candidates(), DrawType.REPLACEMENT));
	}
}
