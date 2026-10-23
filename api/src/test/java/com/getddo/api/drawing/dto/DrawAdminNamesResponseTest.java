package com.getddo.api.drawing.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.api.drawing.dto.response.DrawExecutionAuditResponse;
import com.getddo.api.drawing.dto.response.DrawExecutionResponse;
import com.getddo.core.drawing.domain.DrawConfirmation;
import com.getddo.core.drawing.domain.DrawExecutionResult;
import com.getddo.core.drawing.domain.DrawInspectionView;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawResult;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.domain.DrawSelection;
import com.getddo.core.drawing.domain.DrawSelectionResultType;
import com.getddo.core.drawing.service.DrawAdminNameService;
import com.getddo.core.user.repository.UserNameRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrawAdminNamesResponseTest {
	@Test
	@DisplayName("공개 변경의 전후 당첨자와 처리자 이름을 각 ID에 맞춰 한 번에 조회한다")
	void publicationPageNamesFollowEachOriginalIdWithOneLookup() {
		// given
		UUID before = UUID.randomUUID();
		UUID after = UUID.randomUUID();
		UUID publisher = UUID.randomUUID();
		UUID confirmer = UUID.randomUUID();
		UUID prize = UUID.randomUUID();
		DrawPublication publication = mock(DrawPublication.class);
		when(publication.getConfirmedBy()).thenReturn(publisher);
		DrawPublication.Execution execution = new DrawPublication.Execution(
				UUID.randomUUID(), UUID.randomUUID(), confirmer, Instant.EPOCH, List.of());
		DrawPublicationChange change = new DrawPublicationChange(publication, List.of(
				new DrawPublicationChange.SlotChange(prize, 1, UUID.randomUUID(), before, UUID.randomUUID(), after),
				new DrawPublicationChange.SlotChange(prize, 2, null, null, UUID.randomUUID(), after)), List.of(execution));
		Map<UUID, String> names = Map.of(before, "이전 당첨자", after, "새 당첨자",
				publisher, "발표 관리자", confirmer, "확인 관리자");
		UserNameRepository users = mock(UserNameRepository.class);
		when(users.findNamesByIds(Set.of(before, after, publisher, confirmer))).thenReturn(names);
		DrawAdminNameService service = new DrawAdminNameService(users);

		// when
		DrawPublicationChangeResponse response = DrawPublicationChangeResponse.from(change, service.forChanges(List.of(change)));

		// then

		assertThat(response.confirmedByName()).isEqualTo("발표 관리자");
		assertThat(response.executions().getFirst().confirmedByName()).isEqualTo("확인 관리자");
		assertThat(response.changes().getFirst().beforeUserName()).isEqualTo("이전 당첨자");
		assertThat(response.changes().getFirst().afterUserName()).isEqualTo("새 당첨자");
		assertThat(response.changes().get(1).beforeUserName()).isNull();
		assertThat(change.getChanges().getFirst().getBeforeUserId()).isEqualTo(before);
		verify(users).findNamesByIds(Set.of(before, after, publisher, confirmer));
	}

	@Test
	@DisplayName("확정 실행의 선정·미충원 결과를 보존하고 현재 이름을 추가한다")
	void confirmedExecutionPreservesSelectedAndUnfilledResultsWhileAddingCurrentNames() {
		// given
		UUID run = UUID.randomUUID();
		UUID user = UUID.randomUUID();
		UUID prize = UUID.randomUUID();
		DrawResult selected = new DrawResult(UUID.randomUUID(), new DrawSelection(
				prize, 1, 1, 1, UUID.randomUUID(), user, DrawSelectionResultType.SELECTED), Instant.EPOCH);
		DrawResult unfilled = new DrawResult(UUID.randomUUID(), new DrawSelection(
				prize, 1, 2, null, null, null, DrawSelectionResultType.UNFILLED), Instant.EPOCH);
		DrawExecutionResult result = new DrawExecutionResult(run, DrawRunStatus.CONFIRMED,
				new DrawConfirmation(run, UUID.randomUUID(), Instant.EPOCH, List.of(selected, unfilled)));
		UserNameRepository users = mock(UserNameRepository.class);
		when(users.findNamesByIds(Set.of(user))).thenReturn(Map.of(user, "당첨자"));
		DrawAdminNameService service = new DrawAdminNameService(users);

		// when
		DrawExecutionResponse response = DrawExecutionResponse.from(result, service.forExecution(result));

		// then

		assertThat(response.confirmation().results().getFirst().userId()).isEqualTo(user);
		assertThat(response.confirmation().results().getFirst().userName()).isEqualTo("당첨자");
		assertThat(response.confirmation().results().get(1).userId()).isNull();
		assertThat(response.confirmation().results().get(1).userName()).isNull();
		assertThat(result.getConfirmation().getResults()).containsExactly(selected, unfilled);
		assertThat(DrawExecutionResponse.from(result, Map.of()).confirmation().results().getFirst().userName()).isNull();
		verify(users).findNamesByIds(Set.of(user));
	}

	@Test
	@DisplayName("처리자가 없는 과거 공개와 시스템 감사는 이름도 null로 유지한다")
	void historicalPublicationAndSystemAuditKeepNullActorNames() {
		// given
		DrawPublication publication = mock(DrawPublication.class);
		DrawPublicationChange change = new DrawPublicationChange(publication, List.of(
				new DrawPublicationChange.SlotChange(UUID.randomUUID(), 1, null, null, null, null)));
		DrawInspectionView.Audit audit = new DrawInspectionView.Audit(
				UUID.randomUUID(), null, "DRAW_EXECUTE", null, "{}", "{}", null, Instant.EPOCH);

		// when
		DrawPublicationChangeResponse response = DrawPublicationChangeResponse.from(change, Map.of());
		DrawPublicationResponse publicationResponse = DrawPublicationResponse.from(publication, Map.of());
		DrawExecutionAuditResponse auditResponse = DrawExecutionAuditResponse.from(audit, Map.of());

		// then
		assertThat(response.confirmedByName()).isNull();
		assertThat(response.changes().getFirst().beforeUserName()).isNull();
		assertThat(response.changes().getFirst().afterUserName()).isNull();
		assertThat(publicationResponse.updatedByName()).isNull();
		assertThat(auditResponse.actorName()).isNull();
		assertThat(auditResponse.beforeData()).isEqualTo("{}");
		assertThat(auditResponse.afterData()).isEqualTo("{}");
	}
}
