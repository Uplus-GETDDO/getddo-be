package com.getddo.core.drawing.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.getddo.core.drawing.domain.DrawHistoricalAward;
import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawReplacementRulesView;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.domain.DrawVerification;

/** 저장된 입력과 결과를 비교해 수량·가중치·자리 배정·취소 시점이 맞는지 검사한다. 당첨자를 다시 뽑지 않는다. */
public final class DrawStoredVerification {
	private DrawStoredVerification() {
	}

	/** 최초 실행의 기존 호출을 유지한다. 재추첨은 저장된 최초 입력·과거 배정을 함께 전달한다. */
	public static List<DrawVerification.Item> verify(DrawInspectionView.Run run,
			List<DrawInspectionView.Candidate> candidates,
			List<DrawInspectionView.Result> results) {
		return verify(run, candidates, results, DrawVerification.References.empty());
	}

	public static List<DrawVerification.Item> verify(DrawInspectionView.Run run,
			List<DrawInspectionView.Candidate> candidates,
			List<DrawInspectionView.Result> results, DrawVerification.References references) {
		DrawVerification.References stored = references == null ? DrawVerification.References.empty() : references;
		List<DrawVerification.Item> checks = new ArrayList<>();
		boolean replacement = run.getDrawType() == DrawType.REPLACEMENT;
		boolean supported = run.getRules() != null
				&& ((run.getDrawType() == DrawType.INITIAL && run.getRules().version() == 1)
				|| (replacement && run.getRules().version() == 2 && run.getReplacementRules() != null))
				&& DrawSnapshotService.ALGORITHM_VERSION.equals(run.getAlgorithmVersion());
		checks.add(item("SUPPORTED_INPUT", supported, "실행 종류·입력 버전·알고리즘 검증 범위"));
		checks.add(item("FIXED_INPUT", run.getSnapshotFixedAt() != null && run.getConfirmedAt() != null
				&& !run.getConfirmedAt().isBefore(run.getSnapshotFixedAt()), "입력·결과 확정 시각"));
		checks.add(item("CANDIDATE_RELATIONS", relations(run, candidates), "실행·원본 후보·응모자 연결"));
		Instant evidenceFixedAt = replacement && stored.getOriginalRun() != null
		? stored.getOriginalRun().getSnapshotFixedAt() : run.getSnapshotFixedAt();
		checks.add(item("CANDIDATE_EVIDENCE", supported && evidence(run, candidates,
				evidenceFixedAt, replacement), "저장 응모 수량·등급·가중치 근거"));
		if (replacement) {
			checks.add(item("REPLACEMENT_SOURCE", supported && replacementSource(run, candidates,
					stored), "최초 조건·후보·최초 가중치·과거 당첨 후보 제외"));
			checks.add(item("REPLACEMENT_SLOTS", supported && replacementSlots(run, stored), "고정 대상 자리·유지 결과의 원본 배정"));
		}
		checks.add(item("RESULTS", supported && results(run, candidates, results), "저장 경품 자리·선정 순서·후보·사용자 중복"));
		return List.copyOf(checks);
	}

	private static DrawVerification.Item item(String code, boolean passed, String scope) {
		return new DrawVerification.Item(code, passed, scope + (passed ? ": 일치" : ": 불일치 또는 검증 범위 미지원"));
	}

	private static boolean relations(DrawInspectionView.Run run, List<DrawInspectionView.Candidate> candidates) {
		Set<UUID> ids = new HashSet<>(), participants = new HashSet<>(), users = new HashSet<>();
		for (DrawInspectionView.Candidate view : candidates) {
			DrawSnapshot.Candidate candidate = view.getSnapshot();
			if (candidate.id() == null || candidate.participantId() == null || candidate.userId() == null
					|| !ids.add(candidate.id()) || !participants.add(candidate.participantId()) || !users.add(candidate.userId())
					|| !run.getEventId().equals(view.getSourceEventId())
					|| !run.getEventId().equals(view.getParticipantEventId())
					|| !candidate.userId().equals(view.getParticipantUserId())
					|| !Objects.equals(run.getDrawType() == DrawType.INITIAL ? run.getId() : run.getOriginalDrawId(),
					view.getSourceRunId())) return false;
		}
		return true;
	}

	private static boolean evidence(DrawInspectionView.Run run, List<DrawInspectionView.Candidate> candidates,
			Instant evidenceFixedAt, boolean allowEmpty) {
		DrawSnapshot.Rules rules = run.getRules();
		if (evidenceFixedAt == null || rules.bronzeWeight() != 1 || rules.silverWeight() != 3 || rules.goldWeight() != 5
				|| !("TICKET".equals(rules.eventType()) || "NO_TICKET".equals(rules.eventType()))
				|| ("NO_TICKET".equals(rules.eventType()) && rules.weightingEnabled())) return false;
		Set<UUID> entries = new HashSet<>(), histories = new HashSet<>(), tickets = new HashSet<>();
		try {
			for (DrawInspectionView.Candidate view : candidates) {
				DrawSnapshot.Candidate candidate = view.getSnapshot();
				DrawSnapshot.EntryEvidence entry = candidate.entryEvidence();
				DrawSnapshot.EligibilityEvidence eligibility = candidate.eligibilityEvidence();
				if (entry == null || entry.version() != 1 || entry.entries().isEmpty()
						|| eligibility == null || eligibility.version() != 1 || eligibility.checkedAt() == null
						|| !Objects.equals(candidate.userId(), eligibility.userId()) || candidate.ticketCount() < 0) return false;
				long count = 0, bronze = 0, silver = 0, gold = 0;
				for (DrawSnapshotSource.Entry accepted : entry.entries()) {
					if (accepted.id() == null || !entries.add(accepted.id()) || accepted.acceptedAt() == null
							|| accepted.acceptedAt().isAfter(evidenceFixedAt) || accepted.deductedTicketCount() < 0
							|| accepted.uses().size() != accepted.deductedTicketCount()) return false;
					count = Math.addExact(count, accepted.deductedTicketCount());
					for (DrawSnapshotSource.Use use : accepted.uses()) {
						if (use.historyId() == null || use.ticketId() == null || use.grade() == null
								|| !histories.add(use.historyId()) || !tickets.add(use.ticketId())
								|| !Objects.equals(candidate.userId(), use.ownerId())) return false;
						switch (use.grade()) {
							case BRONZE -> bronze = Math.incrementExact(bronze);
							case SILVER -> silver = Math.incrementExact(silver);
							case GOLD -> gold = Math.incrementExact(gold);
							// 플래티넘 가중치(10)와 증거 구조를 지원하기 전까지는 검증을 통과시키지 않는다.
							case PLATINUM -> {
								return false;
							}
						}
					}
				}
				if (count != candidate.ticketCount() || bronze != entry.bronzeCount()
						|| silver != entry.silverCount() || gold != entry.goldCount()) return false;
				if ("NO_TICKET".equals(rules.eventType()) && count != 0) return false;
				if ("TICKET".equals(rules.eventType()) && (count == 0 || (!rules.weightingEnabled() && count != 1))) return false;
				if (!rules.weightingEnabled() && (silver != 0 || gold != 0)) return false;
				if (!rules.weightingEnabled() && entry.entries().size() != 1) return false;
				long weight = rules.weightingEnabled() ? Math.addExact(bronze,
						Math.addExact(Math.multiplyExact(silver, 3), Math.multiplyExact(gold, 5))) : 1;
				if (candidate.weight() != weight || weight < 1) return false;
			}
		} catch (ArithmeticException overflow) {
			return false;
		}
		return allowEmpty || !candidates.isEmpty();
	}

	/** 현재 응모·사용자 상태 대신 최초 저장 조건과 불변 후보 근거를 대조한다. */
	private static boolean replacementSource(DrawInspectionView.Run run, List<DrawInspectionView.Candidate> candidates,
			DrawVerification.References references) {
		DrawInspectionView.Run original = references.getOriginalRun();
		DrawReplacementRulesView replacement = run.getReplacementRules();
		if (run.getRunNumber() < 1 || original == null || replacement == null || run.getSnapshotFixedAt() == null
				|| !Objects.equals(run.getOriginalDrawId(), replacement.getOriginalRunId())
				|| !Objects.equals(original.getId(), replacement.getOriginalRunId())
				|| !run.getEventId().equals(original.getEventId()) || original.getDrawType() != DrawType.INITIAL
				|| original.getRunNumber() != 0 || original.getStatus() != DrawRunStatus.CONFIRMED
				|| original.getConfirmedAt() == null || original.getConfirmedAt().isAfter(run.getSnapshotFixedAt())
				|| !Objects.equals(run.getAlgorithmVersion(), original.getAlgorithmVersion())
				|| original.getRules() == null || original.getRules().version() != 1 || !sameConditions(run.getRules(),
				original.getRules())
				|| !relations(original, references.getOriginalCandidates())
				|| !evidence(original, references.getOriginalCandidates(), original.getSnapshotFixedAt(), false)) return false;
		Map<UUID, DrawSnapshot.Candidate> firstCandidates = new HashMap<>();
		for (DrawInspectionView.Candidate candidate : references.getOriginalCandidates()) {
			firstCandidates.put(candidate.getSnapshot().id(), candidate.getSnapshot());
		}
		Set<UUID> winners = new HashSet<>(), awardIds = new HashSet<>();
		for (DrawHistoricalAward award : references.getHistoricalAwards()) {
			DrawSnapshot.Candidate candidate = firstCandidates.get(award.getCandidateId());
			if (award.getId() == null || !awardIds.add(award.getId()) || award.getRunId() == null || run.getId().equals(award.getRunId())
					|| !run.getEventId().equals(award.getEventId()) || !run.getEventId().equals(award.getPrizeEventId())
					|| award.getConfirmedAt() == null || award.getConfirmedAt().isAfter(run.getSnapshotFixedAt())
					|| award.getRunNumber() < 0 || award.getRunNumber() >= run.getRunNumber()
					|| award.getResultType() == null
				|| (award.getCanceledAt() != null && award.getCanceledAt().isBefore(award.getConfirmedAt()))) return false;
			if (award.getResultType() == DrawSelectionResultType.SELECTED) {
				if (candidate == null || !Objects.equals(candidate.userId(), award.getUserId())) return false;
				winners.add(award.getCandidateId());
			} else if (award.getCandidateId() != null || award.getUserId() != null || award.getCanceledAt() != null) return false;
		}
		Set<UUID> expected = new HashSet<>(firstCandidates.keySet());
		expected.removeAll(winners);
		Set<UUID> actual = new HashSet<>();
		for (DrawInspectionView.Candidate candidate : candidates) {
			if (!candidate.getSnapshot().equals(firstCandidates.get(candidate.getSnapshot().id()))
					|| !actual.add(candidate.getSnapshot().id())) return false;
		}
		return actual.equals(expected);
	}

	private static boolean sameConditions(DrawSnapshot.Rules current, DrawSnapshot.Rules original) {
		return current.weightingEnabled() == original.weightingEnabled() && Objects.equals(current.eventType(),
				original.eventType())
				&& Objects.equals(current.membershipRule(), original.membershipRule()) && current.bronzeWeight() == original.bronzeWeight()
				&& current.silverWeight() == original.silverWeight() && current.goldWeight() == original.goldWeight()
				&& current.prizes().size() == original.prizes().size()
				&& new HashSet<>(current.prizes()).equals(new HashSet<>(original.prizes()));
	}

	/** 고정 시각 이전 최신 자리 배정의 취소·유지 여부와 완전성을 검증한다. 이후 취소는 소급 반영하지 않는다. */
	private static boolean replacementSlots(DrawInspectionView.Run run, DrawVerification.References references) {
		DrawReplacementRulesView replacement = run.getReplacementRules();
		if (replacement == null || replacement.getTargetSlots().isEmpty() || run.getSnapshotFixedAt() == null) return false;
		Map<UUID, DrawSnapshotSource.Prize> prizes = new HashMap<>();
		for (DrawSnapshotSource.Prize prize : run.getRules().prizes()) prizes.put(prize.id(), prize);
		Map<String, DrawHistoricalAward> latest = new HashMap<>();
		Set<String> rounds = new HashSet<>();
		for (DrawHistoricalAward award : references.getHistoricalAwards()) {
			if (award.getConfirmedAt() == null || award.getConfirmedAt().isAfter(run.getSnapshotFixedAt())) continue;
			String key = slotKey(award.getPrizeId(), award.getSlotNumber());
			DrawHistoricalAward previous = latest.get(key);
			if (!rounds.add(key + ":" + award.getRunNumber())) return false;
			if (previous == null || previous.getRunNumber() < award.getRunNumber()) latest.put(key, award);
		}
		Set<String> occupied = new HashSet<>();
		for (DrawReplacementRulesView.Slot slot : replacement.getTargetSlots()) {
			DrawSnapshotSource.Prize prize = prizes.get(slot.getPrizeId());
			String key = slotKey(slot.getPrizeId(), slot.getSlotNumber());
			DrawHistoricalAward award = latest.get(key);
			if (prize == null || slot.getRank() != prize.rank() || slot.getSlotNumber() < 1 || slot.getSlotNumber() > prize.winnerCount()
					|| !occupied.add(key) || award == null || award.getResultType() != DrawSelectionResultType.SELECTED
					|| !canceledAtFixedTime(award, run.getSnapshotFixedAt())) return false;
		}
		Set<UUID> retainedIds = new HashSet<>(), retainedUsers = new HashSet<>();
		for (DrawReplacementRulesView.RetainedAward retained : replacement.getRetainedAwards()) {
			String key = slotKey(retained.getPrizeId(), retained.getSlotNumber());
			DrawHistoricalAward award = latest.get(key);
			DrawSnapshotSource.Prize prize = prizes.get(retained.getPrizeId());
			if (award == null || prize == null || award.getResultType() != DrawSelectionResultType.SELECTED
					|| canceledAtFixedTime(award, run.getSnapshotFixedAt()) || !Objects.equals(retained.getResultId(),
					award.getId())
					|| !retainedIds.add(retained.getResultId()) || retained.getUserId() == null
					|| !retainedUsers.add(retained.getUserId()) || !Objects.equals(retained.getUserId(), award.getUserId())
					|| retained.getSlotNumber() < 1 || retained.getSlotNumber() > prize.winnerCount() || !occupied.add(key)) return false;
		}
		Set<String> expected = new HashSet<>();
		for (Map.Entry<String, DrawHistoricalAward> entry : latest.entrySet()) {
			if (entry.getValue().getResultType() == DrawSelectionResultType.SELECTED) expected.add(entry.getKey());
		}
		// 이미 UNFILLED로 끝난 다른 재추첨 자리를 이번 실행의 대상에 다시 넣지 않는다.
		return occupied.equals(expected);
	}

	/** 고정 시각까지 발생한 취소만 배정 검증에 반영하고 이후 취소는 소급하지 않는다. */
	private static boolean canceledAtFixedTime(DrawHistoricalAward award, Instant fixedAt) {
		return award.getCanceledAt() != null && !award.getCanceledAt().isAfter(fixedAt);
	}

	private static String slotKey(UUID prizeId, int slot) {
		return prizeId + ":" + slot;
	}

	private static boolean results(DrawInspectionView.Run run, List<DrawInspectionView.Candidate> candidates,
			List<DrawInspectionView.Result> stored) {
		Map<UUID, UUID> candidateUsers = new HashMap<>();
		for (DrawInspectionView.Candidate view : candidates) candidateUsers.put(view.getSnapshot().id(),
				view.getSnapshot().userId());
		Map<UUID, DrawSnapshotSource.Prize> prizes = new HashMap<>();
		Set<Integer> ranks = new HashSet<>();
		long slots = 0;
		for (DrawSnapshotSource.Prize prize : run.getRules().prizes()) {
			if (prize.id() == null || prize.rank() < 1 || prize.winnerCount() < 1
					|| prizes.put(prize.id(), prize) != null || !ranks.add(prize.rank())) return false;
			slots += prize.winnerCount();
		}
		if (prizes.isEmpty()) return false;
		boolean replacement = run.getDrawType() == DrawType.REPLACEMENT;
		List<DrawReplacementRulesView.Slot> targets = replacement && run.getReplacementRules() != null
		? new ArrayList<>(run.getReplacementRules().getTargetSlots()) : List.of();
		if (replacement ? targets.isEmpty() || targets.size() != stored.size() : slots != stored.size()) return false;
		if (replacement) targets.sort(Comparator.comparingInt(DrawReplacementRulesView.Slot::getRank)
				.thenComparingInt(DrawReplacementRulesView.Slot::getSlotNumber));
		List<DrawInspectionView.Result> ordered = new ArrayList<>(stored);
		for (DrawInspectionView.Result result : ordered) {
			if (!prizes.containsKey(result.getPrizeId()) || !run.getId().equals(result.getRunId())
					|| !run.getEventId().equals(result.getPrizeEventId())) return false;
		}
		ordered.sort(Comparator.comparingInt((DrawInspectionView.Result result) -> prizes.get(result.getPrizeId()).rank())
				.thenComparingInt(DrawInspectionView.Result::getSlotNumber));
		Set<UUID> resultIds = new HashSet<>(), selected = new HashSet<>(), users = new HashSet<>();
		Map<UUID, Integer> nextSlots = new HashMap<>();
		int order = 0;
		boolean unfilled = false;
		int index = 0;
		for (DrawInspectionView.Result result : ordered) {
			int slot = nextSlots.merge(result.getPrizeId(), 1, Integer::sum);
			if (replacement) {
				DrawReplacementRulesView.Slot target = targets.get(index++);
				if (!Objects.equals(target.getPrizeId(), result.getPrizeId()) || target.getRank() != prizes.get(result.getPrizeId()).rank()) return false;
				slot = target.getSlotNumber();
			}
			if (result.getId() == null || !resultIds.add(result.getId()) || result.getSlotNumber() != slot || slot < 1
					|| slot > prizes.get(result.getPrizeId()).winnerCount()) return false;
			if (result.getResultType() == DrawSelectionResultType.SELECTED) {
				UUID user = candidateUsers.get(result.getCandidateId());
				if (unfilled || user == null || !selected.add(result.getCandidateId()) || !users.add(user)
						|| !Integer.valueOf(++order).equals(result.getSelectionOrder())) return false;
			} else if (result.getResultType() == DrawSelectionResultType.UNFILLED) {
				unfilled = true;
				if (result.getCandidateId() != null || result.getSelectionOrder() != null || result.isCanceled()
						|| result.isWasPublished()) return false;
			} else return false;
		}
		return order == Math.min(candidates.size(), stored.size());
	}
}
