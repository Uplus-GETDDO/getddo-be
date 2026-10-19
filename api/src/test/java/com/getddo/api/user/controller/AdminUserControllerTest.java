package com.getddo.api.user.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.getddo.api.common.context.CurrentUserArgumentResolver;
import com.getddo.api.common.exception.GlobalExceptionHandler;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserList;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.service.AdminUserQueryService;
import com.getddo.core.user.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserControllerTest {

	@ParameterizedTest
	@ValueSource(longs = {0, 120})
	@DisplayName("검색 결과가 없어도 totalUserCount를 페이지 필드와 함께 반환한다")
	void returnsTotalUserCountWithEmptyPage(long totalUserCount) throws Exception {
		// given
		UUID adminId = UUID.randomUUID();
		User admin = new User(adminId, "관리자", UserRole.ADMIN, UserStatus.ACTIVE,
				null, null, null, null, null, null, null);
		AdminUserQueryService service = mock(AdminUserQueryService.class);
		when(service.findAll(any(), any())).thenReturn(
				new AdminUserList(new PageResult<>(List.of(), 2, 1, 0), totalUserCount));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminUserController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new CurrentUserArgumentResolver(
						new UserService(id -> Optional.of(admin).filter(user -> user.getId().equals(id)))))
				.build();

		// when / then
		mvc.perform(get("/api/v1/admin/users").header("X-User-ID", adminId).header("X-User-Role", "ADMIN")
				.param("role", "ADMIN").param("status", "INACTIVE").param("page", "2").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items").isEmpty())
				.andExpect(jsonPath("$.data.page").value(2))
				.andExpect(jsonPath("$.data.size").value(1))
				.andExpect(jsonPath("$.data.totalElements").value(0))
				.andExpect(jsonPath("$.data.totalUserCount").value(totalUserCount));
	}
}
