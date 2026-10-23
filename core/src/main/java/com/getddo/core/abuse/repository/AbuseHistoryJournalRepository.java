package com.getddo.core.abuse.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.getddo.core.abuse.domain.AbuseHistoryBacklog;
import com.getddo.core.abuse.domain.AbuseHistoryClaim;
import com.getddo.core.abuse.domain.AbuseHistoryQueue;
import com.getddo.core.abuse.domain.AbuseHistoryRetryState;

/** 전체 저널이 원본이며 일반·배치 대기는 복구 가능한 인덱스다. 저장소 구현은 소유권을 원자적으로 확인한다. */
public interface AbuseHistoryJournalRepository {
	/** 배치는 시작 시점의 cutoff까지만 처리한다. 일반 소비의 cutoff는 null이며 저장소 현재 시각을 사용한다. */
	Optional<AbuseHistoryClaim> claimNext(AbuseHistoryQueue queue, Instant cutoff, Duration lease, int scanSize);

	/** DB commit 이후에만 호출한다. 소유권이 바뀌었으면 현재 처리자가 완료 표시를 바꾸지 않는다. */
	boolean acknowledge(AbuseHistoryClaim claim);

	/** 최초 실패·다음 시각을 보존하며 일반 또는 배치 인덱스로 인계한다. */
	boolean reschedule(AbuseHistoryClaim claim, AbuseHistoryRetryState next);

	/** 처리 시각을 Redis 기준으로 읽어 다중 서버의 재시도·배치 기준을 맞춘다. */
	Instant now();

	/** 전역 인덱스·실제 저널을 스캔하고 파생 인덱스·정리 가능한 기록을 복구한다. */
	AbuseHistoryBacklog recover(int scanSize);
}
