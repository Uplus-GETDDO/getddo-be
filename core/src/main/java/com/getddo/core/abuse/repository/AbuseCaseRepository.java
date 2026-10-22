package com.getddo.core.abuse.repository;

import java.util.Optional;
import java.util.UUID;

import com.getddo.core.abuse.domain.AbuseCaseDetail;
import com.getddo.core.abuse.domain.AbuseCaseFilter;
import com.getddo.core.abuse.domain.AbuseCaseSummary;
import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;

/** 자동 차단 이력의 목록·상세 조회 경계. 저장 기능은 후속 작업에서 추가한다. */
public interface AbuseCaseRepository {

	/** 만료 여부와 관계없이 ENTRY 자동 차단만 blockedAt DESC·id DESC로 반환한다. */
	PageResult<AbuseCaseSummary> findAll(AbuseCaseFilter filter, PageQuery page);

	/** 목록과 같은 조회 대상에서 최초 근거를 함께 복원한다. 대상 이력이 없으면 empty다. */
	Optional<AbuseCaseDetail> findById(UUID caseId);
}
