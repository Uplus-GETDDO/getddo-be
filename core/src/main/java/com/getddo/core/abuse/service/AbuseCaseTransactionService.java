package com.getddo.core.abuse.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.abuse.repository.AbuseCaseWriteRepository;
import com.getddo.core.common.ratelimit.EntryBlock;

/** 이력 한 건의 삽입·중복 확인을 서로 독립된 DB 트랜잭션으로 수행한다. */
@Service
@RequiredArgsConstructor
public class AbuseCaseTransactionService {
	private final AbuseCaseWriteRepository repository;

	/** 메서드의 프록시 반환 전에 commit되므로 호출자는 그 이후에만 Redis 완료 표시를 한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void insert(EntryBlock block) {
		repository.insert(block);
	}

	/** 실패한 삽입 트랜잭션을 재사용하지 않고 기존 행의 최신 commit 값을 비교한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.READ_COMMITTED)
	public boolean isSameStored(EntryBlock block) {
		return repository.isSameStored(block);
	}
}
