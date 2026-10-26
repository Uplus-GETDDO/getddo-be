package com.getddo.core.user.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserList;
import com.getddo.core.user.domain.AdminUserQuery;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.exception.UserErrorCode;
import com.getddo.core.user.exception.UserException;
import com.getddo.core.user.repository.AdminUserQueryRepository;
import com.getddo.core.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserQueryServiceTest {
	private final AdminUserQueryRepository queries = mock(AdminUserQueryRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final AdminUserQueryService service = new AdminUserQueryService(queries, users);

	@ParameterizedTest
	@CsvSource({"USER,ACTIVE", "USER,INACTIVE", "ADMIN,INACTIVE"})
	@DisplayName("활성 관리자 외에는 목록과 상세 저장소를 조회하지 않는다")
	void deniesUnqualifiedActor(UserRole role, UserStatus status) {
		// given
		User actor = user(role, status);
		// when / then
		assertDenied(actor);
		verifyNoInteractions(queries, users);
	}

	@Test
	@DisplayName("사용자 문맥이 없으면 두 조회를 모두 거절한다")
	void deniesMissingActor() {
		// given / when / then
		assertDenied(null);
		verifyNoInteractions(queries, users);
	}

	@Test
	@DisplayName("활성 관리자는 비활성 관리자 대상도 기존 저장소로 조회한다")
	void reusesUserRepositoryForDetail() {
		// given
		User actor = user(UserRole.ADMIN, UserStatus.ACTIVE);
		User target = user(UserRole.ADMIN, UserStatus.INACTIVE);
		when(users.findById(target.getId())).thenReturn(Optional.of(target));
		// when / then
		assertThat(service.findById(actor, target.getId())).isSameAs(target);
		verifyNoInteractions(queries);
	}

	@Test
	@DisplayName("목록은 페이지와 전체 건수를 보존하고 없는 상세는 USER-010이다")
	void preservesPageAndReportsMissingTarget() {
		// given
		User actor = user(UserRole.ADMIN, UserStatus.ACTIVE);
		AdminUserQuery query = query(1, 20, null);
		PageResult<User> result = new PageResult<>(List.of(actor), 1, 20, 1);
		when(queries.findAll(query)).thenReturn(result);
		when(queries.countActiveRegularUsers()).thenReturn(120L);
		UUID missing = UUID.randomUUID();
		when(users.findById(missing)).thenReturn(Optional.empty());
		// when / then
		AdminUserList actual = service.findAll(actor, query);
		assertThat(actual.getPage()).isSameAs(result);
		assertThat(actual.getTotalUserCount()).isEqualTo(120L);
		assertThatExceptionOfType(UserException.class).isThrownBy(() -> service.findById(actor, missing))
				.satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.ADMIN_TARGET_NOT_FOUND));
	}

	@ParameterizedTest
	@CsvSource({"1,2,120", "61,0,120", "1,0,0"})
	@DisplayName("활성 일반 사용자 전체를 검색하면 페이지 위치와 무관하게 검색 총건수를 재사용한다")
	void reusesActiveRegularUserCount(int page, int itemCount, long total) {
		// given
		User actor = user(UserRole.ADMIN, UserStatus.ACTIVE);
		User target = user(UserRole.USER, UserStatus.ACTIVE);
		AdminUserQuery query = new AdminUserQuery(page, 2, null, "  ", UserRole.USER, UserStatus.ACTIVE, null);
		List<User> items = java.util.Collections.nCopies(itemCount, target);
		when(queries.findAll(query)).thenReturn(new PageResult<>(items, page, 2, total));

		// when
		AdminUserList result = service.findAll(actor, query);

		// then
		assertThat(result.getTotalUserCount()).isEqualTo(total);
		verify(queries, never()).countActiveRegularUsers();
	}

	@ParameterizedTest
	@CsvSource({"id", "keyword", "membership", "role", "status"})
	@DisplayName("활성 일반 사용자 전체와 다른 조건은 검색 건수를 전체 활성 사용자 수로 재사용하지 않는다")
	void countsActiveUsersIndependently(String filter) {
		// given
		User actor = user(UserRole.ADMIN, UserStatus.ACTIVE);
		AdminUserQuery query = new AdminUserQuery(1, 20,
				filter.equals("id") ? UUID.randomUUID() : null,
				filter.equals("keyword") ? "검색" : null,
				filter.equals("role") ? UserRole.ADMIN : UserRole.USER,
				filter.equals("status") ? UserStatus.INACTIVE : UserStatus.ACTIVE,
				filter.equals("membership") ? Membership.VIP : null);
		when(queries.findAll(query)).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
		when(queries.countActiveRegularUsers()).thenReturn(120L);

		// when
		AdminUserList result = service.findAll(actor, query);

		// then
		assertThat(result.getPage().getTotalElements()).isZero();
		assertThat(result.getTotalUserCount()).isEqualTo(120);
		verify(queries).countActiveRegularUsers();
	}

	@ParameterizedTest
	@CsvSource({"0,20", "-1,20", "1,0", "1,-1", "1,101"})
	@DisplayName("페이지와 크기 경계는 생성 시 USER-008로 거절한다")
	void rejectsInvalidPage(int page, int size) {
		// given / when / then
		assertThatExceptionOfType(UserException.class).isThrownBy(() -> query(page, size, null))
				.satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.INVALID_ADMIN_QUERY));
	}

	@Test
	@DisplayName("이름 검색은 공백을 정리하고 유니코드 문자 수로 20자까지 허용한다")
	void normalizesAndLimitsKeyword() {
		// given / when / then
		assertThat(query(1, 100, "  이름  ").getKeyword()).isEqualTo("이름");
		assertThat(query(1, 20, " \t ").getKeyword()).isNull();
		assertThat(query(Integer.MAX_VALUE, 100, "😀".repeat(20)).getKeyword()).isEqualTo("😀".repeat(20));
		assertThatExceptionOfType(UserException.class).isThrownBy(() -> query(1, 20, "가".repeat(21)))
				.satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.INVALID_ADMIN_QUERY));
	}

	private void assertDenied(User actor) {
		assertThatExceptionOfType(UserException.class).isThrownBy(() -> service.findAll(actor, query(1, 20, null)))
				.satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.ADMIN_REQUIRED));
		assertThatExceptionOfType(UserException.class).isThrownBy(() -> service.findById(actor, UUID.randomUUID()))
				.satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(UserErrorCode.ADMIN_REQUIRED));
	}

	private static AdminUserQuery query(int page, int size, String keyword) {
		return new AdminUserQuery(page, size, null, keyword, null, null, null);
	}

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "조회 사용자", role, status, null,
				null, null, null, null, null, null);
	}
}
