package com.getddo.core.abuse.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

import com.getddo.core.abuse.domain.AbuseHistoryBacklog;
import com.getddo.core.abuse.domain.AbuseHistoryClaim;
import com.getddo.core.abuse.domain.AbuseHistoryQueue;
import com.getddo.core.abuse.domain.AbuseHistoryRetryPolicy;
import com.getddo.core.abuse.domain.AbuseHistoryRetryState;
import com.getddo.core.abuse.repository.AbuseHistoryJournalRepository;

/** Redis 인계와 건별 DB commit을 연결한다. 이 객체 전체를 DB 트랜잭션으로 감싸지 않는다. */
@Slf4j
public final class AbuseHistoryService {
	private final AbuseHistoryJournalRepository journals;
	private final AbuseCaseSaveService saves;
	private final AbuseHistoryRetryPolicy retries;
	private final Duration lease;
	private final int scanSize;

	/** Redis 모드의 API 구성에서만 생성하며 core는 저장소 구현·Spring Boot 설정을 참조하지 않는다. */
	public AbuseHistoryService(AbuseHistoryJournalRepository journals, AbuseCaseSaveService saves,
			AbuseHistoryRetryPolicy retries, Duration lease, int scanSize) {
		if (lease == null || lease.toMillis() < 1 || scanSize < 1) {
			throw new IllegalArgumentException("저장 소유권 기간과 조회 크기는 양수여야 한다.");
		}
		this.journals = Objects.requireNonNull(journals, "journals");
		this.saves = Objects.requireNonNull(saves, "saves");
		this.retries = Objects.requireNonNull(retries, "retries");
		this.lease = lease;
		this.scanSize = scanSize;
	}

	/** 일반 대기 한 건을 처리하며 예산이 끝났으면 DB 호출 없이 배치로 넘긴다. */
	public boolean processNext() {
		return process(AbuseHistoryQueue.NORMAL, null);
	}

	/** 같은 배치의 cutoff를 재사용해 실행 중 새로 인계된 다음 배치까지 끌어오지 않는다. */
	public boolean processNextBatch(Instant cutoff) {
		return process(AbuseHistoryQueue.BATCH, cutoff);
	}

	/** 배치 시작 시각도 저널 저장소 시계를 기준으로 고정한다. */
	public Instant batchCutoff() {
		return journals.now();
	}

	/** 후속 요청이 없어도 저장 대기 복구와 완료·만료 저널 정리를 수행한다. */
	public AbuseHistoryBacklog recover() {
		return journals.recover(scanSize);
	}

	private boolean process(AbuseHistoryQueue queue, Instant cutoff) {
		Optional<AbuseHistoryClaim> candidate = journals.claimNext(queue, cutoff, lease, scanSize);
		if (candidate.isEmpty()) {
			return false;
		}
		AbuseHistoryClaim claim = candidate.get();
		if (!claim.isReadable()) {
			// 손상된 원본은 합성·삭제하지 않는다. 임시 소유권 만료 전 재선점도 하지 않는다.
			log.warn("Abuse history payload unreadable: blockId={}", claim.getId());
			return true;
		}
		if (queue == AbuseHistoryQueue.NORMAL && retries.exhausted(claim.getFirstFailureAt(), claim.getClaimedAt())) {
			reschedule(claim, retries.moveToBatch(claim));
			return true;
		}
		try {
			saves.save(claim.getBlock());
		} catch (RuntimeException failure) {
			log.warn("Abuse history DB save deferred: blockId={}, exceptionType={}",
					claim.getId(), failure.getClass().getSimpleName());
			// DB 실패만 예산에 반영한다. Redis 실패는 만료된 소유권을 다음 작업자가 이어받는다.
			reschedule(claim, retries.afterFailure(claim, journals.now()));
			return true;
		}
		// commit/중복 확인이 끝난 뒤의 완료 표시 실패를 DB 실패로 다시 집계하지 않는다.
		if (!journals.acknowledge(claim)) {
			log.warn("Abuse history ownership changed after commit: blockId={}", claim.getId());
		}
		return true;
	}

	/** 오래된 소유자가 강제 갱신하지 않으며 재예약 미반영을 운영에서 감지하게 한다. */
	private void reschedule(AbuseHistoryClaim claim, AbuseHistoryRetryState next) {
		if (!journals.reschedule(claim, next)) {
			log.warn("Abuse history reschedule skipped after ownership loss: blockId={}, queue={}",
					claim.getId(), next.getQueue());
		}
	}

}
