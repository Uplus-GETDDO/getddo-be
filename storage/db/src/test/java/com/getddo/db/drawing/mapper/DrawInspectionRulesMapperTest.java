package com.getddo.db.drawing.mapper;

import java.util.UUID;
import java.util.List;
import com.getddo.core.drawing.domain.DrawSnapshot;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawInspectionRulesMapperTest {
	private final DrawInspectionRulesMapper mapper = new DrawInspectionRulesMapper();
	private final UUID prizeId = UUID.randomUUID(), originalId = UUID.randomUUID(), resultId = UUID.randomUUID(), userId = UUID.randomUUID();

	@Test
	@DisplayName("기존 버전 1 JSON은 공용 입력 변경 없이 복원한다")
	void readsLegacyInput() {
		// given / when
		DrawInspectionRulesMapper.Decoded decoded = mapper.decode(json(1, ""));
		// then
		assertThat(decoded.rules().version()).isEqualTo(1);
		assertThat(decoded.rules().prizes().getFirst().id()).isEqualTo(prizeId);
		assertThat(decoded.replacementRules()).isNull();
	}

	@Test
	@DisplayName("GD-87 버전 2 JSON의 최초 실행·실제 자리·유지 결과를 손실 없이 읽는다")
	void readsReplacementContract() {
		// given
		String additional = """
			,"originalRunId":"%s", "targetSlots":[{"prizeId":"%s","rank":1,"slotNumber":3}],
			"retainedAwards":[{"resultId":"%s","userId":"%s","prizeId":"%s","slotNumber":1}]
			""".formatted(originalId, prizeId, resultId, userId, prizeId);
		// when
		DrawInspectionRulesMapper.Decoded decoded = mapper.decode(json(2, additional));
		// then
		assertThat(decoded.rules().version()).isEqualTo(2);
		assertThat(decoded.replacementRules().getOriginalRunId()).isEqualTo(originalId);
		assertThat(decoded.replacementRules().getTargetSlots()).singleElement().satisfies(slot -> {
			assertThat(slot.getPrizeId()).isEqualTo(prizeId);
			assertThat(slot.getRank()).isEqualTo(1);
			assertThat(slot.getSlotNumber()).isEqualTo(3);
		});
		assertThat(decoded.replacementRules().getRetainedAwards()).singleElement().satisfies(award -> {
			assertThat(award.getResultId()).isEqualTo(resultId);
			assertThat(award.getUserId()).isEqualTo(userId);
			assertThat(award.getPrizeId()).isEqualTo(prizeId);
			assertThat(award.getSlotNumber()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("손상 JSON·필수 목록 누락·null 항목을 검증 가능한 누락 값으로 복원한다")
	void representsMalformedSnapshots() {
		// given / when / then
		assertThat(mapper.decode("{").rules()).isNull();
		assertThat(mapper.decode("{}").rules()).isNull();
		assertThat(mapper.decode(null).rules()).isNull();
		assertThat(mapper.decode(json(2, ",\"targetSlots\":[null]")).rules()).isNull();
	}

	@Test
	@DisplayName("버전 2의 누락·null 목록은 명시적인 빈 목록으로 가장하지 않는다")
	void missingReplacementListsAreInvalid() {
		// given: 유효한 대상 자리만 있고 유지 목록이 없거나 null인 손상 입력도 포함한다.
		String slots = ",\"originalRunId\":\"%s\",\"targetSlots\":[{\"prizeId\":\"%s\",\"rank\":1,\"slotNumber\":3}]"
				.formatted(originalId, prizeId);
		// when / then
		for (String extra : List.of("", slots, slots + ",\"retainedAwards\":null", ",\"retainedAwards\":[]",
				",\"targetSlots\":null,\"retainedAwards\":[]")) {
			DrawInspectionRulesMapper.Decoded decoded = mapper.decode(json(2, extra));
			assertThat(decoded.rules()).isNull();
			assertThat(decoded.replacementRules()).isNull();
		}
	}

	@Test
	@DisplayName("버전 2의 명시적인 빈 유지 목록은 정상 저장 입력으로 복원한다")
	void explicitEmptyRetainedAwardsRemainValid() {
		// given / when
		String extra = ",\"originalRunId\":\"%s\",\"targetSlots\":[{\"prizeId\":\"%s\",\"rank\":1,\"slotNumber\":3}],\"retainedAwards\":[]"
				.formatted(originalId, prizeId);
		DrawInspectionRulesMapper.Decoded decoded = mapper.decode(json(2, extra));
		// then
		assertThat(decoded.rules().version()).isEqualTo(2);
		assertThat(decoded.replacementRules().getOriginalRunId()).isEqualTo(originalId);
		assertThat(decoded.replacementRules().getTargetSlots()).hasSize(1);
		assertThat(decoded.replacementRules().getRetainedAwards()).isEmpty();
	}

	/** 플래티넘 최초·재추첨 JSON의 가중치와 연결을 복원한다. */
	@Test
	@DisplayName("신규 3·4 버전은 플래티넘 가중치와 재추첨 연결을 복원한다")
	void readsPlatinumRules() {
		// given
		String extra = ",\"platinumWeight\":10";
		String replacement = ",\"originalRunId\":\"%s\",\"targetSlots\":[{\"prizeId\":\"%s\",\"rank\":1,\"slotNumber\":3}],\"retainedAwards\":[]"
				.formatted(originalId, prizeId);
		// when
		DrawInspectionRulesMapper.Decoded initial = mapper.decode(json(3, extra));
		DrawInspectionRulesMapper.Decoded redraw = mapper.decode(json(4, extra + replacement));
		// then
		assertThat(initial.rules().platinumWeight()).isEqualTo(10);
		assertThat(initial.replacementRules()).isNull();
		assertThat(redraw.rules().version()).isEqualTo(4);
		assertThat(redraw.rules().platinumWeight()).isEqualTo(10);
		assertThat(redraw.replacementRules().getOriginalRunId()).isEqualTo(originalId);
		assertThat(redraw.replacementRules().getTargetSlots()).hasSize(1);
		assertThat(mapper.decode(json(3, "")).rules()).isNull();
		assertThat(mapper.decode(json(3, ",\"platinumWeight\":5")).rules()).isNull();
		assertThat(mapper.decode(json(1, extra)).rules()).isNull();
		assertThat(mapper.decode(json(4, extra)).rules()).isNull();
		assertThat(mapper.decode(json(99, extra)).rules()).isNull();
	}

	/** 실행용 직접 역직렬화도 옛 JSON의 누락 필드를 0으로 복원하며 새 형식은 손실 없이 왕복한다. */
	@Test
	@DisplayName("과거 규칙·근거 JSON은 플래티넘 0으로 복원하고 신규 형식은 왕복한다")
	void roundTripsCurrentAndReadsMissingLegacyFields() {
		// given
		JsonMapper json = JsonMapper.builder().build();
		String legacyEvidence = "{\"version\":1,\"bronzeCount\":1,\"silverCount\":0,\"goldCount\":0,\"entries\":[]}";
		// when
		DrawSnapshot.Rules legacy = json.readValue(json(1, ""), DrawSnapshot.Rules.class);
		DrawSnapshot.EntryEvidence old = json.readValue(legacyEvidence, DrawSnapshot.EntryEvidence.class);
		DrawSnapshot.Rules current = mapper.decode(json(3, ",\"platinumWeight\":10")).rules();
		DrawSnapshot.EntryEvidence evidence = new DrawSnapshot.EntryEvidence(2, 0, 0, 0, 3L, List.of());
		// then
		assertThat(legacy.version()).isEqualTo(1);
		assertThat(legacy.platinumWeight()).isZero();
		assertThat(old.version()).isEqualTo(1);
		assertThat(old.platinumCount()).isZero();
		assertThatThrownBy(() -> json.readValue(legacyEvidence.replace("\"version\":1", "\"version\":2"),
				DrawSnapshot.EntryEvidence.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
		assertThat(json.readValue(json.writeValueAsString(current), DrawSnapshot.Rules.class)).isEqualTo(current);
		assertThat(json.readValue(json.writeValueAsString(evidence), DrawSnapshot.EntryEvidence.class)).isEqualTo(evidence);
	}

	private String json(int version, String extra) {
		return """
			{"version":%d,"weightingEnabled":false,"eventType":"NO_TICKET","membershipRule":"ALL",
			"bronzeWeight":1,"silverWeight":3,"goldWeight":5,"prizes":[{"id":"%s","rank":1,"winnerCount":3}]%s}
			""".formatted(version, prizeId, extra);
	}
}
