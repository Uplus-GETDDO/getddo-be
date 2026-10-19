package com.getddo.api.drawing.dto.response;

import java.util.List;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawReplacementRulesView;
import com.getddo.core.drawing.domain.DrawSnapshot;

public record DrawRulesResponse(int version, boolean weightingEnabled, String eventType, String membershipRule,
		int bronzeWeight, int silverWeight, int goldWeight, int platinumWeight, List<Prize> prizes,
		UUID originalRunId, List<Slot> targetSlots, List<RetainedAward> retainedAwards) {
	public DrawRulesResponse {
		prizes = List.copyOf(prizes);
		targetSlots = List.copyOf(targetSlots);
		retainedAwards = List.copyOf(retainedAwards);
	}
	/** 저장된 추첨 조건를 API 응답으로 변환한다. */
	public static DrawRulesResponse from(DrawInspectionView.Run run) {
		DrawSnapshot.Rules rules = run.getRules();
		DrawReplacementRulesView replacement = run.getReplacementRules();
		return rules == null ? null : new DrawRulesResponse(rules.version(), rules.weightingEnabled(),
				rules.eventType(),
				rules.membershipRule(), rules.bronzeWeight(), rules.silverWeight(), rules.goldWeight(), rules.platinumWeight(),
				rules.prizes().stream().map(prize -> new Prize(prize.id(), prize.rank(), prize.winnerCount())).toList(),
				replacement == null ? null : replacement.getOriginalRunId(),
				replacement == null ? List.of() : replacement.getTargetSlots().stream()
				.map(slot -> new Slot(slot.getPrizeId(), slot.getRank(), slot.getSlotNumber())).toList(),
				replacement == null ? List.of() : replacement.getRetainedAwards().stream()
				.map(award -> new RetainedAward(award.getResultId(), award.getUserId(), award.getPrizeId(),
				award.getSlotNumber())).toList());
	}
	public record Prize(UUID id, int rank, int winnerCount) {
	}
	public record Slot(UUID prizeId, int rank, int slotNumber) {
	}
	public record RetainedAward(UUID resultId, UUID userId, UUID prizeId, int slotNumber) {
	}
}
