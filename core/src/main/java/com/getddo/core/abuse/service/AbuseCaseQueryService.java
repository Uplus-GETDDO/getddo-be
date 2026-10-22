package com.getddo.core.abuse.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/** 기존 사용자 문맥이 확인한 활성 관리자에게 차단 이력만 읽기 전용으로 제공한다. */
@Service
@RequiredArgsConstructor
public class AbuseCaseQueryService {

	private final AbuseCaseRepository repository;

	/** 권한 확인을 마치기 전에는 이력 저장소를 조회하지 않는다. */
	@Transactional(readOnly = true)
	public PageResult<AbuseCaseSummary> findAll(User actor, AbuseCaseFilter filter, PageQuery page) {
		if (actor == null || actor.getRole() != UserRole.ADMIN || actor.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(AbuseErrorCode.ADMIN_REQUIRED);
		}
		return repository.findAll(filter, page);
	}
}
