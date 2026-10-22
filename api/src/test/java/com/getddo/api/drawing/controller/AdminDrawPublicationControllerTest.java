package com.getddo.api.drawing.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.drawing.domain.DrawPublication;
import com.getddo.core.drawing.domain.DrawPublicationChange;
import com.getddo.core.drawing.domain.DrawPublicationConfirmation;
import com.getddo.core.drawing.domain.DrawPublicationRun;
import com.getddo.core.drawing.domain.DrawPublicationType;
import com.getddo.core.drawing.domain.DrawResultConfirmation;
import com.getddo.core.drawing.domain.DrawRunStatus;
import com.getddo.core.drawing.repository.DrawPublicationRepository;
import com.getddo.core.drawing.service.DrawAdminAuthorization;
import com.getddo.core.drawing.service.DrawAdminNameService;
import com.getddo.core.drawing.service.DrawPublicationQueryService;
import com.getddo.core.drawing.service.DrawPublicationService;
import com.getddo.core.drawing.service.DrawResultConfirmationService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserNameRepository;
import com.getddo.core.user.repository.UserRepository;
import com.getddo.core.user.service.UserService;

import static com.getddo.core.drawing.exception.DrawPublicationErrorCode.NOT_CONFIRMABLE;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MVC 사용자 문맥·확인/권한 서비스를 사용한다. 공개 계산·저장소는 대역이며 DB 연동 검증은 아니다. */
class AdminDrawPublicationControllerTest {
	private final UserNameRepository userNames = mock(UserNameRepository.class);
	private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
	private final UUID actorId = UUID.randomUUID(), eventId = UUID.randomUUID(), runId = UUID.randomUUID();
	private final DrawPublicationRepository repository = mock(DrawPublicationRepository.class);
	private final DrawPublicationService publications = mock(DrawPublicationService.class);
	private final UserRepository users = mock(UserRepository.class);
	private User actor;
	private DrawResultConfirmation confirmation;
	private MockMvc mvc;

	/** 각 시나리오에 사용할 공개·확인 서비스와 저장·사용자 대역을 준비한다. */
	@BeforeEach
	void setUp() {
		when(userNames.findNamesByIds(anyCollection())).thenAnswer(call -> {
			Collection<UUID> ids = call.getArgument(0);
			return ids.stream().collect(Collectors.toMap(id -> id, id -> "현재 이름"));
		});
		actor = user(UserRole.ADMIN, UserStatus.ACTIVE);
		confirmation = new DrawResultConfirmation(UUID.randomUUID(), runId, actorId, NOW);
		when(users.findById(actorId)).thenAnswer(call -> Optional.of(actor));
		when(repository.findEventId(runId)).thenReturn(Optional.of(eventId));
		when(publications.validateReplacement(eventId, runId)).thenReturn(run(null));
		when(repository.saveConfirmation(runId, actorId, NOW)).thenReturn(confirmation);
		when(publications.publishReviewedInitial(eventId, runId, actorId)).thenReturn(
				new DrawPublicationConfirmation(eventId, confirmation, publication(1)));
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = MockMvcBuilders.standaloneSetup(new AdminDrawPublicationController(new DrawAdminNameService(userNames),
				new DrawResultConfirmationService(repository, publications, new DrawAdminAuthorization(users), new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC))),
				new DrawPublicationQueryService(publications, repository, new DrawAdminAuthorization(users))))
				.setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator)
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(new UserService(users))).build();
	}

	/** 별도 확인 요청 없이 같은 발표 요청을 반복해도 실제 최초 공개 결과를 반환한다. */
	@Test
	@DisplayName("최초 발표 요청 한 번과 재시도는 실제 최초 공개 결과를 반환한다")
	void firstPublicationAndRetryReturnPublishedResult() throws Exception {
		// given
		DrawPublication first = publication(1);
		when(publications.publishReviewedInitial(eventId, runId, actorId)).thenReturn(
				new DrawPublicationConfirmation(eventId, confirmation, first));
		// when / then
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(selected(post("/api/v1/admin/draws/{id}/result-confirmation", runId)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.isPublished").value(true))
					.andExpect(jsonPath("$.data.confirmationId").value(confirmation.getId().toString()))
					.andExpect(jsonPath("$.data.confirmedByName").value("현재 이름"))
					.andExpect(jsonPath("$.data.publicationId").value(first.getId().toString()));
		}
		verify(publications, times(2)).publishReviewedInitial(eventId, runId, actorId);
		verify(repository, never()).saveConfirmation(any(), any(), any());
	}

	/** 확인 완료로 최초 명단이 공개되면 실제 공개 버전을 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("확인 완료로 최초 명단이 공개되면 실제 공개 버전을 반환한다")
	void preInitialConfirmationCanReturnActualFirstPublication() throws Exception {
		// given
		DrawPublication first = publication(1);
		when(publications.publishReviewedInitial(eventId, runId, actorId)).thenReturn(
				new DrawPublicationConfirmation(eventId, confirmation, first));
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/result-confirmation", runId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.isPublished").value(true))
				.andExpect(jsonPath("$.data.publicationId").value(first.getId().toString()))
				.andExpect(jsonPath("$.data.revision").value(1)).andExpect(jsonPath("$.data.publishedAt").value(NOW.toString()));
	}

	/** 최초 공개 전 갱신과 최초 공개 후 신규 사전 확인을 거절한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("최초 공개 전 갱신과 최초 공개 후 신규 사전 확인을 거절한다")
	void publicationRequiresInitialAndPreInitialRouteRejectsNewConfirmationAfterPublication() throws Exception {
		// given
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/publication", runId)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLICATION-005"));
		when(repository.findLatest(eventId)).thenReturn(Optional.of(publication(1)));
		when(publications.publishReviewedInitial(eventId, runId, actorId)).thenThrow(
				new BusinessException(com.getddo.core.drawing.exception.DrawPublicationErrorCode.INVALID_PHASE));
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/result-confirmation", runId)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLICATION-005"));
		verify(repository, never()).saveConfirmation(any(), any(), any());
		verify(publications, never()).publishConfirmed(any(), any());
	}

	/** 공개 후 재요청은 확인을 덮어쓰지 않고 실제 최신 버전을 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("공개 후 재요청은 확인을 덮어쓰지 않고 실제 최신 버전을 반환한다")
	void publishedConfirmationRetryReturnsActualLatestVersionWithoutOverwritingCheck() throws Exception {
		// given
		DrawPublication latest = publication(3);
		when(repository.findLatest(eventId)).thenReturn(Optional.of(latest));
		when(publications.validateReplacement(eventId, runId)).thenReturn(run(confirmation));
		when(publications.publishConfirmed(eventId, runId)).thenReturn(Optional.of(latest));
		for (int attempt = 0; attempt < 2; attempt++) {
			// when / then
			mvc.perform(selected(post("/api/v1/admin/draws/{id}/publication", runId)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data").value(aMapWithSize(8)))
					.andExpect(jsonPath("$.data.publicationId").value(latest.getId().toString()))
					.andExpect(jsonPath("$.data.drawRunId").value(latest.getSourceDrawId().toString()))
					.andExpect(jsonPath("$.data.revision").value(3));
		}
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/result-confirmation", runId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
		verify(repository, never()).saveConfirmation(any(), any(), any());
	}

	/** 최초 공개 후 새 확인은 연결된 최신 공개 메타데이터를 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("최초 공개 후 새 확인은 연결된 최신 공개 메타데이터를 반환한다")
	void firstPostPublicationConfirmationUsesLinkedPublicationMetadata() throws Exception {
		// given
		DrawPublication first = publication(1), latest = publication(2);
		when(repository.findLatest(eventId)).thenReturn(Optional.of(first));
		when(publications.publishConfirmed(eventId, runId)).thenReturn(Optional.of(latest));
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/publication", runId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2))
				.andExpect(jsonPath("$.data.updatedBy").value(actorId.toString()))
				.andExpect(jsonPath("$.data.updatedByName").value("현재 이름"));
		verify(repository).saveConfirmation(runId, actorId, NOW);
		verify(publications).publishConfirmed(eventId, runId);
	}

	/** 실제 공개 변경 이력을 기본 페이지와 UTC 시각으로 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("실제 공개 변경 이력을 기본 페이지와 UTC 시각으로 반환한다")
	void historyReturnsOnlyActualChangesWithDefaultPageMetadataAndUtcTimes() throws Exception {
		// given
		DrawPublication latest = publication(2);
		UUID prize = UUID.randomUUID(), before = UUID.randomUUID(), after = UUID.randomUUID();
		UUID confirmationId = UUID.randomUUID(), cancellationId = UUID.randomUUID(), resultId = UUID.randomUUID();
		DrawPublicationChange change = new DrawPublicationChange(latest, List.of(new DrawPublicationChange.SlotChange(
				prize, 1, UUID.randomUUID(), before, UUID.randomUUID(), after)), List.of(
				new DrawPublication.Execution(runId, confirmationId, actorId, NOW, List.of(
						new DrawPublication.Cancellation(cancellationId, resultId, runId, "실행별 사유")))));
		when(repository.eventExists(eventId)).thenReturn(true);
		when(publications.changes(eventId, new PageQuery(1, 20))).thenReturn(new PageResult<>(List.of(change), 1, 20, 2));
		// when / then
		mvc.perform(selected(get("/api/v1/admin/events/{id}/result-changes", eventId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data").value(aMapWithSize(4)))
				.andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.items[0].revision").value(2))
				.andExpect(jsonPath("$.data.items[0].reason").value("연결 취소 사유"))
				.andExpect(jsonPath("$.data.items[0].updatedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].runId").value(runId.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].confirmationId").value(confirmationId.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].confirmedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].cancellations[0].id").value(cancellationId.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].cancellations[0].resultId").value(resultId.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].confirmedBy").value(actorId.toString()))
				.andExpect(jsonPath("$.data.items[0].executions[0].cancellations[0].reason").value("실행별 사유"))
				.andExpect(jsonPath("$.data.items[0].changes[0].beforeUserId").value(before.toString()))
				.andExpect(jsonPath("$.data.items[0].changes[0].afterUserId").value(after.toString()))
				.andExpect(jsonPath("$.data.items[0].confirmedByName").value("현재 이름"))
				.andExpect(jsonPath("$.data.items[0].executions[0].confirmedByName").value("현재 이름"))
				.andExpect(jsonPath("$.data.items[0].changes[0].beforeUserName").value("현재 이름"))
				.andExpect(jsonPath("$.data.items[0].changes[0].afterUserName").value("현재 이름"));
	}

	/** 다음 동작을 검증한다: 근거 연결이 없는 과거 공개 버전은 executions 빈 배열을 반환한다. */
	@Test @DisplayName("근거 연결이 없는 과거 공개 버전은 executions 빈 배열을 반환한다")
	void historyWithoutEvidenceReturnsEmptyExecutions() throws Exception {
		// given
		when(repository.eventExists(eventId)).thenReturn(true);
		when(publications.changes(eventId, new PageQuery(1, 20))).thenReturn(new PageResult<>(
				List.of(new DrawPublicationChange(publication(1), List.of())), 1, 20, 1));
		// when / then
		mvc.perform(selected(get("/api/v1/admin/events/{id}/result-changes", eventId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].executions").isEmpty());
	}

	/** 큰 페이지는 전체 건수를 보존한 빈 목록을 반환한다는 시나리오를 검증한다. */
	@Test
	@DisplayName("큰 페이지는 전체 건수를 보존한 빈 목록을 반환한다")
	void customOutOfRangePageIsEmptyWithTotalPreserved() throws Exception {
		// given
		when(repository.eventExists(eventId)).thenReturn(true);
		when(publications.changes(eventId, new PageQuery(Integer.MAX_VALUE, 100)))
				.thenReturn(new PageResult<>(List.of(), Integer.MAX_VALUE, 100, 3));
		// when / then
		mvc.perform(selected(get("/api/v1/admin/events/{id}/result-changes", eventId)
				.param("page", "2147483647").param("size", "100")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
				.andExpect(jsonPath("$.data.totalElements").value(3));
	}

	/** 형식과 범위가 잘못된 페이지 조건은 400으로 거절한다는 시나리오를 검증한다. */
	@ParameterizedTest
	@CsvSource({"page,0", "page,-1", "size,0", "size,101", "size,-1", "page,abc", "page,2147483648"})
	@DisplayName("형식과 범위가 잘못된 페이지 조건은 400으로 거절한다")
	void invalidPaginationIs400(String name, String value) throws Exception {
		// given
		// when / then
		mvc.perform(selected(get("/api/v1/admin/events/{id}/result-changes", eventId).param(name, value)))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(repository, publications);
	}

	/** 모든 공개 관리자 API는 활성 관리자만 허용한다는 시나리오를 검증한다. */
	@ParameterizedTest
	@CsvSource({"USER,ACTIVE", "ADMIN,INACTIVE"})
	@DisplayName("모든 공개 관리자 API는 활성 관리자만 허용한다")
	void everyEndpointRequiresActiveAdmin(UserRole role, UserStatus state) throws Exception {
		// given
		actor = user(role, state);
		for (MockHttpServletRequestBuilder request : endpoints()) {
			// when / then
			mvc.perform(selected(request)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PUBLICATION-003"));
		}
		verifyNoInteractions(repository, publications);
	}

	/** 사용자 문맥 누락은 401이고 역할 불일치는 403임을 검증한다. */
	@Test
	@DisplayName("사용자 문맥 누락은 401이고 역할 불일치는 403이다")
	void missingContextIs401AndRoleMismatchIs403() throws Exception {
		// given: 공통 준비에서 활성 관리자와 세 API 요청을 구성한다.
		// when / then
		for (MockHttpServletRequestBuilder request : endpoints()) mvc.perform(request).andExpect(status().isUnauthorized());
		// when / then
		mvc.perform(post("/api/v1/admin/draws/{id}/publication", runId)
				.header("X-User-ID", actorId).header("X-User-Role", "USER")).andExpect(status().isForbidden());
		verifyNoInteractions(repository, publications);
	}

	/** 없는 실행은 404이고 확인 불가 실행은 409임을 검증한다. */
	@ParameterizedTest
	@ValueSource(strings = {"publication", "result-confirmation"})
	@DisplayName("없는 실행은 404이고 확인 불가 실행은 409이다")
	void missingRunIs404AndNonConfirmableIs409(String endpoint) throws Exception {
		// given
		when(repository.findEventId(runId)).thenReturn(Optional.empty());
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/" + endpoint, runId))).andExpect(status().isNotFound());
		when(repository.findEventId(runId)).thenReturn(Optional.of(eventId));
		when(publications.validateReplacement(eventId, runId)).thenThrow(new BusinessException(NOT_CONFIRMABLE));
		when(publications.publishReviewedInitial(eventId, runId, actorId)).thenThrow(new BusinessException(NOT_CONFIRMABLE));
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/{id}/" + endpoint, runId))).andExpect(status().isConflict());
		verify(repository, never()).saveConfirmation(any(), any(), any());
	}

	/** 없는 이벤트는 404이고 잘못된 경로 UUID는 400임을 검증한다. */
	@Test
	@DisplayName("없는 이벤트는 404이고 잘못된 경로 UUID는 400이다")
	void missingEventIs404AndMalformedPathIdsAre400() throws Exception {
		// given
		// when / then
		mvc.perform(selected(get("/api/v1/admin/events/{id}/result-changes", eventId))).andExpect(status().isNotFound());
		// when / then
		mvc.perform(selected(get("/api/v1/admin/events/bad/result-changes"))).andExpect(status().isBadRequest());
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/bad/publication"))).andExpect(status().isBadRequest());
		// when / then
		mvc.perform(selected(post("/api/v1/admin/draws/bad/result-confirmation"))).andExpect(status().isBadRequest());
	}

	/** 시험 요청에 선택한 사용자 식별자와 역할 헤더를 연결한다. */
	private MockHttpServletRequestBuilder selected(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", actorId).header("X-User-Role", actor.getRole());
	}
	/** 같은 권한 검사를 검증할 세 관리자 요청을 구성한다. */
	private List<MockHttpServletRequestBuilder> endpoints() {
		return List.of(post("/api/v1/admin/draws/{id}/publication", runId),
				post("/api/v1/admin/draws/{id}/result-confirmation", runId), get("/api/v1/admin/events/{id}/result-changes", eventId));
	}
	/** 지정 역할과 상태를 가진 시험용 사용자 문맥을 구성한다. */
	private User user(UserRole role, UserStatus status) {
		return new User(actorId, "관리자", role, status, null, null, null, null, null, NOW, NOW);
	}
	/** 선택적인 관리자 확인을 가진 확정 재추첨 조회 대역을 구성한다. */
	private DrawPublicationRun run(DrawResultConfirmation check) {
		return new DrawPublicationRun(runId, eventId, 2, DrawRunStatus.CONFIRMED, NOW, List.of(), check);
	}
	/** 시험에 필요한 공개 버전 메타데이터와 포함 결과를 구성한다. */
	private DrawPublication publication(int revision) {
		return new DrawPublication(UUID.randomUUID(), eventId, UUID.randomUUID(), revision,
				revision == 1 ? DrawPublicationType.INITIAL : DrawPublicationType.UPDATE,
				revision == 1 ? null : "연결 취소 사유", revision == 1 ? null : actorId,
				revision == 1 ? null : NOW, NOW, List.of());
	}
}
