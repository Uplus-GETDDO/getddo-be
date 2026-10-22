package com.getddo.api.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.getddo.core.abuse.domain.AbuseTriggerScope;
import com.getddo.core.common.time.TimeProvider;
import static org.assertj.core.api.Assertions.*;

class EntryRateLimiterTest {
	private static final UUID USER = UUID.randomUUID();
	private static final UUID EVENT = UUID.randomUUID();
	private static final Instant START = Instant.parse("2026-10-22T01:00:00Z");
	private final MutableClock clock = new MutableClock();
	private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(new TimeProvider(clock));
	private final RateLimitProperties.Policy policy = new RateLimitProperties.Policy(4, Duration.ofMinutes(1), 2);
	private final EntryBlockProperties settings = new EntryBlockProperties(4, 2, Duration.ofMinutes(10), "entry-v1");

	private EntryLimitDecision request(UUID scope) {
		return limiter.evaluateEntry(USER, scope, scope, clock.instant(), policy, settings);
	}

	@Test
	@DisplayName("허용 요청은 거절 횟수를 올리지 않고 이벤트 임계값 도달 요청에서 즉시 차단한다")
	void eventThreshold() {
		// given
		assertThat(request(EVENT).getUserWindow().getRejectedCount()).isZero();
		request(EVENT);
		// when
		EntryLimitDecision before = request(EVENT);
		EntryLimitDecision blocked = request(EVENT);
		// then
		assertThat(before.getResult()).isEqualTo(EntryLimitResult.RATE_LIMITED);
		assertThat(before.getUserWindow().getRejectedCount()).isEqualTo(1);
		assertThat(blocked.getResult()).isEqualTo(EntryLimitResult.BLOCKED);
		assertThat(blocked.getRecordingStatus()).isEqualTo(EntryRecordingStatus.RECORDED);
		assertThat(blocked.getUserWindow().getRejectedCount()).isEqualTo(2);
		assertThat(blocked.getRetryAfterSeconds()).isEqualTo(600);
		assertThat(blocked.getBlock().getEvidence().getTriggerScope()).isEqualTo(AbuseTriggerScope.EVENT);
		assertThat(blocked.getBlock().getEvidence().getRequestLimit()).isEqualTo(2);
	}

	@Test
	@DisplayName("전체 제한 선행 거절에서도 새 이벤트의 구간을 만들고 양쪽 거절을 한 번씩 센다")
	void overallDenialCountsEvent() {
		// given
		for (int i = 0; i < 4; i++) {
			request(UUID.randomUUID());
		}
		clock.advance(Duration.ofSeconds(5));
		// when
		EntryLimitDecision decision = request(EVENT);
		// then
		assertThat(decision.getUserWindow().getRejectedCount()).isEqualTo(1);
		assertThat(decision.getEventWindow().getRejectedCount()).isEqualTo(1);
		assertThat(decision.getEventWindow().getStartedAt()).isEqualTo(START.plusSeconds(5));
		assertThat(decision.getEventWindow().getEndsAt()).isEqualTo(START.plusSeconds(65));
		assertThat(decision.getRetryAfterSeconds()).isEqualTo(55);
	}

	@Test
	@DisplayName("두 거절 기준 동시 충족 시 USER 근거와 한 차단만 보존한다")
	void simultaneousUsesUser() {
		// given
		RateLimitProperties.Policy one = new RateLimitProperties.Policy(1, Duration.ofSeconds(5), 1);
		EntryBlockProperties block = new EntryBlockProperties(1, 1, Duration.ofSeconds(10), "v2");
		limiter.evaluateEntry(USER, EVENT, null, START, one, block);
		// when
		EntryLimitDecision decision = limiter.evaluateEntry(USER, EVENT, null, START, one, block);
		// then
		assertThat(decision.getBlock().getEvidence().getTriggerScope()).isEqualTo(AbuseTriggerScope.USER);
		assertThat(decision.getBlock().getEvidence().getWindowEndsAt()).isEqualTo(START.plusSeconds(5));
		assertThat(decision.getBlock().getEventId()).isNull();
		assertThat(limiter.findActiveEntry(USER).orElseThrow().getBlock().getId()).isEqualTo(decision.getBlock().getId());
	}

	@Test
	@DisplayName("차단 중 재평가·빠른 조회는 ID·근거·종료 시각을 유지하고 만료 뒤 새 구간을 시작한다")
	void blockedIsImmutableAndExpires() {
		// given
		for (int i = 0; i < 3; i++) {
			request(EVENT);
		}
		EntryBlock original = request(EVENT).getBlock();
		clock.advance(Duration.ofSeconds(59));
		// when
		EntryLimitDecision repeated = request(UUID.randomUUID());
		// then
		assertThat(repeated.getBlock()).isSameAs(original);
		assertThat(repeated.getUserWindow()).isNull();
		assertThat(repeated.getRetryAfterSeconds()).isEqualTo(541);
		assertThat(limiter.findActiveEntry(USER).orElseThrow().getBlock()).isSameAs(original);
		clock.advance(Duration.ofSeconds(541));
		assertThat(limiter.findActiveEntry(USER)).isEmpty();
		EntryLimitDecision after = request(EVENT);
		assertThat(after.getResult()).isEqualTo(EntryLimitResult.ALLOWED);
		assertThat(after.getUserWindow().getStartedAt()).isEqualTo(START.plusSeconds(600));
		assertThat(after.getUserWindow().getRejectedCount()).isZero();
	}

	@Test
	@DisplayName("첫 429부터 새 구간을 만들지 않고 정확한 구간 경계에서 거절 횟수를 초기화한다")
	void denialSharesOriginalWindow() {
		// given
		request(EVENT);
		request(EVENT);
		clock.advance(Duration.ofSeconds(59));
		EntryLimitDecision denied = request(EVENT);
		// when
		clock.advance(Duration.ofSeconds(1));
		EntryLimitDecision next = request(EVENT);
		// then
		assertThat(denied.getRetryAfterSeconds()).isEqualTo(1);
		assertThat(denied.getEventWindow().getEndsAt()).isEqualTo(START.plusSeconds(60));
		assertThat(next.getResult()).isEqualTo(EntryLimitResult.ALLOWED);
		assertThat(next.getUserWindow().getRejectedCount()).isZero();
	}

	@Test
	@DisplayName("이벤트 scope가 없으면 사용자 전체 거절 임계값만 적용한다")
	void userOnly() {
		// given
		for (int i = 0; i < 7; i++) {
			request(null);
		}
		// when
		EntryLimitDecision decision = request(null);
		// then
		assertThat(decision.getBlock().getEvidence().getTriggerScope()).isEqualTo(AbuseTriggerScope.USER);
		assertThat(decision.getBlock().getEventId()).isNull();
		assertThat(decision.getBlock().getEvidence().getRejectedCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("동시 요청 모두 최초 차단 한 건을 공유한다")
	void concurrentRequests() throws Exception {
		// given
		for (int i = 0; i < 3; i++) {
			request(EVENT);
		}
		CountDownLatch ready = new CountDownLatch(40);
		CountDownLatch start = new CountDownLatch(1);
		try (java.util.concurrent.ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Callable<EntryLimitDecision>> jobs = java.util.stream.IntStream.range(0, 40)
					.mapToObj(i -> (Callable<EntryLimitDecision>) () -> {
						ready.countDown();
						if (!start.await(5, TimeUnit.SECONDS)) {
							throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
						}
						return request(EVENT);
					}).toList();
			// when
			List<java.util.concurrent.Future<EntryLimitDecision>> futures = new java.util.ArrayList<>();
			for (Callable<EntryLimitDecision> job : jobs) {
				futures.add(pool.submit(job));
			}
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				start.countDown();
			}
			List<EntryLimitDecision> decisions = new java.util.ArrayList<>();
			for (java.util.concurrent.Future<EntryLimitDecision> future : futures) {
				decisions.add(future.get(10, TimeUnit.SECONDS));
			}
			// then
			assertThat(decisions).allMatch(d -> d.getResult() == EntryLimitResult.BLOCKED);
			assertThat(decisions).filteredOn(d -> d.getRecordingStatus() == EntryRecordingStatus.RECORDED).hasSize(1);
			assertThat(decisions).filteredOn(d -> d.getRecordingStatus() == EntryRecordingStatus.NOT_APPLICABLE).hasSize(39);
			assertThat(decisions).extracting(d -> d.getBlock().getId()).containsOnly(decisions.getFirst().getBlock().getId());
		}
	}

	@Test
	@DisplayName("응모 차단은 다른 정책의 일반 제한과 다른 사용자를 막지 않는다")
	void isolated() {
		// given
		for (int i = 0; i < 4; i++) {
			request(EVENT);
		}
		// when / then
		assertThat(limiter.tryAcquire("rl:attendance:"+USER, 5, Duration.ofMinutes(1)).allowed()).isTrue();
		assertThat(limiter.findActiveEntry(UUID.randomUUID())).isEmpty();
	}

	@Test
	@DisplayName("차단 설정은 양수·필수 버전·집계시간 이하 금지를 검증한다")
	void invalidSettings() {
		// given / when / then
		assertThatThrownBy(() -> new EntryBlockProperties(0, 1, Duration.ofMinutes(1), "v")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntryBlockProperties(1, 0, Duration.ofMinutes(1), "v")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntryBlockProperties(1, 1, Duration.ZERO, "v")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntryBlockProperties(1, 1, Duration.ofMinutes(1), " ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> settings.validateWindow(Duration.ofMinutes(11))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> settings.validateWindow(Duration.ofNanos(1))).isInstanceOf(IllegalArgumentException.class);
	}

	private static final class MutableClock extends Clock {
		private volatile Instant now = START;
		void advance(Duration duration) {
			now = now.plus(duration);
		}
		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}
}
