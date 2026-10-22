package com.getddo.api.common.config;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.api.user.controller.UserController;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.repository.UserRepository;
import com.getddo.core.user.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsConfigTest {

	private static final String ORIGIN = "https://frontend.example.test";
	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000531");
	private final AtomicInteger lookups = new AtomicInteger();
	private RuntimeException repositoryFailure;
	// 실제 YAML·MVC·사용자 검증을 연결하고 DB 접근만 대체해 CORS와 GD-52의 연동을 확인한다.
	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("CORS_ALLOWED_ORIGINS=" + ORIGIN)
			.withUserConfiguration(TestApplication.class)
			.withBean(UserRepository.class, () -> id -> {
				lookups.incrementAndGet();
				if (repositoryFailure != null) {
					throw repositoryFailure;
				}
				return Optional.of(new User(id, "CORS 사용자", UserRole.USER, UserStatus.ACTIVE,
						Membership.VIP, null, null, null, null, Instant.EPOCH, Instant.EPOCH))
						.filter(user -> user.getId().equals(USER_ID));
			});

	@ParameterizedTest
	@CsvSource({
			"/api/v1/users/me, GET, 'X-User-ID,X-User-Role,X-User-Membership', 'X-User-ID, X-User-Role, X-User-Membership'",
			"/api/v1/events/00000000-0000-0000-0000-000000000001/entries, POST, 'Content-Type,Idempotency-Key,X-User-ID,X-User-Role,X-User-Membership', 'Content-Type, Idempotency-Key, X-User-ID, X-User-Role, X-User-Membership'"
	})
	@DisplayName("사용자·응모 API의 사전 요청은 사용자 조회 없이 문맥·멱등 헤더를 허용한다")
	void allowsApprovedPreflight(String path, String method, String requestedHeaders, String allowedHeaders) {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(options(path)
					.header("Origin", ORIGIN)
					.header("Access-Control-Request-Method", method)
					.header("Access-Control-Request-Headers", requestedHeaders))
					.andExpect(status().isOk())
					.andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
					.andExpect(header().string("Access-Control-Allow-Headers", allowedHeaders))
					.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
			assertThat(lookups.get()).isZero();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "DELETE"})
	@DisplayName("조회·등록·수정·삭제의 사전 요청에 허용 메서드와 JSON 헤더를 응답한다")
	void allowsBusinessMethods(String method) {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(preflight(ORIGIN, method, "Content-Type"))
					.andExpect(status().isOk())
					.andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
					.andExpect(header().string("Access-Control-Allow-Methods", containsString(method)))
					.andExpect(header().string("Access-Control-Allow-Headers", "Content-Type"));
			assertThat(lookups.get()).isZero();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://frontend.example.test", "https://other.example.test",
			"https://frontend.example.test:8443", "null"})
	@DisplayName("허용 목록과 프로토콜·호스트·포트가 다른 origin은 거절한다")
	void rejectsUnknownOrigin(String origin) {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(preflight(origin, "GET", "X-User-ID"))
					.andExpect(status().isForbidden())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			mvc(context).perform(userRequest().header("Origin", origin))
					.andExpect(status().isForbidden())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			assertThat(lookups.get()).isZero();
		});
	}

	@Test
	@DisplayName("origin이 허용되어도 목록에 없는 요청 메서드는 거절한다")
	void rejectsUnknownMethod() {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(preflight(ORIGIN, "PATCH", "X-User-ID"))
					.andExpect(status().isForbidden())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			assertThat(lookups.get()).isZero();
		});
	}

	@Test
	@DisplayName("허용되지 않은 요청 헤더만 있는 사전 요청은 거절한다")
	void rejectsUnknownHeader() {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(preflight(ORIGIN, "GET", "X-Unknown"))
					.andExpect(status().isForbidden())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			assertThat(lookups.get()).isZero();
		});
	}

	@Test
	@DisplayName("혼합 헤더의 사전 요청은 Spring이 허용한 헤더만 응답한다")
	void doesNotGrantUnknownHeaderInMixedPreflight() {
		// given / when / then
		// 응답이 200이어도 X-Unknown의 허용이 없으면 브라우저는 본 요청을 보내지 않는다.
		// MockMvc는 브라우저가 아니므로 응답 헤더를 검사하고 브라우저 동작까지 검증했다고 보지 않는다.
		runner.run(context -> {
			mvc(context).perform(preflight(ORIGIN, "GET", "x-user-id,X-Unknown"))
					.andExpect(status().isOk())
					.andExpect(header().string("Access-Control-Allow-Headers", "x-user-id"));
			assertThat(lookups.get()).isZero();
		});
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = "http://localhost")
	@DisplayName("동일 origin이나 Origin 없는 정상 요청은 CORS 허용 헤더 없이 기존 처리를 실행한다")
	void sameOriginRequestNeedsNoCorsGrant(String origin) {
		// given / when / then
		runner.run(context -> {
			var request = userRequest();
			if (origin != null) request.header("Origin", origin);
			mvc(context).perform(request)
					.andExpect(status().isOk())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			assertThat(lookups.get()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("허용 origin의 본 요청은 DB 사용자 정보와 CORS 헤더를 반환하고 한 번만 조회한다")
	void returnsUserWithCorsHeaders() {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(userRequest().header("Origin", ORIGIN))
					.andExpect(status().isOk())
					.andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
					.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
					.andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"))
					.andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
					.andExpect(jsonPath("$.data.name").value("CORS 사용자"));
			assertThat(lookups.get()).isEqualTo(1);
		});
	}

	@ParameterizedTest
	@CsvSource({
			"bad-id, USER, vip, 400, COMMON-005, 0",
			", USER, vip, 401, USER-003, 0",
			"00000000-0000-0000-0000-000000000531, , vip, 401, USER-003, 0",
			"00000000-0000-0000-0000-000000000531, USER, , 401, USER-003, 0",
			"00000000-0000-0000-0000-000000000532, USER, vip, 401, USER-002, 1",
			"00000000-0000-0000-0000-000000000531, ADMIN, , 403, USER-004, 1",
			"00000000-0000-0000-0000-000000000531, USER, vvip, 409, USER-006, 1"
	})
	@DisplayName("사용자 입력 오류에도 기존 오류 봉투와 CORS 헤더를 유지한다")
	void preservesCorsHeadersOnUserErrors(String id, String role, String membership,
			int status, String code, int expectedLookups) {
		// given
		var request = get("/api/v1/users/me").header("Origin", ORIGIN);
		if (id != null) request.header("X-User-ID", id);
		if (role != null) request.header("X-User-Role", role);
		if (membership != null) request.header("X-User-Membership", membership);

		// when / then
		runner.run(context -> {
			mvc(context).perform(request)
					.andExpect(status().is(status))
					.andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
					.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value(code))
					.andExpect(jsonPath("$.data").value(nullValue()));
			assertThat(lookups.get()).isEqualTo(expectedLookups);
		});
	}

	@Test
	@DisplayName("사용자 조회 장애의 500 응답에도 CORS 헤더를 유지한다")
	void preservesCorsHeadersOnServerError() {
		// given
		repositoryFailure = new IllegalStateException("test-only repository failure");

		// when / then
		runner.run(context -> {
			mvc(context).perform(userRequest().header("Origin", ORIGIN))
					.andExpect(status().isInternalServerError())
					.andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
					.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value("COMMON-001"))
					.andExpect(jsonPath("$.data").value(nullValue()));
			assertThat(lookups.get()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("본 요청 메서드가 없는 OPTIONS를 유효한 preflight로 취급하지 않는다")
	void optionsAloneDoesNotBypassCorsPolicy() {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(options("/api/v1/users/me").header("Origin", ORIGIN))
					.andExpect(status().isForbidden())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			assertThat(lookups.get()).isZero();
		});
	}

	@Test
	@DisplayName("업무 API 밖의 경로에는 CORS 허용을 부여하지 않는다")
	void limitsCorsGrantToApiPaths() {
		// given / when / then
		runner.run(context -> {
			mvc(context).perform(get("/cors-test/public").header("Origin", ORIGIN))
					.andExpect(status().isOk())
					.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
			assertThat(lookups.get()).isZero();
		});
	}

	private MockHttpServletRequestBuilder preflight(String origin, String method, String headers) {
		return options("/api/v1/users/me").header("Origin", origin)
				.header("Access-Control-Request-Method", method)
				.header("Access-Control-Request-Headers", headers);
	}

	private MockHttpServletRequestBuilder userRequest() {
		return get("/api/v1/users/me").header("X-User-ID", USER_ID)
				.header("X-User-Role", "USER").header("X-User-Membership", "vip");
	}

	private MockMvc mvc(WebApplicationContext context) {
		var builder = MockMvcBuilders.webAppContextSetup(context);
		// MockMvc에서는 운영 Bean의 필터를 직접 연결한다.
		// 실제 서블릿 컨테이너의 등록·실행 순서를 검증하는 GD-54 테스트와는 범위가 다르다.
		context.getBeansOfType(FilterRegistrationBean.class).values()
				.forEach(registration -> builder.addFilters(registration.getFilter()));
		return builder.build();
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableWebMvc
	@ComponentScan(basePackageClasses = TimeConfig.class, excludeFilters = @Filter(TestConfiguration.class))
	@Import({UserController.class, CurrentUserArgumentResolver.class, UserService.class,
			GlobalExceptionHandler.class, PublicController.class})
	static class TestApplication {
		/** CORS 전용 컨텍스트에서 응모의 DB·Redis 연결을 요구하지 않도록 의존성 경계만 대역으로 둔다. */
		@org.springframework.context.annotation.Bean
		com.getddo.api.common.ratelimit.EntryRateLimitHandler entryRateLimitHandler() {
			return org.mockito.Mockito.mock(com.getddo.api.common.ratelimit.EntryRateLimitHandler.class);
		}
	}

	@RestController
	static class PublicController {

		@GetMapping("/cors-test/public")
		String publicEndpoint() {
			return "public";
		}
	}
}
