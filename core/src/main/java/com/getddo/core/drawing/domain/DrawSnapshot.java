package com.getddo.core.drawing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** READY 이후 재요청은 이 저장된 값만 반환한다. JSON의 버전은 알고리즘 버전과 별개다. */
public record DrawSnapshot(UUID runId, UUID eventId, DrawRunStatus status, Instant fixedAt,
		String algorithmVersion, Rules rules, List<Candidate> candidates, DrawType drawType) {
	/** 기존 최초 실행 생성 호출과 호환된다. */
	public DrawSnapshot(UUID runId, UUID eventId, DrawRunStatus status, Instant fixedAt,
			String algorithmVersion, Rules rules, List<Candidate> candidates) {
		this(runId, eventId, status, fixedAt, algorithmVersion, rules, candidates, DrawType.INITIAL);
	}

	/** 후보 목록을 복사해 외부 컬렉션 변경이 확정 입력에 영향을 주지 않게 한다. */
	public DrawSnapshot {
		Objects.requireNonNull(drawType, "drawType");
		candidates = List.copyOf(candidates);
	}

	/** 실행 당시 이벤트 유형·멤버십·등급별 가중치와 경품 조건을 보존한다. */
	public record Rules(int version, boolean weightingEnabled, String eventType, String membershipRule,
			int bronzeWeight, int silverWeight, int goldWeight, Integer platinumWeight, List<DrawSnapshotSource.Prize> prizes,
			UUID originalRunId, List<Slot> targetSlots, List<RetainedAward> retainedAwards) {
		/** 재추첨 확장 필드 없이 규칙을 생성해 기존 최초 입력 호출과 저장 형식의 호환성을 유지한다. */
		public Rules(int version, boolean weightingEnabled, String eventType, String membershipRule,
				int bronzeWeight, int silverWeight, int goldWeight, List<DrawSnapshotSource.Prize> prizes) {
			this(version, weightingEnabled, eventType, membershipRule, bronzeWeight, silverWeight, goldWeight, 0,
					prizes, null, List.of(), List.of());
		}

		/** 플래티넘을 모르는 기존 최초·재추첨 생성자는 당시 가중치 0을 보존한다. */
		public Rules(int version, boolean weightingEnabled, String eventType, String membershipRule,
				int bronzeWeight, int silverWeight, int goldWeight, List<DrawSnapshotSource.Prize> prizes,
				UUID originalRunId, List<Slot> targetSlots, List<RetainedAward> retainedAwards) {
			this(version, weightingEnabled, eventType, membershipRule, bronzeWeight, silverWeight, goldWeight, 0,
					prizes, originalRunId, targetSlots, retainedAwards);
		}

		/** 신규 최초 입력은 플래티넘 장당 가중치를 명시한다. */
		public Rules(int version, boolean weightingEnabled, String eventType, String membershipRule,
				int bronzeWeight, int silverWeight, int goldWeight, int platinumWeight, List<DrawSnapshotSource.Prize> prizes) {
			this(version, weightingEnabled, eventType, membershipRule, bronzeWeight, silverWeight, goldWeight,
					platinumWeight, prizes, null, List.of(), List.of());
		}

		/** 경품 조건 목록을 변경 불가능한 값으로 보존한다. */
		public Rules {
			// Jackson 3은 누락된 primitive 필드를 거절한다. 옛 버전에서만 없는 새 필드를 0으로 복원한다.
			if (platinumWeight == null && (version == 1 || version == 2)) platinumWeight = 0;
			if (platinumWeight == null) throw new IllegalArgumentException("플래티넘 가중치가 필요합니다.");
			prizes = List.copyOf(prizes);
			targetSlots = targetSlots == null ? List.of() : List.copyOf(targetSlots);
			retainedAwards = retainedAwards == null ? List.of() : List.copyOf(retainedAwards);
			if (version < 1 || version > 4) throw new IllegalArgumentException("지원하지 않는 추첨 규칙 버전입니다.");
			if (platinumWeight != (version >= 3 ? 10 : 0)) {
				throw new IllegalArgumentException("규칙 버전과 플래티넘 가중치가 일치하지 않습니다.");
			}
			if ((version == 1 || version == 3) && (originalRunId != null || !targetSlots.isEmpty() || !retainedAwards.isEmpty())) {
				throw new IllegalArgumentException("최초 규칙에는 재추첨 연결 정보를 넣을 수 없습니다.");
			}
			if ((version == 2 || version == 4) && (originalRunId == null || targetSlots.isEmpty())) {
				throw new IllegalArgumentException("재추첨 규칙에는 최초 실행과 대상 자리가 필요합니다.");
			}
		}

		/** 최초 입력은 기존 1과 플래티넘 확장 3을 지원한다. */
		public boolean initialFormat() {
			return version == 1 || version == 3;
		}

		/** 재추첨 입력은 최초 규칙에 따라 기존 2 또는 플래티넘 확장 4를 유지한다. */
		public boolean replacementFormat() {
			return version == 2 || version == 4;
		}

		/** 기존 스냅샷에는 새 등급의 의미를 소급 부여하지 않는다. */
		public boolean supportsPlatinum() {
			return version == 3 || version == 4;
		}
	}

	/** 재추첨의 실제 경품 자리. 전체 경품 조건과 별도로 보존한다. */
	public record Slot(UUID prizeId, int rank, int slotNumber) {
	}

	/** 입력 고정 시 유지할 배정의 불변 식별자와 사용자·자리. */
	public record RetainedAward(UUID resultId, UUID userId, UUID prizeId, int slotNumber) {
	}

	/** 실제 사용 장수와 선정 가중치를 구분하고 응모·자격 근거를 함께 보존한 후보다. */
	public record Candidate(UUID id, UUID participantId, UUID userId, long ticketCount, long weight,
			EntryEvidence entryEvidence, EligibilityEvidence eligibilityEvidence) {
	}

	/** 확정 가중치의 근거인 등급별 실제 차감량과 정상 접수된 응모를 보존한다. */
	public record EntryEvidence(int version, long bronzeCount, long silverCount, long goldCount, Long platinumCount,
			List<DrawSnapshotSource.Entry> entries) {
		/** 기존 3등급 근거 생성 호출은 플래티넘 0장으로 복원한다. */
		public EntryEvidence(int version, long bronzeCount, long silverCount, long goldCount,
				List<DrawSnapshotSource.Entry> entries) {
			this(version, bronzeCount, silverCount, goldCount, 0L, entries);
		}

		/** 응모 근거 목록을 변경 불가능한 값으로 보존한다. */
		public EntryEvidence {
			if (platinumCount == null && version == 1) platinumCount = 0L;
			Objects.requireNonNull(platinumCount, "platinumCount");
			entries = List.copyOf(entries);
		}
	}

	/** excluded는 과거 JSON 호환용이며 새 후보에는 false를 저장한다. */
	public record EligibilityEvidence(int version, UUID userId, String membership, String role,
			boolean excluded, Instant checkedAt) {
	}
}
