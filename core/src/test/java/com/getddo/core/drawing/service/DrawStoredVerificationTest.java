package com.getddo.core.drawing.service;

import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.domain.DrawVerification;
import com.getddo.core.ticket.domain.TicketGrade;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class DrawStoredVerificationTest {
	private final UUID runId = UUID.randomUUID(), eventId = UUID.randomUUID(), prizeId = UUID.randomUUID();
	private final UUID candidateId = UUID.randomUUID(), participantId = UUID.randomUUID(), userId = UUID.randomUUID();
	private final Instant fixedAt = Instant.parse("2026-10-12T00:00:00Z");

	@Test
	@DisplayName("저장된 가중치·응모 근거·선정·미충원을 난수 없이 검증한다")
	void verifiesStoredResults() {
		// given
		DrawInspectionView.Run run = run(DrawType.INITIAL);
		// when
		List<DrawVerification.Item> checks = DrawStoredVerification.verify(run, List.of(candidate(1, 5)),
				List.of(result(1, 1, candidateId, true, true), result(2, null, null, false, false)));
		// then: 공개 후 취소된 당첨도 원래 확정 결과의 정합성을 바꾸지 않는다.
		assertThat(checks).allMatch(DrawVerification.Item::isPassed);
	}

	@Test
	@DisplayName("실제 장수와 가중치를 구분하고 변조된 수량·가중치를 거절한다")
	void rejectsWrongWeightAndCount() {
		// given / when / then
		assertThat(checks(List.of(candidate(5, 5)), validResults())).anyMatch(item -> item.getCode().equals("CANDIDATE_EVIDENCE") && !item.isPassed());
		assertThat(checks(List.of(candidate(1, 1)), validResults())).anyMatch(item -> item.getCode().equals("CANDIDATE_EVIDENCE") && !item.isPassed());
	}

	@ParameterizedTest
	@CsvSource({"1, BRONZE", "1, SILVER", "1, GOLD", "1, PLATINUM",
			"3, BRONZE", "3, SILVER", "3, GOLD", "3, PLATINUM"})
	@DisplayName("신규·기존 비가중치 응모권 이벤트는 저장된 브론즈 한 장 근거만 통과한다")
	void unweightedTicketEvidenceRequiresBronze(int rulesVersion, TicketGrade grade) {
		// given
		DrawInspectionView.Run weighted = run(DrawType.INITIAL);
		DrawInspectionView.Run unweighted = new DrawInspectionView.Run(runId, eventId, 0, DrawType.INITIAL, DrawRunStatus.CONFIRMED, null,
				weighted.getAlgorithmVersion(), new DrawSnapshot.Rules(rulesVersion, false, "TICKET", "ALL", 1, 3, 5, rulesVersion == 3 ? 10 : 0,
				weighted.getRules().prizes()), fixedAt, fixedAt, fixedAt, fixedAt);
		DrawSnapshotSource.Use use = new DrawSnapshotSource.Use(UUID.randomUUID(), UUID.randomUUID(), userId, grade);
		DrawSnapshotSource.Entry entry = new DrawSnapshotSource.Entry(UUID.randomUUID(), 1, fixedAt.minusSeconds(300), List.of(use));
		DrawSnapshot.Candidate snapshot = new DrawSnapshot.Candidate(candidateId, participantId, userId, 1, 1,
				new DrawSnapshot.EntryEvidence(rulesVersion == 3 ? 2 : 1, grade == TicketGrade.BRONZE ? 1 : 0,
				grade == TicketGrade.SILVER ? 1 : 0, grade == TicketGrade.GOLD ? 1 : 0,
				grade == TicketGrade.PLATINUM ? 1L : 0L, List.of(entry)),
				new DrawSnapshot.EligibilityEvidence(1, userId, "VIP", "USER", false, fixedAt));
		// when
		List<DrawVerification.Item> checks = DrawStoredVerification.verify(unweighted,
				List.of(new DrawInspectionView.Candidate(snapshot, runId, eventId, eventId, userId)), validResults());
		// then
		assertThat(checks).filteredOn(item -> item.getCode().equals("CANDIDATE_EVIDENCE"))
				.extracting(DrawVerification.Item::isPassed).containsExactly(grade == TicketGrade.BRONZE);
	}

	@Test
	@DisplayName("후보 부족 이전 미충원·중복 자리·잘못된 실행 연결·선정 순서를 거절한다")
	void rejectsInvalidResults() {
		// given / when / then
		assertResultsFail(List.of(result(1, null, null, false, false), result(2, 1, candidateId, false, false)));
		assertResultsFail(List.of(result(1, 1, candidateId, false, false), result(1, null, null, false, false)));
		assertResultsFail(List.of(result(1, 2, candidateId, false, false), result(2, null, null, false, false)));
		assertResultsFail(List.of(new DrawInspectionView.Result(UUID.randomUUID(), UUID.randomUUID(), prizeId, eventId, 1, 1,
				DrawSelectionResultType.SELECTED, candidateId, false, false), result(2, null, null, false, false)));
	}

	@Test
	@DisplayName("중복 사용자 후보·다른 이벤트 후보 연결을 불일치로 기록한다")
	void rejectsCandidateRelations() {
		// given
		DrawInspectionView.Candidate candidate = candidate(1, 5);
		DrawInspectionView.Candidate wrongEvent = new DrawInspectionView.Candidate(candidate.getSnapshot(), runId, UUID.randomUUID(), eventId, userId);
		// when / then
		assertThat(checks(List.of(candidate, candidate), validResults())).anyMatch(item -> item.getCode().equals("CANDIDATE_RELATIONS") && !item.isPassed());
		assertThat(checks(List.of(wrongEvent), validResults())).anyMatch(item -> item.getCode().equals("CANDIDATE_RELATIONS") && !item.isPassed());
	}

	@Test
	@DisplayName("버전 1 입력의 재추첨을 버전 2 검증으로 통과시키지 않는다")
	void replacementWithVersionOneIsUnsupported() {
		// given / when / then
		assertThat(DrawStoredVerification.verify(run(DrawType.REPLACEMENT), List.of(candidate(1, 5)), validResults()))
				.anyMatch(item -> item.getCode().equals("SUPPORTED_INPUT") && !item.isPassed());
	}

	@Test
	@DisplayName("손상된 JSON 복원값과 누락 시각은 예외 원문 대신 불일치가 된다")
	void missingEvidenceAndTimeFailSafely() {
		// given
		DrawSnapshot.Candidate broken = new DrawSnapshot.Candidate(candidateId, participantId, null, 1, 5, null, null);
		DrawInspectionView.Run run = new DrawInspectionView.Run(runId, eventId, 0, DrawType.INITIAL, DrawRunStatus.CONFIRMED,
				null, DrawSnapshotService.ALGORITHM_VERSION, run(DrawType.INITIAL).getRules(), null, null, fixedAt, fixedAt);
		// when / then
		assertThat(DrawStoredVerification.verify(run, List.of(new DrawInspectionView.Candidate(broken, runId, eventId, eventId, userId)), validResults()))
				.anyMatch(item -> !item.isPassed());
	}

	/** 신규 근거의 플래티넘 수량과 가중치를 검증하고 옛 형식으로 위장한 입력을 거절한다. */
	@Test
	@DisplayName("신규 플래티넘 근거는 가중치 10을 검증하며 누락·변조·옛 버전 위장을 거절한다")
	void verifiesPlatinumOnlyForNewEvidence() {
		// given
		DrawInspectionView.Run legacy = run(DrawType.INITIAL);
		DrawInspectionView.Run current = new DrawInspectionView.Run(runId, eventId, 0, DrawType.INITIAL,
				DrawRunStatus.CONFIRMED, null, legacy.getAlgorithmVersion(),
				new DrawSnapshot.Rules(3, true, "TICKET", "ALL", 1, 3, 5, 10, legacy.getRules().prizes()),
				fixedAt, fixedAt, fixedAt, fixedAt);
		DrawSnapshotSource.Use use = new DrawSnapshotSource.Use(UUID.randomUUID(), UUID.randomUUID(), userId, TicketGrade.PLATINUM);
		DrawSnapshotSource.Entry entry = new DrawSnapshotSource.Entry(UUID.randomUUID(), 1, fixedAt.minusSeconds(1), List.of(use));
		DrawSnapshot.EntryEvidence evidence = new DrawSnapshot.EntryEvidence(2, 0, 0, 0, 1L, List.of(entry));
		DrawSnapshot.Candidate base = candidate(1, 5).getSnapshot();
		DrawSnapshot.Candidate valid = new DrawSnapshot.Candidate(candidateId, participantId, userId, 1, 10,
				evidence, base.eligibilityEvidence());
		// when / then
		assertThat(DrawStoredVerification.verify(current, List.of(view(valid)), validResults()))
				.allMatch(DrawVerification.Item::isPassed);
		for (DrawSnapshot.Candidate broken : List.of(
				new DrawSnapshot.Candidate(candidateId, participantId, userId, 1, 5, evidence, base.eligibilityEvidence()),
				new DrawSnapshot.Candidate(candidateId, participantId, userId, 1, 10,
						new DrawSnapshot.EntryEvidence(2, 0, 0, 0, 0L, List.of(entry)), base.eligibilityEvidence()),
				new DrawSnapshot.Candidate(candidateId, participantId, userId, 1, 10,
						new DrawSnapshot.EntryEvidence(1, 0, 0, 0, List.of(entry)), base.eligibilityEvidence()))) {
			assertThat(DrawStoredVerification.verify(current, List.of(view(broken)), validResults()))
					.anyMatch(item -> item.getCode().equals("CANDIDATE_EVIDENCE") && !item.isPassed());
		}
		assertThat(DrawStoredVerification.verify(legacy, List.of(view(valid)), validResults()))
				.anyMatch(item -> item.getCode().equals("CANDIDATE_EVIDENCE") && !item.isPassed());
	}

	/** 후보의 기존 실행·이벤트·사용자 관계를 유지한 검증용 조회값을 만든다. */
	private DrawInspectionView.Candidate view(DrawSnapshot.Candidate candidate) {
		return new DrawInspectionView.Candidate(candidate, runId, eventId, eventId, userId);
	}

	private List<DrawVerification.Item> checks(List<DrawInspectionView.Candidate> candidates, List<DrawInspectionView.Result> results) {
		return DrawStoredVerification.verify(run(DrawType.INITIAL), candidates, results);
	}
	private void assertResultsFail(List<DrawInspectionView.Result> results) {
		assertThat(checks(List.of(candidate(1, 5)), results)).anyMatch(item -> item.getCode().equals("RESULTS") && !item.isPassed());
	}
	private List<DrawInspectionView.Result> validResults() {
		return List.of(result(1, 1, candidateId, false, false), result(2, null, null, false, false));
	}
	private DrawInspectionView.Result result(int slot, Integer order, UUID candidate, boolean published, boolean canceled) {
		return new DrawInspectionView.Result(UUID.randomUUID(), runId, prizeId, eventId, slot, order,
				candidate == null ? DrawSelectionResultType.UNFILLED : DrawSelectionResultType.SELECTED, candidate, published, canceled);
	}
	private DrawInspectionView.Run run(DrawType type) {
		return new DrawInspectionView.Run(runId, eventId, 0, type, DrawRunStatus.CONFIRMED, null,
				DrawSnapshotService.ALGORITHM_VERSION, new DrawSnapshot.Rules(1, true, "TICKET", "ALL", 1, 3, 5,
				List.of(new DrawSnapshotSource.Prize(prizeId, 1, 2))), fixedAt, fixedAt, fixedAt, fixedAt);
	}
	private DrawInspectionView.Candidate candidate(long count, long weight) {
		DrawSnapshotSource.Use use = new DrawSnapshotSource.Use(UUID.randomUUID(), UUID.randomUUID(), userId, TicketGrade.GOLD);
		DrawSnapshotSource.Entry entry = new DrawSnapshotSource.Entry(UUID.randomUUID(), 1, fixedAt.minusSeconds(300), List.of(use));
		DrawSnapshot.Candidate snapshot = new DrawSnapshot.Candidate(candidateId, participantId, userId, count, weight,
				new DrawSnapshot.EntryEvidence(1, 0, 0, 1, List.of(entry)),
				new DrawSnapshot.EligibilityEvidence(1, userId, "VIP", "USER", false, fixedAt));
		return new DrawInspectionView.Candidate(snapshot, runId, eventId, eventId, userId);
	}
}
