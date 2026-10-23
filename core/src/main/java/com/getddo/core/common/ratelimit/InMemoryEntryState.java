package com.getddo.core.common.ratelimit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.getddo.core.abuse.domain.AbuseEvidence;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.common.time.TimeProvider;

/** 메모리 모드의 사용자별 원자 평가다. DB 인계 대상은 만들지 않으며 서버 재시작 시 사라진다. */
final class InMemoryEntryState {
	private final TimeProvider time;
	private final ConcurrentHashMap<UUID, State> users = new ConcurrentHashMap<>();
	private final Object[] locks = new Object[256];
	private final AtomicLong calls = new AtomicLong();

	InMemoryEntryState(TimeProvider time) {
		this.time = time;
		for (int i = 0; i < locks.length; i++) {
			locks[i] = new Object();
		}
	}

	/** 고정 개수의 잠금을 공유해 사용자별 잠금 객체가 계속 쌓이는 것을 피한다. */
	private Object lock(UUID id) {
		return locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length];
	}

	/** 실제 유효한 차단만 읽으며 상태를 생성하거나 만료를 연장하지 않는다. */
	Optional<EntryLimitDecision> active(UUID id) {
		synchronized (lock(id)) {
			State state = users.get(id);
			Instant now = time.now();
			return state != null && state.block != null && now.isBefore(state.block.getBlockedUntil())
					? Optional.of(EntryLimitDecision.blocked(state.block, now)) : Optional.empty();
		}
	}

	/** 사용자·해당 이벤트 상태를 같은 임계 구역에서 교체한다. */
	EntryLimitDecision evaluate(UUID id, UUID event, UUID verifiedEvent, Instant received,
			RateLimitPolicy policy, EntryBlockPolicy settings) {
		settings.validateWindow(policy.getWindow());
		EntryLimitDecision decision;
		synchronized (lock(id)) {
			Instant now = time.now().truncatedTo(ChronoUnit.MILLIS);
			State state = users.computeIfAbsent(id, ignored -> new State());
			// 조회와 평가 사이 다른 요청이 차단했을 수 있으므로 잠금 안에서 다시 확인한다.
			if (state.block != null && now.isBefore(state.block.getBlockedUntil())) {
				return EntryLimitDecision.blocked(state.block, now);
			}
			state.block = null;
			state.events.values().removeIf(window -> !now.isBefore(window.end));
			state.user = window(state.user, now, policy);
			Window scoped = event == null ? null : window(state.events.get(event), now, policy);
			if (scoped != null) {
				state.events.put(event, scoped);
			}
			Window user = state.user;
			user.count = Math.min((long) policy.getLimit() + 1, user.count + 1);
			boolean denied = user.count > policy.getLimit();
			Instant retryAt = user.end;
			// 기존 일반 제한처럼 전체 한도를 넘으면 이벤트 일반 카운터는 증가시키지 않는다.
			if (!denied && scoped != null && policy.getScopedLimit() != null) {
				scoped.count = Math.min((long) policy.getScopedLimit() + 1, scoped.count + 1);
				denied = scoped.count > policy.getScopedLimit();
				retryAt = scoped.end;
			}
			// 어느 일반 한도에서 거절됐든 같은 요청을 전체·해당 이벤트 거절 횟수에 각각 한 번 반영한다.
			if (denied) {
				user.denied = (int) Math.min(Integer.MAX_VALUE, (long) user.denied + 1);
				if (scoped != null) {
					scoped.denied = (int) Math.min(Integer.MAX_VALUE, (long) scoped.denied + 1);
				}
				boolean overall = user.denied >= settings.getUserThreshold();
				boolean perEvent = scoped != null && policy.getScopedLimit() != null
						&& scoped.denied >= settings.getEventThreshold();
				if (overall || perEvent) {
					// 두 기준이 동시에 충족되면 USER 근거로 차단 한 건만 생성한다.
					Window representative = overall ? user : scoped;
					AbuseEvidence evidence = new AbuseEvidence(settings.getRuleVersion(),
							overall ? AbuseTriggerScope.USER : AbuseTriggerScope.EVENT,
							representative.start, representative.end,
							overall ? policy.getLimit() : policy.getScopedLimit(), representative.denied,
							overall ? settings.getUserThreshold() : settings.getEventThreshold());
					state.block = new EntryBlock(UUID.randomUUID(), id, verifiedEvent,
							received.truncatedTo(ChronoUnit.MICROS), now, now.plusMillis(settings.getDuration().toMillis()), evidence);
					EntryLimitDecision blocked = EntryLimitDecision.blocked(state.block, now);
					return new EntryLimitDecision(EntryLimitResult.BLOCKED, EntryRecordingStatus.RECORDED,
							blocked.getRetryAfterSeconds(), snapshot(user), scoped == null ? null : snapshot(scoped), state.block);
				}
			}
			decision = new EntryLimitDecision(denied ? EntryLimitResult.RATE_LIMITED : EntryLimitResult.ALLOWED,
					denied ? EntryRecordingStatus.RECORDED : EntryRecordingStatus.NOT_APPLICABLE,
					denied ? Math.max(1, (retryAt.toEpochMilli() - now.toEpochMilli() + 999) / 1000) : 0,
					snapshot(user), scoped == null ? null : snapshot(scoped), null);
		}
		if (calls.incrementAndGet() % 1024 == 0) {
			cleanup();
		}
		return decision;
	}

	private void cleanup() {
		Instant now = time.now();
		for (UUID id : users.keySet()) {
			synchronized (lock(id)) {
				State state = users.get(id);
				if (state != null && (state.block == null || !now.isBefore(state.block.getBlockedUntil()))
						&& !now.isBefore(state.user.end)
						&& state.events.values().stream().noneMatch(window -> now.isBefore(window.end))) {
					users.remove(id);
				}
			}
		}
	}

	/** 최초 요청의 종료 시각을 유지하고, 만료 뒤 다음 요청에서만 새 구간을 연다. */
	private static Window window(Window old, Instant now, RateLimitPolicy policy) {
		return old == null || !now.isBefore(old.end) ? new Window(now, now.plusMillis(policy.getWindow().toMillis())) : old;
	}

	private static EntryWindowSnapshot snapshot(Window window) {
		return new EntryWindowSnapshot(window.start, window.end, window.denied);
	}

	private static final class State {
		private Window user;
		private final Map<UUID, Window> events = new HashMap<>();
		private EntryBlock block;
	}

	private static final class Window {
		private final Instant start;
		private final Instant end;
		private long count;
		private int denied;

		private Window(Instant start, Instant end) {
			this.start = start;
			this.end = end;
		}
	}
}
