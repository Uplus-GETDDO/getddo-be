package com.getddo.core.user.domain;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.common.pagination.PageResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AdminUserListTest {

	@Test
	@DisplayName("목록 페이지가 null이면 생성할 수 없다")
	void rejectsNullPage() {
		// given / when / then
		assertThatNullPointerException().isThrownBy(() -> new AdminUserList(null, 0));
	}

	@Test
	@DisplayName("활성 일반 사용자 수가 음수이면 생성할 수 없다")
	void rejectsNegativeCount() {
		// given
		PageResult<User> page = new PageResult<>(List.of(), 1, 20, 0);

		// when / then
		assertThatIllegalArgumentException().isThrownBy(() -> new AdminUserList(page, -1));
	}

	@Test
	@DisplayName("빈 페이지와 0명은 유효한 결과다")
	void acceptsEmptyPageAndZeroCount() {
		// given
		PageResult<User> page = new PageResult<>(List.of(), 1, 20, 0);

		// when
		AdminUserList result = new AdminUserList(page, 0);

		// then
		assertThat(result.getPage()).isSameAs(page);
		assertThat(result.getTotalUserCount()).isZero();
	}
}
