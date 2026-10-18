package com.getddo.core.drawing.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.AwardCancellation;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.domain.DrawSnapshot;
import com.getddo.core.drawing.domain.DrawSnapshotSource;
import com.getddo.core.drawing.domain.DrawType;
import com.getddo.core.drawing.repository.AwardCancellationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.drawing.repository.ReplacementDrawRepository;

import static com.getddo.core.drawing.exception.DrawingErrorCode.ATTEMPT_LOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DrawReplacementInputPreparationTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private final UUID run = UUID.randomUUID(), event = UUID.randomUUID(), origin = UUID.randomUUID(), attempt = UUID.randomUUID();
	private final UUID prize = UUID.randomUUID();
	private final DrawSnapshotRepository snapshots = mock(DrawSnapshotRepository.class);
	private final DrawResultRepository results = mock(DrawResultRepository.class);
	private final AwardCancellationRepository cancellations = mock(AwardCancellationRepository.class);
	private final ReplacementDrawRepository replacements = mock(ReplacementDrawRepository.class);
	private final DrawReplacementInputPreparation preparation = new DrawReplacementInputPreparation(snapshots, results,
			cancellations, replacements, new ReplacementDrawInputBuilder(), new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	@DisplayName("재추첨은 최초 규칙에 맞는 2·4 버전과 최초 후보·가중치를 보존한다")
	void storesMatchingVersionWithOriginalCandidates(int version) {
		// given
		DrawSnapshot.Candidate canceled = candidate(9), retained = candidate(25), remaining = candidate(3);
		DrawResult canceledResult = result(canceled, 2), retainedResult = result(retained, 1);
		DrawSnapshot original = new DrawSnapshot(origin, event, DrawRunStatus.CONFIRMED, NOW, DrawSnapshotService.ALGORITHM_VERSION,
				new DrawSnapshot.Rules(version, true, "TICKET", "vip", 1, 3, 5, version == 3 ? 10 : 0,
						List.of(new DrawSnapshotSource.Prize(prize, 1, 2))),
				List.of(canceled, retained, remaining));
		when(snapshots.findRegistered(run)).thenReturn(Optional.of(new DrawSnapshot(run, event, DrawRunStatus.PREPARING,
				null, null, null, List.of(), DrawType.REPLACEMENT)));
		when(snapshots.findInitial(event)).thenReturn(Optional.of(original));
		when(cancellations.findByReplacementRunId(run)).thenReturn(List.of(
				new AwardCancellation(UUID.randomUUID(), canceledResult.getId(), run, UUID.randomUUID(), "사유", NOW)));
		when(replacements.findCanceledResults(event, original)).thenReturn(List.of(canceledResult));
		when(replacements.findCurrentResults(event, original)).thenReturn(List.of(retainedResult));
		when(snapshots.saveRegisteredReplacement(any())).thenAnswer(invocation -> invocation.getArgument(0));
		// when
		DrawSnapshot prepared = preparation.prepare(run, attempt);
		// then
		assertThat(prepared.drawType()).isEqualTo(DrawType.REPLACEMENT);
		assertThat(prepared.status()).isEqualTo(DrawRunStatus.READY);
		assertThat(prepared.candidates()).containsExactly(remaining);
		assertThat(prepared.rules().version()).isEqualTo(version == 3 ? 4 : 2);
		assertThat(prepared.rules().platinumWeight()).isEqualTo(original.rules().platinumWeight());
		assertThat(prepared.algorithmVersion()).isEqualTo(original.algorithmVersion());
		assertThat(prepared.rules().originalRunId()).isEqualTo(origin);
		assertThat(prepared.rules().targetSlots()).containsExactly(new DrawSnapshot.Slot(prize, 1, 2));
		assertThat(prepared.rules().retainedAwards()).containsExactly(new DrawSnapshot.RetainedAward(
				retainedResult.getId(), retained.userId(), prize, 1));
		verify(results).validateAttempt(run, attempt);
	}

	@Test @DisplayName("입력 고정 뒤 재요청은 과거 입력을 반환하고 현재 근거를 재조회하지 않는다")
	void fixedInputIsNotRebuilt() {
		// given
		DrawSnapshot fixed = new DrawSnapshot(run, event, DrawRunStatus.READY, NOW, "v1", null, List.of(), DrawType.REPLACEMENT);
		when(snapshots.findRegistered(run)).thenReturn(Optional.of(fixed));
		// when / then
		assertThat(preparation.prepare(run, attempt)).isSameAs(fixed);
		verifyNoInteractions(cancellations, replacements);
		verify(snapshots, never()).saveRegisteredReplacement(any());
	}

	@Test @DisplayName("이전 시도의 늦은 준비 호출은 입력 저장 전에 거절한다")
	void rejectsLostAttempt() {
		// given
		doThrow(new BusinessException(ATTEMPT_LOST)).when(results).validateAttempt(run, attempt);
		// when / then
		assertThatThrownBy(() -> preparation.prepare(run, attempt)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(snapshots, cancellations, replacements);
	}

	private DrawSnapshot.Candidate candidate(long weight) {
		UUID user = UUID.randomUUID();
		return new DrawSnapshot.Candidate(UUID.randomUUID(), UUID.randomUUID(), user, 1, weight,
				new DrawSnapshot.EntryEvidence(1, 0, 1, 0, List.of()),
				new DrawSnapshot.EligibilityEvidence(1, user, "VIP", "USER", false, NOW));
	}
	private DrawResult result(DrawSnapshot.Candidate candidate, int slot) {
		return new DrawResult(UUID.randomUUID(), new DrawSelection(prize, 1, slot, slot,
				candidate.id(), candidate.userId(), DrawSelectionResultType.SELECTED), NOW);
	}
}
