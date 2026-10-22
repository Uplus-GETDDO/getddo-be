package com.getddo.api.notification.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.notification.domain.AdminNotification;
import com.getddo.core.notification.domain.AdminNotificationJob;
import com.getddo.core.notification.domain.AdminNotificationJobResult;
import com.getddo.core.notification.domain.NotificationJobStatus;
import com.getddo.core.notification.domain.NotificationType;
import com.getddo.core.notification.domain.MockDeliveryStatus;
import com.getddo.core.notification.service.NotificationAdminService;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminNotificationControllerTest {
	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
	private final NotificationAdminService service = mock(NotificationAdminService.class);
	private MockMvc mvc;

	/** 테스트 데이터와 고정 시계를 준비한다. */
	@BeforeEach
	void setUp() {
		User user = new User(ADMIN_ID, "관리자", UserRole.ADMIN, UserStatus.ACTIVE, null, null, null, null, null, null, null);
		mvc = MockMvcBuilders.standaloneSetup(new AdminNotificationController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(new UserService(id -> Optional.of(user))))
				.build();
	}

	/** 관리자 작업 목록은 공통 페이지 응답을 반환한다 */
	@Test
	@DisplayName("관리자 작업 목록은 공통 페이지 응답을 반환한다")
	void returnsPageEnvelope() throws Exception {
		// given
		AdminNotificationJob item = job(UUID.randomUUID(), NotificationJobStatus.FAILED);
		when(service.findJobs(any(), any())).thenReturn(new PageResult<>(List.of(new AdminNotificationJobResult(item, true)), 1, 20, 21));
		// when / then
		mvc.perform(get("/api/v1/admin/notification-jobs").header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray())
				.andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(21))
				.andExpect(jsonPath("$.data.items[0].id").value(item.getId().toString()))
				.andExpect(jsonPath("$.data.items[0].notificationType").value("RESULT_PUBLISHED"))
				.andExpect(jsonPath("$.data.items[0].attemptCount").value(4))
				.andExpect(jsonPath("$.data.items[0].retryable").value(true));
	}

	/** 잘못된 조회 기간·상태·크기는 400으로 반환한다 */
	@Test
	@DisplayName("잘못된 조회 기간·상태·크기는 400으로 반환한다")
	void rejectsMalformedQueries() throws Exception {
		// given / when / then
		for (List<String> testCase : List.of(List.of("size=101", "NOTIFICATION-007"),
				List.of("status=WRONG", "COMMON-005"),
				List.of("from=2026-10-12T02:00:00Z&to=2026-10-12T01:00:00Z", "NOTIFICATION-007"))) {
			mvc.perform(get("/api/v1/admin/notification-jobs?" + testCase.get(0))
					.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(testCase.get(1)));
		}
	}

	/** 발송 복구 접수는 202이며 isRead JSON 필드명을 유지한다 */
	@Test
	@DisplayName("발송 복구 접수는 202이며 isRead JSON 필드명을 유지한다")
	void acceptsDeliveryRetry() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		when(service.retryDelivery(any(), eq(id))).thenReturn(new AdminNotification(id, UUID.randomUUID(), UUID.randomUUID(), "수신자",
				null, "제목", "내용", null, NOW, true, MockDeliveryStatus.PENDING, null, 4, 4, NOW, null));
		// when / then
		mvc.perform(post("/api/v1/admin/notifications/" + id + "/delivery-retries")
				.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.userName").value("수신자"))
				.andExpect(jsonPath("$.data.isRead").value(true))
				.andExpect(jsonPath("$.data.deliveryAttemptCount").value(4));
	}

	/** 상세 응답의 실패 근거와 재처리 대상 식별자를 보존한다. */
	@Test
	@DisplayName("작업 상세는 실패 코드와 처리 근거를 반환한다")
	void returnsJobDetail() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		AdminNotificationJob item = job(id, NotificationJobStatus.FAILED);
		when(service.findJob(any(), eq(id))).thenReturn(new AdminNotificationJobResult(item, true));
		// when / then
		mvc.perform(get("/api/v1/admin/notification-jobs/" + id)
				.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()))
				.andExpect(jsonPath("$.data.lastError").value("NOTIFICATION-005"))
				.andExpect(jsonPath("$.data.sourceJobId").value(item.getSourceJobId().toString()))
				.andExpect(jsonPath("$.data.lastProcessedUserId").value(item.getLastProcessedUserId().toString()))
				.andExpect(jsonPath("$.data.retryable").value(true));
	}

	/** HTTP 변환은 Service가 판단한 복구 여부를 그대로 보존한다. */
	@Test
	@DisplayName("작업 응답은 Service의 복구 가능 여부를 다시 판단하지 않는다")
	void preservesServiceRetryability() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		AdminNotificationJob item = job(id, NotificationJobStatus.FAILED);
		when(service.findJobs(any(), any())).thenReturn(new PageResult<>(List.of(new AdminNotificationJobResult(item, false)), 1, 20, 1));
		when(service.findJob(any(), eq(id))).thenReturn(new AdminNotificationJobResult(item, false));
		// when / then
		mvc.perform(get("/api/v1/admin/notification-jobs").header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].retryable").value(false));
		mvc.perform(get("/api/v1/admin/notification-jobs/" + id).header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.retryable").value(false));
	}

	/** 생성 복구는 완료 응답이 아니라 누적 차수를 유지한 접수 응답이다. */
	@Test
	@DisplayName("작업 복구 접수는 202와 PENDING 작업을 반환한다")
	void acceptsGenerationRetry() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		when(service.retryJob(any(), eq(id))).thenReturn(new AdminNotificationJobResult(job(id, NotificationJobStatus.PENDING), false));
		// when / then
		mvc.perform(post("/api/v1/admin/notification-jobs/" + id + "/retry")
				.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value(id.toString()))
				.andExpect(jsonPath("$.data.status").value("PENDING"))
				.andExpect(jsonPath("$.data.attemptCount").value(4))
				.andExpect(jsonPath("$.data.retryable").value(false));
	}

	/** 발송 목록의 내용·읽음 여부·실패 정보를 HTTP 계약대로 변환한다. */
	@Test
	@DisplayName("발송 목록은 실제 항목과 전체 개수를 반환한다")
	void returnsDeliveryPage() throws Exception {
		// given
		UUID id = UUID.randomUUID();
		AdminNotification item = new AdminNotification(id, UUID.randomUUID(), UUID.randomUUID(), "수신자", null,
				"제목", "내용", "/events/test", NOW, true, MockDeliveryStatus.FAILED, null, 4, 0, null, "NOTIFICATION-005");
		when(service.findDeliveries(any(), any())).thenReturn(new PageResult<>(List.of(item), 1, 20, 21));
		// when / then
		mvc.perform(get("/api/v1/admin/notifications").header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(21))
				.andExpect(jsonPath("$.data.items[0].id").value(id.toString()))
				.andExpect(jsonPath("$.data.items[0].jobId").value(item.getJobId().toString()))
				.andExpect(jsonPath("$.data.items[0].userId").value(item.getUserId().toString()))
				.andExpect(jsonPath("$.data.items[0].userName").value("수신자"))
				.andExpect(jsonPath("$.data.items[0].isRead").value(true))
				.andExpect(jsonPath("$.data.items[0].mockDeliveryStatus").value("FAILED"))
				.andExpect(jsonPath("$.data.items[0].lastDeliveryError").value("NOTIFICATION-005"))
				.andExpect(jsonPath("$.data.items[0].retryable").value(true));
	}

	/** 범위를 벗어난 페이지도 전체 개수를 유지하고 빈 목록으로 응답한다. */
	@Test
	@DisplayName("작업·발송 목록의 범위 밖 페이지는 빈 items를 반환한다")
	void returnsEmptyOutOfRangePages() throws Exception {
		// given
		when(service.findJobs(any(), any())).thenReturn(new PageResult<>(List.of(), 3, 20, 21));
		when(service.findDeliveries(any(), any())).thenReturn(new PageResult<>(List.of(), 3, 20, 21));
		// when / then
		for (String route : List.of("notification-jobs", "notifications")) {
			mvc.perform(get("/api/v1/admin/" + route + "?page=3")
					.header("X-User-ID", ADMIN_ID).header("X-User-Role", "ADMIN"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
					.andExpect(jsonPath("$.data.page").value(3)).andExpect(jsonPath("$.data.totalElements").value(21));
		}
	}

	/** 목록・상세・접수 테스트에서 사용할 작업 조회 결과를 만든다. */
	private AdminNotificationJob job(UUID id, NotificationJobStatus status) {
		return new AdminNotificationJob(id, null, UUID.randomUUID(), NotificationType.RESULT_PUBLISHED,
				status, 4, 0, NOW, null, NOW, null, null, "NOTIFICATION-005", UUID.randomUUID(),
				UUID.randomUUID(), null, null, null, false);
	}

}
