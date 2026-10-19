package com.getddo.core.entry.repository;

import java.util.Optional;
import java.util.UUID;

import com.getddo.core.common.pagination.PageQuery;
import com.getddo.core.common.pagination.PageResult;
import com.getddo.core.entry.domain.AdminEntryFilter;
import com.getddo.core.entry.domain.Entry;
import com.getddo.core.entry.domain.EntryHistoryQuery;
import com.getddo.core.entry.domain.EntrySummary;

/** 응모 요청의 결과(접수 완료와 거절 이력) 저장소. 결과는 추가만 하며 수정·삭제하지 않는다. */
public interface EntryRepository {

	/** 응모 ID로 응모 결과를 조회한다. 없으면 비어 있다. */
	Optional<Entry> findById(UUID id);

	/**
	 * 응모 결과를 저장하고 즉시 DB에 반영한다. 차감 이력이 접수된 응모 행을 참조하므로 차감 전에 반영해야 한다.
	 * 같은 ID의 결과가 이미 있으면 PK 위반 예외가 난다.
	 */
	Entry save(Entry entry);

	/**
	 * 사용자의 응모 이력을 결과 기록 시각 내림차순(같으면 ID 내림차순)으로 조회한다. 접수 건과 거절 건을 모두 포함하며
	 * 이벤트 제목을 함께 돌려준다. 요청 페이지가 범위를 넘으면 빈 목록과 전체 건수를 돌려준다.
	 */
	PageResult<EntrySummary> findByUser(UUID userId, EntryHistoryQuery query, PageQuery page);

	/** 모든 사용자의 응모 이력을 조건에 맞게 {@link #findByUser}와 같은 순서로 조회한다. 관리자 조회(AO06)용이다. */
	PageResult<EntrySummary> findAll(AdminEntryFilter filter, PageQuery page);
}
