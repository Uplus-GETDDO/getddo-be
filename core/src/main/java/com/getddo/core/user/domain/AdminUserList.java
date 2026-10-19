package com.getddo.core.user.domain;

import lombok.Getter;

import com.getddo.core.common.pagination.PageResult;

/** 관리자 사용자 목록과 검색 조건에 영향받지 않는 활성 일반 사용자 수다. */
@Getter
public final class AdminUserList {

	private final PageResult<User> page;
	private final long totalUserCount;

	/** 검색 결과 페이지와 별도로 집계한 활성 일반 사용자 수를 보관한다. */
	public AdminUserList(PageResult<User> page, long totalUserCount) {
		this.page = page;
		this.totalUserCount = totalUserCount;
	}
}
