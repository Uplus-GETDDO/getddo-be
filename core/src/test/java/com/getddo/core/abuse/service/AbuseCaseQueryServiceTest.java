package com.getddo.core.abuse.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.abuse.exception.AbuseErrorCode;
import com.getddo.core.abuse.repository.AbuseCaseRepository;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbuseCaseQueryServiceTest {

	@Mock
	private AbuseCaseRepository repository;

	@Test
	@DisplayName("활성 관리자는 같은 조건으로 차단 목록을 조회하고 빈 페이지도 정상 반환한다")
	void activeAdminCanReadEmptyPage() {
		// given
		AbuseCaseFilter filter = new AbuseCaseFilter(UUID.randomUUID(), null, null, null, null);
		PageQuery query = new PageQuery(3, 10);
		PageResult<AbuseCaseSummary> empty = new PageResult<>(List.of(), 3, 10, 0);
		when(repository.findAll(filter, query)).thenReturn(empty);

		// when
		PageResult<AbuseCaseSummary> result = new AbuseCaseQueryService(repository)
				.findAll(user(UserRole.ADMIN, UserStatus.ACTIVE), filter, query);

		// then
		assertThat(result).isSameAs(empty);
		verify(repository).findAll(filter, query);
	}

	@Test
	@DisplayName("비관리자·비활성 관리자·사용자 부재는 ABUSE-001이며 이력 저장소를 읽지 않는다")
	void deniesBeforeReadingHistory() {
		// given
		AbuseCaseQueryService service = new AbuseCaseQueryService(repository);
		User[] denied = {user(UserRole.USER, UserStatus.ACTIVE), user(UserRole.ADMIN, UserStatus.INACTIVE),
				user(UserRole.USER, UserStatus.INACTIVE), null};

		// when / then
		for (User actor : denied) {
			assertThatThrownBy(() -> service.findAll(actor, new AbuseCaseFilter(null, null, null, null, null),
					new PageQuery(1, 20)))
					.isInstanceOfSatisfying(BusinessException.class,
							ex -> assertThat(ex.getErrorCode()).isEqualTo(AbuseErrorCode.ADMIN_REQUIRED));
		}
		verifyNoInteractions(repository);
	}

	private static User user(UserRole role, UserStatus status) {
		return new User(UUID.randomUUID(), "관리자", role, status, null, null, null, null, null, null, null);
	}
}
