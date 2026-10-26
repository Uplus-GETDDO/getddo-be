package com.getddo.core.abuse.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.ratelimit.EntryBlock;

/** 최초 차단의 동일 전달만 멱등 성공으로 확인하며 다른 PK·UNIQUE·FK 오류를 성공으로 삼키지 않는다. */
@Service
@RequiredArgsConstructor
public class AbuseCaseSaveService {
	private final AbuseCaseTransactionService transactions;

	/** 실패 rollback 뒤 별도 트랜잭션으로 같은 PK의 전체 내용을 확인한다. */
	@Transactional(propagation = Propagation.NEVER)
	public void save(EntryBlock block) {
		Objects.requireNonNull(block, "block");
		try {
			transactions.insert(block);
		} catch (DataIntegrityViolationException collision) {
			if (!transactions.isSameStored(block)) {
				throw collision;
			}
		}
	}
}
