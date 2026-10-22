package com.getddo.api.common.ratelimit;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 응모 전용 평가를 기존 일반 제한기에 추가한다. 비응모 정책은 tryAcquire를 유지한다. */
public interface EntryRateLimiter extends RateLimiter {

	/** DB 문맥 검증으로 생성된 ACTIVE만 읽는다. 쓰기·TTL 갱신을 하지 않는다. */
	Optional<EntryLimitDecision> findActiveEntry(UUID userId);

	/** 검증된 사용자만 호출한다. ACTIVE 재확인부터 최초 활성화까지 한 번에 수행하며 재호출하지 않는다. */
	EntryLimitDecision evaluateEntry(UUID userId, UUID eventScope, UUID verifiedEventId, Instant occurredAt,
			RateLimitProperties.Policy policy, EntryBlockProperties blockProperties);
}
