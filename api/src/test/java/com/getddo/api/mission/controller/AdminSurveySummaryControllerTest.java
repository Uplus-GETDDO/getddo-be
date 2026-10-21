package com.getddo.api.mission.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.mission.domain.SurveyFreeTextAnswer;
import com.getddo.core.mission.domain.SurveySummary;
import com.getddo.core.mission.exception.MissionErrorCode;
import com.getddo.core.mission.exception.MissionException;
import com.getddo.core.mission.service.SurveySummaryService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSurveySummaryControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null,
			null, null, null, null);
	private static final UUID MISSION_ID = UUID.randomUUID();
	private static final String URL = "/api/v1/admin/missions/{id}/survey-summary";

	private final SurveySummaryService service = mock(SurveySummaryService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminSurveySummaryController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}

	@Test
	@DisplayName("집계를 날짜별 응답 수와 문항별 선택지 응답 수 구조로 돌려준다")
	void returnsSummary() throws Exception {
		// given
		UUID questionId = UUID.randomUUID();
		UUID optionId = UUID.randomUUID();
		when(service.summarize(any(), eq(MISSION_ID), eq(LocalDate.of(2026, 10, 11)), eq(LocalDate.of(2026, 10, 12))))
				.thenReturn(new SurveySummary(
						List.of(new SurveySummary.DailyCount(LocalDate.of(2026, 10, 11), 0),
								new SurveySummary.DailyCount(LocalDate.of(2026, 10, 12), 4)),
						List.of(new SurveySummary.QuestionSummary(questionId, "선호",
								List.of(new SurveySummary.OptionCount(optionId, "A", 3))))));
		// when
		// then
		mvc.perform(asAdmin(get(URL, MISSION_ID).param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.dailyResponses[0].date").value("2026-10-11"))
				.andExpect(jsonPath("$.data.dailyResponses[0].count").value(0))
				.andExpect(jsonPath("$.data.dailyResponses[1].count").value(4))
				.andExpect(jsonPath("$.data.questions[0].questionId").value(questionId.toString()))
				.andExpect(jsonPath("$.data.questions[0].questionText").value("선호"))
				.andExpect(jsonPath("$.data.questions[0].options[0].optionId").value(optionId.toString()))
				.andExpect(jsonPath("$.data.questions[0].options[0].optionText").value("A"))
				.andExpect(jsonPath("$.data.questions[0].options[0].count").value(3));
	}

	@Test
	@DisplayName("기간 파라미터가 없거나 날짜 형식이 틀리면 400이고 서비스를 부르지 않는다")
	void rejectsMissingOrMalformedPeriod() throws Exception {
		mvc.perform(asAdmin(get(URL, MISSION_ID)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(URL, MISSION_ID).param("from", "2026-10-11")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(URL, MISSION_ID).param("from", "2026/10/11").param("to", "2026-10-12")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("서비스의 거절은 403 MISSION-006, 404 MISSION-003, 400 MISSION-001로 내려간다")
	void mapsServiceErrors() throws Exception {
		when(service.summarize(any(), any(), any(), any()))
				.thenThrow(new MissionException(MissionErrorCode.ADMIN_REQUIRED))
				.thenThrow(new MissionException(MissionErrorCode.MISSION_NOT_FOUND))
				.thenThrow(new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION));
		String from = "2026-10-11";
		String to = "2026-10-12";
		mvc.perform(asAdmin(get(URL, MISSION_ID).param("from", from).param("to", to)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MISSION-006"));
		mvc.perform(asAdmin(get(URL, MISSION_ID).param("from", from).param("to", to)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MISSION-003"));
		mvc.perform(asAdmin(get(URL, MISSION_ID).param("from", from).param("to", to)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSION-001"));
	}

	private static final String ANSWERS_URL = "/api/v1/admin/missions/{id}/survey-answers";

	@Test
	@DisplayName("자유 서술 답변을 답변 원문과 제출 시각만 담은 익명 페이지로 돌려준다")
	void returnsAnonymousAnswers() throws Exception {
		// given
		UUID questionId = UUID.randomUUID();
		when(service.findFreeTextAnswers(any(), eq(MISSION_ID), eq(questionId), eq(LocalDate.of(2026, 10, 11)),
				eq(LocalDate.of(2026, 10, 12)), eq(new PageQuery(2, 5)))).thenReturn(new PageResult<>(
						List.of(new SurveyFreeTextAnswer("집에 가고 싶다", Instant.parse("2026-10-12T01:00:00Z")),
								new SurveyFreeTextAnswer("<b>태그</b>", Instant.parse("2026-10-11T01:00:00Z"))),
						2, 5, 7));
		// when
		// then
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", questionId.toString())
				.param("from", "2026-10-11").param("to", "2026-10-12").param("page", "2").param("size", "5")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(7))
				.andExpect(jsonPath("$.data.items[0].answerText").value("집에 가고 싶다"))
				.andExpect(jsonPath("$.data.items[0].submittedAt").value("2026-10-12T01:00:00Z"))
				.andExpect(jsonPath("$.data.items[1].answerText").value("<b>태그</b>"))
				.andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].submissionId").doesNotExist())
				.andExpect(jsonPath("$.data.items[0].userName").doesNotExist());
	}

	@Test
	@DisplayName("자유 서술 답변 조회는 문항·기간이 없거나 형식이 틀리거나 페이지 크기가 범위를 넘으면 400이고 서비스를 부르지 않는다")
	void answersRejectInvalidParameters() throws Exception {
		String question = UUID.randomUUID().toString();
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", question).param("from", "2026-10-11")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", "not-a-uuid")
				.param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-005"));
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", question)
				.param("from", "2026-10-11").param("to", "2026-10-12").param("size", "101")))
				.andExpect(status().isBadRequest());
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", question)
				.param("from", "2026-10-11").param("to", "2026-10-12").param("page", "0")))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("자유 서술 답변 조회의 서비스 거절은 403 MISSION-006, 404 MISSION-003, 400 MISSION-001로 내려간다")
	void mapsAnswerErrors() throws Exception {
		when(service.findFreeTextAnswers(any(), any(), any(), any(), any(), any()))
				.thenThrow(new MissionException(MissionErrorCode.ADMIN_REQUIRED))
				.thenThrow(new MissionException(MissionErrorCode.MISSION_NOT_FOUND))
				.thenThrow(new MissionException(MissionErrorCode.INVALID_MISSION_DEFINITION));
		String question = UUID.randomUUID().toString();
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", question)
				.param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MISSION-006"));
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", question)
				.param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MISSION-003"));
		mvc.perform(asAdmin(get(ANSWERS_URL, MISSION_ID).param("questionId", question)
				.param("from", "2026-10-11").param("to", "2026-10-12")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSION-001"));
	}
}
