package com.getddo.core.user.repository;

import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserQuery;
import com.getddo.core.user.domain.User;

/** 사용자 저장·권한 변경과 분리된 관리자 목록 조회 계약이다. */
public interface AdminUserQueryRepository {
	PageResult<User> findAll(AdminUserQuery query);

	/** 목록 필터와 무관하게 DB 전체의 USER·ACTIVE 사용자 수를 센다. */
	long countActiveRegularUsers();
}
