package com.getddo.db.drawing.mapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.getddo.core.drawing.domain.DrawReplacementRulesView;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;

/** 기존 버전 1/2와 플래티넘 버전 3/4의 저장 JSON을 조회용 값으로 복원한다. 공용 실행 Mapper는 변경하지 않는다. */
@Component
public class DrawInspectionRulesMapper {
	private final JsonMapper json = JsonMapper.builder().build();

	/** 저장 JSON의 버전별 전체 조건과 재추첨 연결을 복원하고 손상된 입력은 누락 근거로 반환한다. */
	public Decoded decode(String value) {
		if (value == null) return new Decoded(null, null);
		try {
			StoredRules stored = json.readValue(value, StoredRules.class);
			if (stored == null || stored.prizes() == null || stored.prizes().stream().anyMatch(Objects::isNull)
					|| ((stored.version() == 2 || stored.version() == 4) && (stored.targetSlots() == null || stored.retainedAwards() == null))
					|| (stored.targetSlots() != null && stored.targetSlots().stream().anyMatch(Objects::isNull))
					|| (stored.retainedAwards() != null && stored.retainedAwards().stream().anyMatch(Objects::isNull))) {
				return new Decoded(null, null);
			}
			DrawSnapshot.Rules base = new DrawSnapshot.Rules(stored.version(), stored.weightingEnabled(),
					stored.eventType(),
					stored.membershipRule(), stored.bronzeWeight(), stored.silverWeight(), stored.goldWeight(), stored.platinumWeight(), stored.prizes(),
					stored.originalRunId(), stored.targetSlots() == null ? List.of() : stored.targetSlots().stream()
					.map(slot -> new DrawSnapshot.Slot(slot.prizeId(), slot.rank(), slot.slotNumber())).toList(),
					stored.retainedAwards() == null ? List.of() : stored.retainedAwards().stream()
					.map(award -> new DrawSnapshot.RetainedAward(award.resultId(), award.userId(),
					award.prizeId(), award.slotNumber())).toList());
			DrawReplacementRulesView replacement = !base.replacementFormat() ? null : new DrawReplacementRulesView(stored.originalRunId(),

					stored.targetSlots().stream()
					.map(slot -> new DrawReplacementRulesView.Slot(slot.prizeId(), slot.rank(), slot.slotNumber())).toList(),
					stored.retainedAwards().stream()
					.map(award -> new DrawReplacementRulesView.RetainedAward(award.resultId(), award.userId(),
					award.prizeId(), award.slotNumber())).toList());
			return new Decoded(base, replacement);
		} catch (JacksonException | IllegalArgumentException invalidSnapshot) {
			return new Decoded(null, null);
		}
	}

	public record Decoded(DrawSnapshot.Rules rules, DrawReplacementRulesView replacementRules) {
	}
	private record StoredRules(int version, boolean weightingEnabled, String eventType, String membershipRule,
			int bronzeWeight, int silverWeight, int goldWeight, Integer platinumWeight, List<DrawSnapshotSource.Prize> prizes,
			UUID originalRunId, List<StoredSlot> targetSlots, List<StoredAward> retainedAwards) {
	}
	private record StoredSlot(UUID prizeId, int rank, int slotNumber) {
	}
	private record StoredAward(UUID resultId, UUID userId, UUID prizeId, int slotNumber) {
	}
}
