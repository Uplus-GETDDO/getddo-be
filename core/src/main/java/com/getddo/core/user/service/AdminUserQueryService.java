package com.getddo.core.user.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.user.domain.AdminUserList;
import com.getddo.core.user.domain.AdminUserQuery;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.domain.UserStatus;
import com.getddo.core.user.exception.UserErrorCode;
import com.getddo.core.user.exception.UserException;
import com.getddo.core.user.repository.AdminUserQueryRepository;
import com.getddo.core.user.repository.UserRepository;

/** DB 사용자 문맥의 활성 관리자에게 읽기 전용 목록·상세를 제공한다. */
@Service
@RequiredArgsConstructor
public class AdminUserQueryService {
	private final AdminUserQueryRepository queries;
	private final UserRepository users;

	/** 검색 결과 페이지와 DB 전체의 활성 일반 사용자 수를 하나의 조회 트랜잭션에서 읽는다. */
	@Transactional(readOnly = true)
	public AdminUserList findAll(User actor, AdminUserQuery query) {
		requireAdmin(actor);
		PageResult<User> page = queries.findAll(query);
		return new AdminUserList(page, queries.countActiveRegularUsers());
	}

	/** 조회 대상은 역할·활성 여부로 제외하지 않으며 부재만 404로 처리한다. */
	@Transactional(readOnly = true)
	public User findById(User actor, UUID userId) {
		requireAdmin(actor);
		return users.findById(userId).orElseThrow(() -> new UserException(UserErrorCode.ADMIN_TARGET_NOT_FOUND));
	}

	private static void requireAdmin(User actor) {
		if (actor == null || actor.getRole() != UserRole.ADMIN || actor.getStatus() != UserStatus.ACTIVE) {
			throw new UserException(UserErrorCode.ADMIN_REQUIRED);
		}
	}
}
