package com.getddo.core.abuse.repository;

import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;

/** 자동 차단 이력의 조회 경계. 저장·상세 기능은 해당 후속 작업에서 추가한다. */
public interface AbuseCaseRepository {

	/** 만료 여부와 관계없이 ENTRY 자동 차단만 blockedAt DESC·id DESC로 반환한다. */
	PageResult<AbuseCaseSummary> findAll(AbuseCaseFilter filter, PageQuery page);
}
