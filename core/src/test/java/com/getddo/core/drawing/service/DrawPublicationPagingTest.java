package com.getddo.core.drawing.service;

import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.repository.DrawResultRepository;
import com.getddo.core.drawing.repository.DrawSnapshotRepository;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

class DrawPublicationPagingTest {
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private final UUID event = UUID.randomUUID(), actor = UUID.randomUUID(), prize = UUID.randomUUID();
	private final DrawPublicationRepository repository = mock(DrawPublicationRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final DrawPublicationService publications = new DrawPublicationService(repository,
			mock(DrawSnapshotRepository.class), mock(DrawResultRepository.class), new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), mock(DrawPublicationNotificationService.class), mock(com.getddo.core.event.service.EventStatusChangeService.class));

	/** 페이지 밖의 직전 버전을 포함해 현재 페이지의 실제 변경 전후를 비교한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("페이지 밖의 직전 버전을 포함해 현재 페이지의 실제 변경 전후를 비교한다")
	void comparesBoundaryVersionAndBatchesSlots() {
		// given
		DrawPublication first = publication(1), second = publication(2), third = publication(3);
		DrawPublication.Slot original = slot(), middle = slot(), latest = slot();
		PageQuery query = new PageQuery(1, 1);
		when(repository.findHistory(event, query)).thenReturn(new PageResult<>(List.of(third), 1, 1, 3));
		when(repository.findPrevious(event, 3)).thenReturn(Optional.of(second));
		when(repository.findPublishedSlots(Set.of(third.getId(), second.getId()))).thenReturn(Map.of(third.getId(), List.of(latest), second.getId(), List.of(middle)));
		DrawPublication.Execution currentEvidence = new DrawPublication.Execution(UUID.randomUUID(), UUID.randomUUID(), actor, NOW, List.of());
		DrawPublication.Execution boundaryEvidence = new DrawPublication.Execution(UUID.randomUUID(), UUID.randomUUID(), actor, NOW.minusSeconds(1), List.of());
		when(repository.findExecutionEvidence(Set.of(third.getId(), second.getId()))).thenReturn(
				Map.of(third.getId(), List.of(currentEvidence), second.getId(), List.of(boundaryEvidence)));
		// when
		PageResult<DrawPublicationChange> changes = publications.changes(event, query);
		// then
		assertThat(changes.getTotalElements()).isEqualTo(3);
		assertThat(changes.getItems().getFirst().getExecutions()).containsExactly(currentEvidence);
		verify(repository).findExecutionEvidence(Set.of(third.getId(), second.getId()));
		assertThat(changes.getItems().getFirst().getChanges()).containsExactly(new DrawPublicationChange.SlotChange(prize,
				1, middle.getResultId(), middle.getUserId(), latest.getResultId(), latest.getUserId()));
		verify(repository, never()).findHistory(event);
		verify(repository, never()).findPublishedSlots(any(UUID.class));
		// 최초 버전은 페이지 위치와 관계없이 이전 사용자 null을 반환한다.
		PageQuery lastPage = new PageQuery(3, 1);
		when(repository.findHistory(event, lastPage)).thenReturn(new PageResult<>(List.of(first), 3, 1, 3));
		when(repository.findPrevious(event, 1)).thenReturn(Optional.empty());
		when(repository.findPublishedSlots(Set.of(first.getId()))).thenReturn(Map.of(first.getId(), List.of(original)));
		assertThat(publications.changes(event, lastPage).getItems().getFirst().getChanges()).containsExactly(
				new DrawPublicationChange.SlotChange(prize, 1, null, null, original.getResultId(), original.getUserId()));
	}

	/** 조회 범위 밖의 페이지는 전체 건수를 유지하고 불필요한 이전 버전 조회를 하지 않는다는 시나리오를 검증한다. */
	@Test
	@DisplayName("조회 범위 밖의 페이지는 전체 건수를 유지하고 불필요한 이전 버전 조회를 하지 않는다")
	void returnsEmptyPageWithTotal() {
		// given
		PageQuery query = new PageQuery(Integer.MAX_VALUE, 20);
		when(repository.findHistory(event, query)).thenReturn(new PageResult<>(List.of(), query.getPage(), 20, 5));
		// when / then
		PageResult<DrawPublicationChange> result = publications.changes(event, query);
		assertThat(result.getItems()).isEmpty();
		assertThat(result.getTotalElements()).isEqualTo(5);
		verify(repository, never()).findPrevious(any(), anyInt());
		verify(repository, never()).findPublishedSlots(anySet());
	}

	/** 관리자 이력 조회는 권한 확인 뒤 이벤트 존재를 확인하고 없는 이벤트는 404로 거절한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("관리자 이력 조회는 권한 확인 뒤 이벤트 존재를 확인하고 없는 이벤트는 404로 거절한다")
	void validatesAdminBeforeEventAndPages() {
		// given
		DrawPublicationQueryService queryService = new DrawPublicationQueryService(publications, repository, new DrawAdminAuthorization(users));
		PageQuery query = new PageQuery(1, 20);
		when(users.findById(actor)).thenReturn(Optional.of(user(UserRole.USER, UserStatus.ACTIVE)));
		// when / then
		assertThatThrownBy(() -> queryService.changes(event, actor, query)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode().getStatus()).isEqualTo(403);
		verifyNoInteractions(repository);
		when(users.findById(actor)).thenReturn(Optional.of(user(UserRole.ADMIN, UserStatus.INACTIVE)));
		assertThatThrownBy(() -> queryService.changes(event, actor, query)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(repository);
		when(users.findById(actor)).thenReturn(Optional.of(user(UserRole.ADMIN, UserStatus.ACTIVE)));
		when(repository.eventExists(event)).thenReturn(false);
		assertThatThrownBy(() -> queryService.changes(event, actor, query)).isInstanceOf(BusinessException.class)
				.extracting(error -> ((BusinessException) error).getErrorCode().getStatus()).isEqualTo(404);
		when(repository.eventExists(event)).thenReturn(true);
		when(repository.findHistory(event, query)).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		assertThat(queryService.changes(event, actor, query).getItems()).isEmpty();
	}

	/** 시험에 필요한 공개 버전 메타데이터와 포함 결과를 구성한다. */
	private DrawPublication publication(int revision) {
		return new DrawPublication(UUID.randomUUID(), event, UUID.randomUUID(), revision,
				revision == 1 ? DrawPublicationType.INITIAL : DrawPublicationType.UPDATE,
				revision == 1 ? null : "사유", revision == 1 ? null : actor, revision == 1 ? null : NOW, NOW, List.of());
	}
	/** 시험용 경품 자리의 실제 공개 결과와 사용자 값을 구성한다. */
	private DrawPublication.Slot slot() { return new DrawPublication.Slot(prize, 1, UUID.randomUUID(), UUID.randomUUID()); }
	/** 지정 역할과 상태를 가진 시험용 사용자 문맥을 구성한다. */
	private User user(UserRole role, UserStatus status) { return new User(actor, "조회 사용자", role, status, Membership.VIP, null, null, null, null, NOW, NOW); }
}
