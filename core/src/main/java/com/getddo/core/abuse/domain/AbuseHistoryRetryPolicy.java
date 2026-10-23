package com.getddo.core.abuse.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/** 최초 DB 실패부터의 예산과 간격을 적용한다. 차단 기간·현재 설정으로 최초 근거를 재계산하지 않는다. */
public final class AbuseHistoryRetryPolicy {
	private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalTime BATCH_TIME = LocalTime.of(6, 0);
	private final Duration budget;
	private final List<Duration> delays;

	/** 운영 설정의 양수 예산과 증가하는 재시도 간격만 허용한다. 마지막 간격을 이후 상한으로 사용한다. */
	public AbuseHistoryRetryPolicy(Duration budget, List<Duration> delays) {
		if (budget == null || budget.toMillis() < 1 || delays == null || delays.isEmpty()) {
			throw new IllegalArgumentException("저장 재시도 기간과 간격이 필요하다.");
		}
		Duration previous = Duration.ZERO;
		for (Duration delay : delays) {
			if (delay == null || delay.toMillis() < 1 || delay.compareTo(previous) < 0) {
				throw new IllegalArgumentException("저장 재시도 간격은 양수이며 감소하지 않아야 한다.");
			}
			previous = delay;
		}
		this.budget = budget;
		this.delays = List.copyOf(delays);
	}

	/** 예산 경계와 같은 시각부터 일반 DB 재시도를 실행하지 않는다. */
	public boolean exhausted(Instant firstFailureAt, Instant now) {
		return firstFailureAt != null && !now.isBefore(firstFailureAt.plus(budget));
	}

	/** DB 저장 실패만 횟수에 반영한다. 배치 실패는 다음 날 배치로 보존한다. */
	public AbuseHistoryRetryState afterFailure(AbuseHistoryClaim claim, Instant now) {
		Objects.requireNonNull(now, "now");
		Instant first = claim.getFirstFailureAt() == null ? now : claim.getFirstFailureAt();
		int count = (int) Math.min(Integer.MAX_VALUE, (long) claim.getFailureCount() + 1);
		Duration delay = delays.get(Math.min(count - 1, delays.size() - 1));
		Instant next = now.plus(delay);
		if (claim.getQueue() == AbuseHistoryQueue.BATCH || !next.isBefore(first.plus(budget))) {
			return new AbuseHistoryRetryState(first, count, nextBatchAfter(now), AbuseHistoryQueue.BATCH);
		}
		return new AbuseHistoryRetryState(first, count, next, AbuseHistoryQueue.NORMAL);
	}

	/** 만료된 일반 재시도는 DB를 다시 호출하지 않고 기존 실패 횟수로 배치에 인계한다. */
	public AbuseHistoryRetryState moveToBatch(AbuseHistoryClaim claim) {
		return new AbuseHistoryRetryState(claim.getFirstFailureAt(), claim.getFailureCount(),
				nextBatchAfter(claim.getClaimedAt()), AbuseHistoryQueue.BATCH);
	}

	/** 현재 시각보다 뒤에 있는 KST 오전 6시를 구한다. 이미 지난 배치를 즉시 재실행하지 않는다. */
	public Instant nextBatchAfter(Instant now) {
		ZonedDateTime local = now.atZone(BATCH_ZONE);
		ZonedDateTime next = local.toLocalDate().atTime(BATCH_TIME).atZone(BATCH_ZONE);
		return (next.toInstant().isAfter(now) ? next : next.plusDays(1)).toInstant();
	}
}
