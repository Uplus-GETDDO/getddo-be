package com.getddo.api.user.dto.response;

import java.util.List;

import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserList;
import com.getddo.core.user.domain.User;

/** 기존 페이지 필드와 DB 전체의 활성 일반 사용자 수를 함께 반환한다. */
public record AdminUserListResponse(List<AdminUserResponse> items, int page, int size,
		long totalElements, long totalUserCount) {

	/** 목록의 전체 건수와 별개로 집계한 활성 일반 사용자 수를 보존한다. */
	public static AdminUserListResponse from(AdminUserList result) {
		PageResult<User> users = result.getPage();
		return new AdminUserListResponse(users.getItems().stream().map(AdminUserResponse::from).toList(),
				users.getPage(), users.getSize(), users.getTotalElements(), result.getTotalUserCount());
	}
}
