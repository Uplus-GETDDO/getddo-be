package com.getddo.api.common.exception;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.util.stream.Stream;
import java.util.UUID;

import com.getddo.api.common.context.CurrentUser;
import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.service.UserService;

import jakarta.persistence.RollbackException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.hibernate.exception.JDBCConnectionException;
import com.getddo.api.common.ratelimit.RateLimitExceededException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getddo.api.common.response.ResponseEnvelope;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.exception.ErrorCode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 테스트 전용 Controller와 예외 처리기만 연결해 실제 HTTP 응답 형식을 검증한다.
 *
 * <p>Spring Boot 전체 컨텍스트를 시작하지 않으므로 DB 연결 없이 실행한다.
 * 업무 Service나 저장소의 동작이 아니라 JSON 직렬화, 예외 분류, HTTP 상태·헤더 보존이 검증 대상이다.</p>
 */
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void successContainsResponseData() throws Exception {
		mockMvc.perform(get("/test/success"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.message").value("성공했습니다."))
				.andExpect(jsonPath("$.data.name").value("sample"));
	}

	@Test
	void successCanHaveNoData() throws Exception {
		mockMvc.perform(get("/test/empty"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	void businessErrorPreservesItsStatusAndCode() throws Exception {
		mockMvc.perform(get("/test/business"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("TEST-001"))
				.andExpect(jsonPath("$.message").value("이미 처리된 요청입니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	@Test
	void invalidDtoReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-002"));
	}

	@Test
	void missingParameterReturnsBadRequestInsteadOfServerError() throws Exception {
		mockMvc.perform(get("/test/parameter"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-002"));
	}

	@Test
	void missingHeaderReturnsBadRequest() throws Exception {
		mockMvc.perform(get("/test/header"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-002"));
	}

	@Test
	void malformedJsonReturnsFormatError() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
	}

	@Test
	void invalidParameterTypeReturnsFormatError() throws Exception {
		mockMvc.perform(get("/test/parameter").param("id", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-005"));
	}

	// 본문만 통일하다가 원래의 405 상태나 재시도에 필요한 Allow 헤더를 잃지 않는지 확인한다.
	@Test
	void unsupportedMethodPreservesStatusAndAllowHeader() throws Exception {
		mockMvc.perform(post("/test/success"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(jsonPath("$.code").value("HTTP-405"));
	}

	@Test
	void unsupportedMediaTypePreservesStatus() throws Exception {
		mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("sample"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("HTTP-415"));
	}

	@Test
	void unexpectedErrorDoesNotExposeInternalDetails() throws Exception {
		mockMvc.perform(get("/test/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("COMMON-001"))
				.andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
				.andExpect(jsonPath("$.data").value(nullValue()));
	}

	/** 연결 장애의 포장 형태와 무관하게 공개 503 봉투를 유지하고 내부 정보·복구 시간 헤더를 노출하지 않는지 확인한다. */
	@ParameterizedTest
	@MethodSource("connectionFailures")
	@DisplayName("연결 획득·실행·커밋의 연결 장애는 내부 정보와 Retry-After 없이 공통 503으로 응답한다")
	void connectionFailureReturnsUnavailable(Exception failure) throws Exception {
		// given
		MockMvc mvc = failingMvc(failure);
		// when / then
		mvc.perform(get("/test/failure"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("COMMON-007"))
				.andExpect(jsonPath("$.message").value("일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(header().doesNotExist("Retry-After"));
	}

	/** 연결 획득·실행·커밋 실패와 풀 고갈을 나타내는 연결 전용 예외 표본을 제공한다. */
	static Stream<Exception> connectionFailures() {
		return Stream.of(
				new SQLException("private SQL and host", "08006"),
				new CannotGetJdbcConnectionException("unavailable"),
				new CannotCreateTransactionException("begin", new SQLException("private", "08001")),
				new RollbackException("commit", new SQLException("private", "08S01")),
				new TransactionSystemException("commit", new RollbackException("commit", new SQLException("private", "08007"))),
				new CannotCreateTransactionException("pool", new JDBCConnectionException("acquire",
						new SQLTransientConnectionException("pool exhausted"))));
	}

	/** 연결 장애로 단정할 수 없는 SQL 오류를 기존 서버 오류 계약으로 유지하는지 검증한다. */
	@ParameterizedTest
	@MethodSource("nonConnectionFailures")
	@DisplayName("문법·무결성·쿼리 시간초과·교착과 근거 없는 일시 예외는 기존 500을 유지한다")
	void unrelatedFailureKeepsServerError(Exception failure) throws Exception {
		// given
		MockMvc mvc = failingMvc(failure);
		// when / then
		mvc.perform(get("/test/failure"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-001"));
	}

	/** 문법·무결성·쿼리 제한·교착 등 503으로 일괄 변환하면 안 되는 오류 표본을 제공한다. */
	static Stream<Exception> nonConnectionFailures() {
		return Stream.of(new SQLSyntaxErrorException("syntax", "42000"),
				new SQLIntegrityConstraintViolationException("duplicate", "23000", 1062),
				new SQLTimeoutException("query timeout", "HYT00"), new SQLException("deadlock", "40001", 1213),
				new SQLTransientConnectionException("not evidence"),
				new CannotCreateTransactionException("no connection cause"));
	}

	/** DB 장애 분류를 추가해도 기존 빈도 제한의 상태·코드·Retry-After가 유지되는지 확인한다. */
	@Test
	@DisplayName("빈도 제한은 기존 429와 Retry-After를 유지한다")
	void rateLimitPreservesRetryAfter() throws Exception {
		// given
		MockMvc mvc = failingMvc(new RateLimitExceededException(12));
		// when / then
		mvc.perform(get("/test/failure"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("COMMON-006"))
				.andExpect(header().string("Retry-After", "12"));
	}

	/** Controller 실행 전 사용자 파라미터의 DB 조회에서 발생한 연결 오류도 MVC Advice가 처리하는지 확인한다. */
	@Test
	@DisplayName("사용자 문맥 DB 조회의 연결 장애도 MVC에서 공통 503으로 변환한다")
	void userContextConnectionFailureReturnsUnavailable() throws Exception {
		// given
		UserService users = mock(UserService.class);
		UUID id = UUID.randomUUID();
		when(users.findById(id)).thenThrow(new CannotCreateTransactionException("begin",
				new SQLException("private", "08001")));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserContextController())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(users))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
		// when / then
		mvc.perform(get("/test/user").header("X-User-ID", id).header("X-User-Role", "USER"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("COMMON-007"));
	}

	/** 실제 사용자 파라미터 해석 경로를 통과시키는 테스트 진입점이다. */
	@RestController
	static class UserContextController {
		/** DB에서 사용자 문맥을 조회하다 발생한 예외가 Advice로 전달되게 한다. */
		@GetMapping("/test/user")
		void user(@CurrentUser(membershipRequired = false) User user) {}
	}

	/** 가려진 연결 근거를 트랜잭션 실패에만 사용하고 무관한 SQL 오류나 다음 요청에 재사용하지 않는지 검증한다. */
	@Test
	@DisplayName("단절 근거가 롤백 오류에 가려져도 503이며 다음 요청의 근거 없는 오류는 500을 유지한다")
	void rollbackCannotHideConnectionFailureOrLeakToNextRequest() throws Exception {
		// given
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new RollbackFailureController())
				.setControllerAdvice(new GlobalExceptionHandler()).build();
		// when / then
		mvc.perform(get("/test/rollback").param("state", "08S01"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("COMMON-007"));
		mvc.perform(get("/test/rollback"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-001"));
		mvc.perform(get("/test/rollback").param("state", "08S01").param("rollback", "false"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-001"));
		mvc.perform(get("/test/rollback").param("state", "42000"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-001"));
	}

	/** 실제 JDBC 근거가 트랜잭션 정리 오류에 가려지는 모양을 재현한다. */
	@RestController
	static class RollbackFailureController {
		/** 연결 근거·트랜잭션 실패 여부를 조합해 잘못된 503 변환과 요청 간 근거 유출을 검증한다. */
		@GetMapping("/test/rollback")
		void fail(@RequestParam(defaultValue = "", name = "state") String state,
				@RequestParam(defaultValue = "true", name = "rollback") boolean rollback) {
			if (!state.isEmpty()) {
				new RequestDatabaseConnectionFailure().adjudicate(new SQLException("private", state));
			}
			Throwable cause = rollback ? new org.hibernate.TransactionException("rollback", new SQLException("private"))
					: new SQLException("private");
			throw new DataAccessResourceFailureException("failure", cause);
		}
	}

	/** 선택한 예외를 던지는 테스트 Controller와 실제 Advice만 연결한다. */
	private MockMvc failingMvc(Exception failure) {
		return MockMvcBuilders.standaloneSetup(new FailureController(failure))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	/** 예외 표본을 실제 MVC 처리기에 전달하는 테스트 Controller다. */
	@RestController
	static class FailureController {
		private final Exception failure;

		FailureController(Exception failure) {
			this.failure = failure;
		}

		/** 표본 예외를 포장하지 않고 던져 Advice의 분류 결과를 확인한다. */
		@GetMapping("/test/failure")
		void fail() throws Exception {
			throw failure;
		}
	}

	// 운영 API가 아니라 공통 응답의 각 처리 경로를 재현하기 위한 테스트 전용 진입점이다.
	@RestController
	static class TestController {
		@GetMapping("/test/success")
		ResponseEnvelope<Payload> success() {
			return ResponseEnvelope.success(new Payload("sample"));
		}

		@GetMapping("/test/empty")
		ResponseEnvelope<Void> empty() {
			return ResponseEnvelope.success(null);
		}

		@GetMapping("/test/business")
		void business() {
			throw new BusinessException(new ErrorCode() {
				public int getStatus() { return 409; }
				public String getCode() { return "TEST-001"; }
				public String getMessage() { return "이미 처리된 요청입니다."; }
			});
		}

		@PostMapping(value = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
		ResponseEnvelope<Payload> body(@Valid @RequestBody Payload payload) {
			return ResponseEnvelope.success(payload);
		}

		@GetMapping("/test/parameter")
		void parameter(@RequestParam("id") int id) {}

		@GetMapping("/test/header")
		void requiredHeader(@RequestHeader("X-Test") String value) {}

		@GetMapping("/test/unexpected")
		void unexpected() {
			throw new IllegalStateException("internal database connection details");
		}
	}

	record Payload(@NotBlank String name) {}
}
