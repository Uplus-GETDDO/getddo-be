package com.getddo.api.drawing.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
	void publicationPageNamesFollowEachOriginalIdWithOneLookup() {
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

		DrawPublicationChangeResponse response = DrawPublicationChangeResponse.from(change, service.forChanges(List.of(change)));

		assertThat(response.confirmedByName()).isEqualTo("발표 관리자");
		assertThat(response.executions().getFirst().confirmedByName()).isEqualTo("확인 관리자");
		assertThat(response.changes().getFirst().beforeUserName()).isEqualTo("이전 당첨자");
		assertThat(response.changes().getFirst().afterUserName()).isEqualTo("새 당첨자");
		assertThat(response.changes().get(1).beforeUserName()).isNull();
		assertThat(change.getChanges().getFirst().getBeforeUserId()).isEqualTo(before);
		verify(users).findNamesByIds(Set.of(before, after, publisher, confirmer));
	}

	@Test
	void confirmedExecutionPreservesSelectedAndUnfilledResultsWhileAddingCurrentNames() {
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

		DrawExecutionResponse response = DrawExecutionResponse.from(result, service.forExecution(result));

		assertThat(response.confirmation().results().getFirst().userId()).isEqualTo(user);
		assertThat(response.confirmation().results().getFirst().userName()).isEqualTo("당첨자");
		assertThat(response.confirmation().results().get(1).userId()).isNull();
		assertThat(response.confirmation().results().get(1).userName()).isNull();
		assertThat(result.getConfirmation().getResults()).containsExactly(selected, unfilled);
		assertThat(DrawExecutionResponse.from(result, Map.of()).confirmation().results().getFirst().userName()).isNull();
		verify(users).findNamesByIds(Set.of(user));
	}

	@Test
	void historicalPublicationAndSystemAuditKeepNullActorNames() {
		DrawPublication publication = mock(DrawPublication.class);
		DrawPublicationChange change = new DrawPublicationChange(publication, List.of(
				new DrawPublicationChange.SlotChange(UUID.randomUUID(), 1, null, null, null, null)));
		DrawPublicationChangeResponse response = DrawPublicationChangeResponse.from(change, Map.of());
		assertThat(response.confirmedByName()).isNull();
		assertThat(response.changes().getFirst().beforeUserName()).isNull();
		assertThat(response.changes().getFirst().afterUserName()).isNull();
		assertThat(DrawPublicationResponse.from(publication, Map.of()).updatedByName()).isNull();
		DrawInspectionView.Audit audit = new DrawInspectionView.Audit(
				UUID.randomUUID(), null, "DRAW_EXECUTE", null, "{}", "{}", null, Instant.EPOCH);
		DrawExecutionAuditResponse auditResponse = DrawExecutionAuditResponse.from(audit, Map.of());
		assertThat(auditResponse.actorName()).isNull();
		assertThat(auditResponse.beforeData()).isEqualTo("{}");
		assertThat(auditResponse.afterData()).isEqualTo("{}");
	}
}
