package com.getddo.core.common.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.getddo.core.common.time.TimeProvider;

import lombok.Getter;

/**
 * 서버 메모리에서 세는 고정 창 방식의 {@link RateLimiter}.
 *
 * <p>키마다 창이 시작된 시각과 횟수를 들고 있다. 창이 끝나면 첫 요청에서 처음부터 다시 센다. 한 서버 안에서만 유효하므로
 * 서버를 여러 대로 늘리면 공유 저장소 구현으로 바꾼다. 서버가 다시 시작하면 횟수도 사라진다. 오래된 키는 일정 호출마다
 * 지워 메모리가 무한히 늘지 않게 한다. 이 정리는 그 호출을 처리하는 요청 스레드가 전체 키를 한 번 훑으므로 키가 매우 많으면
 * 그 요청이 느려질 수 있다. 사용자 식별이 UUID 형식 검증뿐이라 값을 바꿔 보내는 요청으로 키가 늘 수 있지만, 키는 창 길이가
 * 지나면 정리되고 시연 규모(서버 한 대)에서는 문제가 되지 않는다. 규모가 커지면 키 수 상한이나 별도 정리 스레드를 둔다.</p>
 */
public class InMemoryRateLimiter implements EntryRateLimiter {

	/** 이 횟수만큼 호출될 때마다 끝난 창을 지운다. */
	private static final long CLEANUP_EVERY = 1024;

	private final TimeProvider timeProvider;
	private final InMemoryEntryState entries;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final AtomicLong calls = new AtomicLong();

	public InMemoryRateLimiter(TimeProvider timeProvider) {
		this.timeProvider = timeProvider;
		this.entries = new InMemoryEntryState(timeProvider);
	}

	@Override
	public Decision tryAcquire(String key, int limit, Duration window) {
		long now = timeProvider.now().toEpochMilli();
		long windowMillis = window.toMillis();
		// 한도를 넘은 요청은 횟수를 더 올리지 않아 공격 요청이 많아도 값이 계속 커지지 않는다.
		Window current = windows.compute(key, (k, existing) -> {
			if (existing == null || now >= existing.getEndMillis()) {
				return new Window(now + windowMillis, 1);
			}
			return existing.getCount() > limit ? existing : new Window(existing.getEndMillis(), existing.getCount() + 1);
		});
		if (calls.incrementAndGet() % CLEANUP_EVERY == 0) {
			windows.values().removeIf(stale -> now >= stale.getEndMillis());
		}
		if (current.getCount() <= limit) {
			return Decision.allow();
		}
		return Decision.deny((long) Math.ceil((current.getEndMillis() - now) / 1000.0));
	}

	@Override
	public Optional<EntryLimitDecision> findActiveEntry(UUID userId) {
		return entries.active(userId);
	}

	@Override
	public EntryLimitDecision evaluateEntry(UUID userId, UUID eventScope,
			UUID verifiedEventId, Instant occurredAt, RateLimitPolicy policy,
			EntryBlockPolicy blockProperties) {
		return entries.evaluate(userId, eventScope, verifiedEventId, occurredAt, policy, blockProperties);
	}

	/** 창이 끝나는 시각과 그 창에서 센 요청 수. */
	@Getter
	private static final class Window {
		private final long endMillis;
		private final int count;

		private Window(long endMillis, int count) {
			this.endMillis = endMillis;
			this.count = count;
		}
	}
}
