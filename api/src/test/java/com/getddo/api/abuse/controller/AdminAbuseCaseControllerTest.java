package com.getddo.api.abuse.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.abuse.domain.AbuseCaseDetail;
import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.abuse.domain.AbuseSourceType;
import com.getddo.core.abuse.exception.AbuseErrorCode;
import com.getddo.core.abuse.service.AbuseCaseQueryService;
import com.getddo.core.abuse.repository.AbuseCaseRepository;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DB·Spring 컨텍스트 없이 공통 resolver와 예외 처리기를 연결하는 Controller 단위 테스트다. */
class AdminAbuseCaseControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE,
			null, null, null, null, null, null, null);
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID EVENT_ID = UUID.randomUUID();
	private static final Instant AT = Instant.parse("2026-10-21T06:00:00Z");

	private final AbuseCaseQueryService service = mock(AbuseCaseQueryService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminAbuseCaseController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	@Test
	@DisplayName("AR01은 목록 공개 필드 10개만 반환하고 없는 이벤트는 null로 유지한다")
	void returnsOnlySummaryFields() throws Exception {
		// given
		UUID caseId = UUID.randomUUID();
		AbuseCaseSummary summary = new AbuseCaseSummary(caseId, USER_ID, "응모자", null, null, "ENTRY",
				"REPEATED_RATE_LIMIT_EXCEEDED", "응모 요청 한도 초과가 반복되어 일시 차단되었습니다.", AT, AT.plusSeconds(600));
		when(service.findAll(any(), any(), any())).thenReturn(new PageResult<>(List.of(summary), 1, 20, 1));

		// when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.page").value(1))
				.andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0]", aMapWithSize(10)))
				.andExpect(jsonPath("$.data.items[0].id").value(caseId.toString()))
				.andExpect(jsonPath("$.data.items[0].userNickname").value("응모자"))
				.andExpect(jsonPath("$.data.items[0].eventId").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].eventTitle").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].blockedAt").value("2026-10-21T06:00:00Z"))
				.andExpect(jsonPath("$.data.items[0].blockedUntil").value("2026-10-21T06:10:00Z"));
	}

	@Test
	@DisplayName("모든 필터와 페이지를 전달하고 UTC 오프셋 시각은 같은 instant로 변환한다")
	void passesAllFilters() throws Exception {
		// given
		when(service.findAll(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 2, 5, 0));

		// when
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases").param("page", "2").param("size", "5")
				.param("userId", USER_ID.toString()).param("eventId", EVENT_ID.toString()).param("sourceType", "ENTRY")
				.param("from", "2026-10-21T15:00:00+09:00").param("to", "2026-10-21T06:10:00Z")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());

		// then
		ArgumentCaptor<AbuseCaseFilter> filter = ArgumentCaptor.forClass(AbuseCaseFilter.class);
		verify(service).findAll(eq(ADMIN), filter.capture(), eq(new PageQuery(2, 5)));
		assertThat(filter.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(filter.getValue().getEventId()).isEqualTo(EVENT_ID);
		assertThat(filter.getValue().getSourceType()).isEqualTo(AbuseSourceType.ENTRY);
		assertThat(filter.getValue().getFrom()).isEqualTo(AT);
		assertThat(filter.getValue().getTo()).isEqualTo(AT.plusSeconds(600));
	}

	@ParameterizedTest
	@CsvSource({"page,0", "page,-1", "size,0", "size,101", "sourceType,GAME", "sourceType,entry"})
	@DisplayName("해석 가능한 페이지 범위·지원하지 않는 기능 값은 COMMON-002로 거절한다")
	void rejectsInvalidValues(String parameter, String value) throws Exception {
		// given / when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases").param(parameter, value)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@CsvSource({"page,abc", "size,1.5", "userId,bad", "eventId,bad", "from,bad",
			"from,2026-10-21T06:00:00", "to,bad"})
	@DisplayName("숫자·UUID·시간대 없는 시각의 해석 실패는 COMMON-005로 거절한다")
	void rejectsInvalidFormats(String parameter, String value) throws Exception {
		// given / when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases").param(parameter, value)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@CsvSource({"2026-10-21T06:00:00Z,2026-10-21T06:00:00Z", "2026-10-22T06:00:00Z,2026-10-21T06:00:00Z"})
	@DisplayName("시작이 끝보다 같거나 늦은 기간은 COMMON-002로 거절한다")
	void rejectsInvalidPeriod(String from, String to) throws Exception {
		// given / when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases").param("from", from).param("to", to)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("활성 관리자 거절은 403 ABUSE-001과 data=null로 응답한다")
	void mapsAdminRequired() throws Exception {
		// given
		when(service.findAll(any(), any(), any())).thenThrow(new BusinessException(AbuseErrorCode.ADMIN_REQUIRED));

		// when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases"))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ABUSE-001"))
				.andExpect(jsonPath("$.message").value("활성 관리자 권한이 필요합니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("헤더 누락·미등록 사용자·역할 불일치는 기존 USER 코드를 유지한다")
	void preservesUserContextErrors() throws Exception {
		// given / when / then
		mvc.perform(get("/api/v1/admin/abuse-cases")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("USER-003"));
		mvc.perform(get("/api/v1/admin/abuse-cases").header("X-User-ID", UUID.randomUUID()).header("X-User-Role", "ADMIN"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-002"));
		mvc.perform(get("/api/v1/admin/abuse-cases").header("X-User-ID", ADMIN_ID).header("X-User-Role", "USER"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("내부 조회 실패는 예외 원문을 노출하지 않는 COMMON-001로 응답한다")
	void hidesInternalFailure() throws Exception {
		// given
		when(service.findAll(any(), any(), any())).thenThrow(new IllegalStateException("private DB details"));

		// when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases"))).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-001"))
				.andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@ParameterizedTest
	@CsvSource({"USER,true", "EVENT,false"})
	@DisplayName("AR02는 평탄한 열한 필드와 근거 일곱 필드만 공개하고 저장된 범위·설정·시각을 보존한다")
	void returnsAllowedDetailFields(AbuseTriggerScope scope, boolean withoutEvent) throws Exception {
		// given
		UUID id = UUID.randomUUID();
		AbuseCaseSummary summary = new AbuseCaseSummary(id, USER_ID, "응모자", withoutEvent ? null : EVENT_ID,
				withoutEvent ? null : "이벤트 제목", "ENTRY", "REPEATED_RATE_LIMIT_EXCEEDED", "저장된 사유", AT, AT.plusSeconds(600));
		AbuseCaseDetail detail = new AbuseCaseDetail(summary, new AbuseEvidence("stored-v2", scope,
				AT.minusSeconds(30), AT.plusSeconds(30), 37, 39, 37));
		when(service.findById(any(), eq(id))).thenReturn(detail);

		// when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases/{caseId}", id))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data", aMapWithSize(11)))
				.andExpect(jsonPath("$.data.id").value(id.toString()))
				.andExpect(jsonPath("$.data.userId").value(USER_ID.toString()))
				.andExpect(jsonPath("$.data.userNickname").value("응모자"))
				.andExpect(jsonPath("$.data.eventId").value(withoutEvent ? nullValue() : org.hamcrest.Matchers.is(EVENT_ID.toString())))
				.andExpect(jsonPath("$.data.eventTitle").value(withoutEvent ? nullValue() : org.hamcrest.Matchers.is("이벤트 제목")))
				.andExpect(jsonPath("$.data.sourceType").value("ENTRY"))
				.andExpect(jsonPath("$.data.detectionType").value("REPEATED_RATE_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$.data.detectionReason").value("저장된 사유"))
				.andExpect(jsonPath("$.data.blockedAt").value("2026-10-21T06:00:00Z"))
				.andExpect(jsonPath("$.data.blockedUntil").value("2026-10-21T06:10:00Z"))
				.andExpect(jsonPath("$.data.evidence", aMapWithSize(7)))
				.andExpect(jsonPath("$.data.evidence.ruleVersion").value("stored-v2"))
				.andExpect(jsonPath("$.data.evidence.triggerScope").value(scope.name()))
				.andExpect(jsonPath("$.data.evidence.windowStartedAt").value("2026-10-21T05:59:30Z"))
				.andExpect(jsonPath("$.data.evidence.windowEndsAt").value("2026-10-21T06:00:30Z"))
				.andExpect(jsonPath("$.data.evidence.requestLimit").value(37))
				.andExpect(jsonPath("$.data.evidence.rejectedCount").value(39))
				.andExpect(jsonPath("$.data.evidence.rejectionThreshold").value(37));
	}

	@Test
	@DisplayName("AR02 대상 부재는 경로 404가 아닌 ABUSE-002와 공용 메시지로 응답한다")
	void detailMissingIsBusinessNotFound() throws Exception {
		// given
		when(service.findById(any(), any())).thenThrow(new BusinessException(AbuseErrorCode.CASE_NOT_FOUND));

		// when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases/{caseId}", UUID.randomUUID())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ABUSE-002"))
				.andExpect(jsonPath("$.message").value("차단 이력을 찾을 수 없습니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	@DisplayName("AR02 UUID 해석 실패는 COMMON-005이고 상세 서비스를 호출하지 않는다")
	void rejectsMalformedDetailId() throws Exception {
		// given / when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases/not-a-uuid")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@CsvSource({"USER,ACTIVE", "ADMIN,INACTIVE"})
	@DisplayName("실제 상세 서비스는 비관리자·비활성 관리자를 ABUSE-001로 거절하고 이력 저장소를 조회하지 않는다")
	void actualDetailServiceChecksPermissionFirst(UserRole role, UserStatus state) throws Exception {
		// given
		User actor = new User(ADMIN_ID, "요청자", role, state, null, null, null, null, null, null, null);
		AbuseCaseRepository repository = mock(AbuseCaseRepository.class);
		MockMvc actualServiceMvc = MockMvcBuilders.standaloneSetup(new AdminAbuseCaseController(new AbuseCaseQueryService(repository)))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(new UserService(id -> Optional.of(actor))))
				.build();

		// when / then
		actualServiceMvc.perform(get("/api/v1/admin/abuse-cases/{caseId}", UUID.randomUUID())
				.header("X-User-ID", ADMIN_ID).header("X-User-Role", role.name()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ABUSE-001"));
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("AR02도 기존 USER 문맥 오류를 그대로 유지한다")
	void detailPreservesContextErrors() throws Exception {
		// given
		String path = "/api/v1/admin/abuse-cases/" + UUID.randomUUID();

		// when / then
		mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-003"));
		mvc.perform(get(path).header("X-User-ID", UUID.randomUUID()).header("X-User-Role", "ADMIN"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER-002"));
		mvc.perform(get(path).header("X-User-ID", ADMIN_ID).header("X-User-Role", "USER"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER-004"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("손상된 근거 등 상세 내부 오류는 COMMON-001이며 근거 원문을 응답하지 않는다")
	void detailHidesStoredEvidenceFailure() throws Exception {
		// given
		when(service.findById(any(), any())).thenThrow(new IllegalStateException("Stored abuse evidence is invalid"));

		// when / then
		mvc.perform(asAdmin(get("/api/v1/admin/abuse-cases/{caseId}", UUID.randomUUID())))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"))
				.andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}
}
