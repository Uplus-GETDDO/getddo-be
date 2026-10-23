package com.getddo.core.common.ratelimit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.getddo.core.abuse.domain.AbuseEvidence;

import lombok.Getter;

/** GD-291에 인계할 최초 차단 payload다. 재전달 때 ID·시각·근거를 다시 만들지 않는다. */
@Getter
public final class EntryBlock {
	private final UUID id;
	private final UUID userId;
	private final UUID eventId;
	private final Instant occurredAt;
	private final Instant blockedAt;
	private final Instant blockedUntil;
	private final AbuseEvidence evidence;

	public EntryBlock(UUID id, UUID userId, UUID eventId, Instant occurredAt, Instant blockedAt,
			Instant blockedUntil, AbuseEvidence evidence) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(occurredAt, "occurredAt");
		Objects.requireNonNull(evidence, "evidence");
		if (blockedAt == null || blockedUntil == null || !blockedAt.isBefore(blockedUntil)) {
			throw new IllegalArgumentException("차단 시작·종료 시각이 올바르지 않다.");
		}
		this.id = id;
		this.userId = userId;
		this.eventId = eventId;
		this.occurredAt = occurredAt;
		this.blockedAt = blockedAt;
		this.blockedUntil = blockedUntil;
		this.evidence = evidence;
	}
}
