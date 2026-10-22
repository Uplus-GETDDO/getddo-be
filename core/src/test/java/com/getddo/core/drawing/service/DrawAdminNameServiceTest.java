package com.getddo.core.drawing.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawReplacementRulesView;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.user.repository.UserNameRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DrawAdminNameServiceTest {
	private final UserNameRepository repository = mock(UserNameRepository.class);
	private final DrawAdminNameService service = new DrawAdminNameService(repository);

	@Test
	@DisplayName("당첨자·유지 당첨자·취소 처리자의 중복과 null을 제거해 한 번 조회한다")
	void detailCollectsWinnersRetainedWinnersAndCancelersOnce() {
		// given
		UUID winner = UUID.randomUUID();
		UUID retained = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		DrawInspectionView.Run run = mock(DrawInspectionView.Run.class);
		DrawInspectionView.Result selected = mock(DrawInspectionView.Result.class);
		DrawInspectionView.Result unfilled = mock(DrawInspectionView.Result.class);
		when(selected.getUserId()).thenReturn(winner);
		when(run.getReplacementRules()).thenReturn(new DrawReplacementRulesView(UUID.randomUUID(), List.of(),
				List.of(new DrawReplacementRulesView.RetainedAward(UUID.randomUUID(), retained, UUID.randomUUID(), 1))));
		DrawInspectionView.Cancellation cancellation = new DrawInspectionView.Cancellation(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), actor, "취소", null);
		DrawInspectionView.Detail detail = new DrawInspectionView.Detail(run, List.of(selected, selected, unfilled),
				new DrawInspectionView.ExecutionSummary(0, null, null, false, List.of(cancellation)));
		Map<UUID, String> names = Map.of(winner, "당첨자", retained, "유지 당첨자", actor, "관리자");
		when(repository.findNamesByIds(Set.of(winner, retained, actor))).thenReturn(names);

		// when
		Map<UUID, String> result = service.forDetail(detail);

		// then
		assertThat(result).isEqualTo(names);
		verify(repository).findNamesByIds(Set.of(winner, retained, actor));
		verifyNoMoreInteractions(repository);
		assertThat(detail.getResults()).containsExactly(selected, selected, unfilled);
		assertThat(detail.getRun()).isSameAs(run);
	}

	@Test
	@DisplayName("빈 페이지·시스템 처리·미확정 실행은 이름 저장소를 조회하지 않는다")
	void emptyPagesSystemActorsAndUnconfirmedExecutionsDoNotQueryUsers() {
		// given
		DrawInspectionView.Audit system = new DrawInspectionView.Audit(
				UUID.randomUUID(), null, "DRAW_EXECUTE", null, "{}", "{}", null, null);
		// when / then
		assertThat(service.forAudits(List.of(system))).isEmpty();
		assertThat(service.forCandidates(List.of())).isEmpty();
		assertThat(service.forVerifications(List.of())).isEmpty();
		assertThat(service.forChanges(List.of())).isEmpty();
		assertThat(service.forActor(null)).isEmpty();
		assertThat(service.forExecution(new DrawExecutionResult(UUID.randomUUID(), DrawRunStatus.READY, null))).isEmpty();
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("이름 변경 후 재조회하면 이전 값을 캐시하지 않고 현재 이름을 반환한다")
	void namesAreReadAgainAfterRenameWithoutCachingOldValues() {
		// given
		UUID actor = UUID.randomUUID();
		when(repository.findNamesByIds(Set.of(actor)))
				.thenReturn(Map.of(actor, "변경 전"), Map.of(actor, "변경 후"));
		// when
		Map<UUID, String> before = service.forActor(actor);
		Map<UUID, String> after = service.forActor(actor);

		// then
		assertThat(before).containsEntry(actor, "변경 전");
		assertThat(after).containsEntry(actor, "변경 후");
	}
}
