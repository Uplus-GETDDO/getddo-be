package com.getddo.core.abuse.repository;

import com.getddo.core.common.ratelimit.EntryBlock;

/** 조회용 Entity·근거 Mapper를 재사용하는 최초 차단 이력 저장 경계다. */
public interface AbuseCaseWriteRepository {
	/** 기존 행을 갱신하지 않고 최초 ID·시각·근거를 삽입한다. 트랜잭션은 호출 Service가 관리한다. */
	void insert(EntryBlock block);

	/** 같은 PK의 모든 최초 저장값과 근거가 일치할 때만 중복 전달을 성공으로 확인한다. */
	boolean isSameStored(EntryBlock block);
}
