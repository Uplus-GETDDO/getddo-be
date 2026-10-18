package com.getddo.core.drawing.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.ReplacementDrawInput;

import static com.getddo.core.drawing.exception.DrawingErrorCode.INVALID_RUN;

/** 최초 추첨의 후보와 조건에서 유지 당첨자를 빼고, 취소로 빈 자리의 재추첨 입력을 만든다. */
@Component
public class ReplacementDrawInputBuilder {
	/**
	 * 호출자는 이벤트 선잠금 아래 최초 확정 입력, 현재 유지 당첨, 모든 과거 취소 당첨을 제공한다.
	 * 이번 실행의 대상은 canceledResult 한 자리이며 다른 실행의 미충원 자리를 함께 채우지 않는다.
	 * 최초 당첨자와 이전 재추첨 당첨자도 유지·취소 목록에서 제거되어 다시 후보가 되지 않는다.
	 */
	public ReplacementDrawInput build(DrawSnapshot original, DrawResult canceledResult,
			List<DrawResult> retainedResults, List<DrawResult> canceledResults) {
		if (original.status() != DrawRunStatus.CONFIRMED || original.fixedAt() == null
				|| original.rules() == null || !original.rules().initialFormat()) invalid();
		Map<UUID, DrawSnapshot.Candidate> candidates = new HashMap<>();
		Set<UUID> users = new HashSet<>();
		for (DrawSnapshot.Candidate candidate : original.candidates()) {
			if (candidate.id() == null || candidate.userId() == null || candidate.weight() <= 0
					|| candidate.ticketCount() < 0 || candidate.eligibilityEvidence() == null
					|| candidate.eligibilityEvidence().excluded()
					|| !candidate.userId().equals(candidate.eligibilityEvidence().userId())
					|| (!original.rules().weightingEnabled() && candidate.weight() != 1)
					|| candidates.put(candidate.id(), candidate) != null || !users.add(candidate.userId())) invalid();
		}
		Map<UUID, DrawSnapshotSource.Prize> prizes = new HashMap<>();
		for (DrawSnapshotSource.Prize prize : original.rules().prizes()) {
			if (prize.rank() <= 0 || prize.winnerCount() <= 0 || prizes.put(prize.id(), prize) != null) invalid();
		}
		Set<UUID> excludedUsers = new HashSet<>();
		Set<UUID> resultIds = new HashSet<>();
		Set<DrawSnapshot.Slot> retainedSlots = new HashSet<>();
		for (DrawResult result : retainedResults) {
			validateResult(result, candidates, prizes);
			DrawSelection selection = result.getSelection();
			if (!excludedUsers.add(selection.getUserId()) || !resultIds.add(result.getId())
					|| !retainedSlots.add(slot(selection))) invalid();
		}
		boolean targetFound = false;
		for (DrawResult result : canceledResults) {
			validateResult(result, candidates, prizes);
			if (!resultIds.add(result.getId()) || !excludedUsers.add(result.getSelection().getUserId())) invalid();
			if (result.getId().equals(canceledResult.getId())) {
				if (!result.getSelection().equals(canceledResult.getSelection())) invalid();
				targetFound = true;
			}
		}
		validateResult(canceledResult, candidates, prizes);
		DrawSnapshot.Slot vacant = slot(canceledResult.getSelection());
		if (!targetFound || retainedSlots.contains(vacant)) invalid();
		List<DrawSnapshot.Candidate> remaining = new ArrayList<>();
		for (DrawSnapshot.Candidate candidate : original.candidates()) {
			if (!excludedUsers.contains(candidate.userId())) remaining.add(candidate);
		}
		// 빈 후보도 유효 입력이다. UNFILLED 저장은 GD-87 공통 확정 기능에서 처리한다.
		return new ReplacementDrawInput(original.runId(), original.rules(), remaining,
				List.of(vacant), retainedResults);
	}

	private static void validateResult(DrawResult result, Map<UUID, DrawSnapshot.Candidate> candidates,
			Map<UUID, DrawSnapshotSource.Prize> prizes) {
		DrawSelection selection = result.getSelection();
		DrawSnapshot.Candidate candidate = candidates.get(selection.getCandidateId());
		DrawSnapshotSource.Prize prize = prizes.get(selection.getPrizeId());
		if (selection.getResultType() != DrawSelectionResultType.SELECTED || candidate == null || prize == null
				|| !candidate.userId().equals(selection.getUserId()) || selection.getRank() != prize.rank()
				|| selection.getSlotNumber() > prize.winnerCount()) invalid();
	}

	private static DrawSnapshot.Slot slot(DrawSelection selection) {
		return new DrawSnapshot.Slot(selection.getPrizeId(), selection.getRank(), selection.getSlotNumber());
	}

	private static void invalid() {
		throw new BusinessException(INVALID_RUN);
	}
}
