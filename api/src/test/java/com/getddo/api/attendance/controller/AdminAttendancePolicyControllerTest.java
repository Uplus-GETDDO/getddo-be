package com.getddo.api.attendance.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.attendance.domain.DailyAttendancePolicy;
import com.getddo.core.attendance.domain.MilestoneDraft;
import com.getddo.core.attendance.domain.StreakMilestone;
import com.getddo.core.attendance.domain.StreakPolicyRecord;
import com.getddo.core.attendance.exception.AttendanceErrorCode;
import com.getddo.core.attendance.exception.AttendanceException;
import com.getddo.core.attendance.service.AdminAttendancePolicyService;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAttendancePolicyControllerTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final User ADMIN = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null,
			null, null, null, null);
	private static final Instant FROM = Instant.parse("2026-10-17T15:00:00Z");
	private static final Instant AT = Instant.parse("2026-10-17T03:00:00Z");
	private static final String DAILY = "/api/v1/admin/policies/attendance/daily";
	private static final String STREAK = "/api/v1/admin/policies/attendance/streak";

	private final AdminAttendancePolicyService service = mock(AdminAttendancePolicyService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AdminAttendancePolicyController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(ADMIN).filter(user -> user.getId().equals(id)))))
				.build();
	}

	private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
		return request.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN");
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return asAdmin(request).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static DailyAttendancePolicy daily(int count, Instant until) {
		return new DailyAttendancePolicy(UUID.randomUUID(), count, FROM, until, ADMIN_ID, AT);
	}

	private static StreakPolicyRecord streak() {
		return new StreakPolicyRecord(UUID.randomUUID(), LocalDate.of(2026, 11, 1),
				List.of(new StreakMilestone(UUID.randomUUID(), 14, 3), new StreakMilestone(UUID.randomUUID(), 7, 1)),
				ADMIN_ID, AT);
	}

	@Test
	@DisplayName("AP01은 일일 정책을 계약 필드로 돌려주고 종료 시각이 없으면 null이다")
	void returnsDailyPolicies() throws Exception {
		// given
		when(service.findDailyPolicies(any(), eq(new PageQuery(1, 20)))).thenReturn(new PageResult<>(
				List.of(daily(3, null), daily(1, Instant.parse("2026-10-18T15:00:00Z"))), 1, 20, 2));
		// when
		// then
		mvc.perform(asAdmin(get(DAILY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.items[0].rewardTicketCount").value(3))
				.andExpect(jsonPath("$.data.items[0].effectiveFrom").value("2026-10-17T15:00:00Z"))
				.andExpect(jsonPath("$.data.items[0].effectiveUntil").hasJsonPath())
				.andExpect(jsonPath("$.data.items[0].effectiveUntil").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].createdBy").value(ADMIN_ID.toString()))
				.andExpect(jsonPath("$.data.items[1].effectiveUntil").value("2026-10-18T15:00:00Z"));
	}

	@Test
	@DisplayName("AP02는 보상 수량만 받아 201로 돌려준다")
	void createsDailyPolicy() throws Exception {
		// given
		when(service.createDailyPolicy(any(), eq(3))).thenReturn(daily(3, null));
		// when
		// then
		mvc.perform(json(post(DAILY), "{\"rewardTicketCount\":3}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.rewardTicketCount").value(3))
				.andExpect(jsonPath("$.data.effectiveFrom").value("2026-10-17T15:00:00Z"));
	}

	@Test
	@DisplayName("AP02는 수량이 없거나 1 미만이거나 형식이 틀리면 400이고 서비스를 부르지 않는다")
	void rejectsInvalidDailyBody() throws Exception {
		mvc.perform(json(post(DAILY), "{}")).andExpect(status().isBadRequest());
		mvc.perform(json(post(DAILY), "{\"rewardTicketCount\":0}")).andExpect(status().isBadRequest());
		mvc.perform(json(post(DAILY), "{\"rewardTicketCount\":\"x\"}")).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AP02의 예약 중복은 409 ATTENDANCE-004, 관리자 아님은 403 ATTENDANCE-002로 내려간다")
	void mapsDailyErrors() throws Exception {
		when(service.createDailyPolicy(any(), eq(3)))
				.thenThrow(new AttendanceException(AttendanceErrorCode.DAILY_POLICY_ALREADY_SCHEDULED));
		when(service.createDailyPolicy(any(), eq(4)))
				.thenThrow(new AttendanceException(AttendanceErrorCode.ADMIN_REQUIRED));
		mvc.perform(json(post(DAILY), "{\"rewardTicketCount\":3}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-004"));
		mvc.perform(json(post(DAILY), "{\"rewardTicketCount\":4}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-002"));
	}

	@Test
	@DisplayName("AP03은 정책 묶음을 단계 일수 오름차순 단계와 함께 돌려준다")
	void returnsStreakPolicies() throws Exception {
		// given
		when(service.findStreakPolicies(any(), eq(new PageQuery(2, 5))))
				.thenReturn(new PageResult<>(List.of(streak()), 2, 5, 6));
		// when
		// then
		mvc.perform(asAdmin(get(STREAK).param("page", "2").param("size", "5")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(6))
				.andExpect(jsonPath("$.data.items[0].effectiveMonth").value("2026-11"))
				.andExpect(jsonPath("$.data.items[0].milestones[0].milestoneDays").value(7))
				.andExpect(jsonPath("$.data.items[0].milestones[1].rewardTicketCount").value(3))
				.andExpect(jsonPath("$.data.items[0].createdBy").value(ADMIN_ID.toString()));
	}

	@Test
	@DisplayName("AP04는 단계를 서비스에 넘기고 201로 돌려준다")
	void createsStreakPolicy() throws Exception {
		// given
		when(service.createStreakPolicy(any(), any())).thenReturn(streak());
		// when
		mvc.perform(json(post(STREAK),
				"{\"milestones\":[{\"milestoneDays\":7,\"rewardTicketCount\":1},"
						+ "{\"milestoneDays\":14,\"rewardTicketCount\":3}]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.effectiveMonth").value("2026-11"));
		// then
		ArgumentCaptor<List<MilestoneDraft>> drafts = ArgumentCaptor.forClass(List.class);
		verify(service).createStreakPolicy(any(), drafts.capture());
		assertThat(drafts.getValue()).extracting(MilestoneDraft::getMilestoneDays).containsExactly(7, 14);
		assertThat(drafts.getValue()).extracting(MilestoneDraft::getRewardTicketCount).containsExactly(1, 3);
	}

	@Test
	@DisplayName("AP04는 단계가 비었거나 범위 밖이거나 수량이 0이면 400이고 서비스를 부르지 않는다")
	void rejectsInvalidStreakBody() throws Exception {
		mvc.perform(json(post(STREAK), "{\"milestones\":[]}")).andExpect(status().isBadRequest());
		mvc.perform(json(post(STREAK), "{}")).andExpect(status().isBadRequest());
		mvc.perform(json(post(STREAK), "{\"milestones\":[{\"milestoneDays\":29,\"rewardTicketCount\":1}]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post(STREAK), "{\"milestones\":[{\"milestoneDays\":7,\"rewardTicketCount\":0}]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post(STREAK), "{\"milestones\":[null]}")).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AP04의 순서·중복 오류는 400 ATTENDANCE-003, 적용월 중복은 409 ATTENDANCE-005로 내려간다")
	void mapsStreakErrors() throws Exception {
		when(service.createStreakPolicy(any(), any()))
				.thenThrow(new AttendanceException(AttendanceErrorCode.INVALID_POLICY))
				.thenThrow(new AttendanceException(AttendanceErrorCode.STREAK_POLICY_ALREADY_SCHEDULED));
		String body = "{\"milestones\":[{\"milestoneDays\":14,\"rewardTicketCount\":1},"
				+ "{\"milestoneDays\":7,\"rewardTicketCount\":1}]}";
		mvc.perform(json(post(STREAK), body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-003"));
		mvc.perform(json(post(STREAK), body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-005"));
	}

	@Test
	@DisplayName("조회의 페이지 크기가 범위를 넘으면 400이다")
	void rejectsInvalidPaging() throws Exception {
		mvc.perform(asAdmin(get(DAILY).param("size", "101"))).andExpect(status().isBadRequest());
		mvc.perform(asAdmin(get(STREAK).param("page", "0"))).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AP05는 경로의 정책 ID와 보상 수량을 서비스에 넘기고 수정된 정책을 200으로 돌려준다")
	void updatesDailyPolicy() throws Exception {
		// given
		DailyAttendancePolicy updated = daily(5, null);
		when(service.updateDailyPolicy(any(), eq(updated.getId()), eq(5))).thenReturn(updated);
		// when
		// then
		mvc.perform(json(put(DAILY + "/" + updated.getId()), "{\"rewardTicketCount\":5}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(updated.getId().toString()))
				.andExpect(jsonPath("$.data.rewardTicketCount").value(5))
				.andExpect(jsonPath("$.data.effectiveFrom").value("2026-10-17T15:00:00Z"));
	}

	@Test
	@DisplayName("AP05는 수량이 없거나 1 미만이거나 정책 ID 형식이 틀리면 400이고 서비스를 부르지 않는다")
	void rejectsInvalidDailyUpdate() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(json(put(DAILY + "/" + id), "{}")).andExpect(status().isBadRequest());
		mvc.perform(json(put(DAILY + "/" + id), "{\"rewardTicketCount\":0}")).andExpect(status().isBadRequest());
		mvc.perform(json(put(DAILY + "/not-a-uuid"), "{\"rewardTicketCount\":2}")).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AP05의 없는 정책은 404 ATTENDANCE-006, 이미 시작한 정책은 409 ATTENDANCE-007로 내려간다")
	void mapsDailyUpdateErrors() throws Exception {
		when(service.updateDailyPolicy(any(), any(), eq(2)))
				.thenThrow(new AttendanceException(AttendanceErrorCode.UPDATE_TARGET_POLICY_NOT_FOUND))
				.thenThrow(new AttendanceException(AttendanceErrorCode.POLICY_ALREADY_STARTED));
		String url = DAILY + "/" + UUID.randomUUID();
		mvc.perform(json(put(url), "{\"rewardTicketCount\":2}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-006"));
		mvc.perform(json(put(url), "{\"rewardTicketCount\":2}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-007"));
	}

	@Test
	@DisplayName("AP06은 경로의 묶음 ID와 단계를 서비스에 넘기고 수정된 묶음을 200으로 돌려준다")
	void updatesStreakPolicy() throws Exception {
		// given
		StreakPolicyRecord updated = streak();
		when(service.updateStreakPolicy(any(), eq(updated.getId()), any())).thenReturn(updated);
		// when
		// then
		mvc.perform(json(put(STREAK + "/" + updated.getId()),
				"{\"milestones\":[{\"milestoneDays\":7,\"rewardTicketCount\":1},"
						+ "{\"milestoneDays\":14,\"rewardTicketCount\":3}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(updated.getId().toString()))
				.andExpect(jsonPath("$.data.effectiveMonth").value("2026-11"));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MilestoneDraft>> drafts = ArgumentCaptor.forClass(List.class);
		verify(service).updateStreakPolicy(any(), eq(updated.getId()), drafts.capture());
		assertThat(drafts.getValue()).extracting(MilestoneDraft::getMilestoneDays, MilestoneDraft::getRewardTicketCount)
				.containsExactly(tuple(7, 1), tuple(14, 3));
	}

	@Test
	@DisplayName("AP06은 단계가 비었거나 범위 밖이면 400이고 서비스를 부르지 않는다")
	void rejectsInvalidStreakUpdate() throws Exception {
		String url = STREAK + "/" + UUID.randomUUID();
		mvc.perform(json(put(url), "{\"milestones\":[]}")).andExpect(status().isBadRequest());
		mvc.perform(json(put(url), "{\"milestones\":[{\"milestoneDays\":29,\"rewardTicketCount\":1}]}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("AP06의 없는 묶음은 404 ATTENDANCE-006, 이미 시작한 묶음은 409 ATTENDANCE-007로 내려간다")
	void mapsStreakUpdateErrors() throws Exception {
		when(service.updateStreakPolicy(any(), any(), any()))
				.thenThrow(new AttendanceException(AttendanceErrorCode.UPDATE_TARGET_POLICY_NOT_FOUND))
				.thenThrow(new AttendanceException(AttendanceErrorCode.POLICY_ALREADY_STARTED));
		String url = STREAK + "/" + UUID.randomUUID();
		String body = "{\"milestones\":[{\"milestoneDays\":7,\"rewardTicketCount\":1}]}";
		mvc.perform(json(put(url), body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-006"));
		mvc.perform(json(put(url), body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTENDANCE-007"));
	}
}
