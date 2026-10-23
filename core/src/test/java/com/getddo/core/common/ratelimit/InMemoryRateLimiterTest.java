package com.getddo.core.common.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.getddo.core.common.time.TimeProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRateLimiterTest {

	private static final Duration MINUTE = Duration.ofMinutes(1);

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T03:00:00Z"));
	private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(new TimeProvider(clock));

	@Test
	@DisplayName("한도 안의 요청은 허용하고 한도를 넘으면 거절하며 창이 끝날 때까지 남은 초를 돌려준다")
	void allowsUpToLimitThenDeniesWithRetryAfter() {
		// given
		clock.set(Instant.parse("2026-10-15T03:00:00Z"));
		// when
		// then
		for (int i = 0; i < 3; i++) {
			assertThat(limiter.tryAcquire("k", 3, MINUTE).isAllowed()).isTrue();
			clock.advance(Duration.ofSeconds(1));
		}
		RateLimiter.Decision denied = limiter.tryAcquire("k", 3, MINUTE);
		assertThat(denied.isAllowed()).isFalse();
		// 첫 요청 후 60초 창에서 3초가 지났으므로 57초가 남는다.
		assertThat(denied.getRetryAfterSeconds()).isEqualTo(57);
	}

	@Test
	@DisplayName("창이 끝나면 횟수를 처음부터 다시 센다")
	void resetsAfterWindow() {
		// given
		for (int i = 0; i < 4; i++) {
			limiter.tryAcquire("k", 2, MINUTE);
		}
		assertThat(limiter.tryAcquire("k", 2, MINUTE).isAllowed()).isFalse();
		// when
		clock.advance(MINUTE);
		// then
		assertThat(limiter.tryAcquire("k", 2, MINUTE).isAllowed()).isTrue();
		assertThat(limiter.tryAcquire("k", 2, MINUTE).isAllowed()).isTrue();
		assertThat(limiter.tryAcquire("k", 2, MINUTE).isAllowed()).isFalse();
	}

	@Test
	@DisplayName("창이 끝나기 직전까지는 같은 창이고 끝나는 순간부터 새 창이다")
	void windowBoundaryIsExclusive() {
		// given
		limiter.tryAcquire("k", 1, MINUTE);
		// when
		clock.advance(MINUTE.minusMillis(1));
		RateLimiter.Decision justBefore = limiter.tryAcquire("k", 1, MINUTE);
		clock.advance(Duration.ofMillis(1));
		RateLimiter.Decision atEnd = limiter.tryAcquire("k", 1, MINUTE);
		// then
		assertThat(justBefore.isAllowed()).isFalse();
		assertThat(justBefore.getRetryAfterSeconds()).isEqualTo(1);
		assertThat(atEnd.isAllowed()).isTrue();
	}

	@Test
	@DisplayName("키마다 따로 센다")
	void keysAreIndependent() {
		// given
		limiter.tryAcquire("a", 1, MINUTE);
		// when
		// then
		assertThat(limiter.tryAcquire("a", 1, MINUTE).isAllowed()).isFalse();
		assertThat(limiter.tryAcquire("b", 1, MINUTE).isAllowed()).isTrue();
	}

	@Test
	@DisplayName("거절된 요청이 계속 와도 창은 첫 요청으로부터 정확히 창 길이만큼만 이어지고 그 순간 다시 허용한다")
	void deniedRequestsDoNotExtendTheWindow() {
		// given
		Instant first = clock.instant();
		limiter.tryAcquire("k", 1, MINUTE);
		for (int i = 0; i < 40; i++) {
			clock.advance(Duration.ofMillis(1_400));
			assertThat(limiter.tryAcquire("k", 1, MINUTE).isAllowed()).isFalse();
		}
		// when
		clock.set(first.plus(MINUTE).minusMillis(1));
		RateLimiter.Decision justBeforeEnd = limiter.tryAcquire("k", 1, MINUTE);
		clock.set(first.plus(MINUTE));
		RateLimiter.Decision atEnd = limiter.tryAcquire("k", 1, MINUTE);
		// then
		assertThat(justBeforeEnd.isAllowed()).isFalse();
		assertThat(atEnd.isAllowed()).isTrue();
	}

	@Test
	@DisplayName("허용과 거절의 재시도 시간 불변식을 지키지 않는 판정은 만들 수 없다")
	void decisionRejectsInconsistentRetryAfter() {
		// given
		// when
		// then
		assertThatThrownBy(() -> new RateLimiter.Decision(true, 3)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimiter.Decision(false, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThat(RateLimiter.Decision.deny(0).getRetryAfterSeconds()).isEqualTo(1);
		assertThat(RateLimiter.Decision.allow().getRetryAfterSeconds()).isZero();
	}

	@Test
	@DisplayName("동시에 요청해도 한도 개수만큼만 허용한다")
	void concurrentCallsAreCountedExactly() throws Exception {
		// given
		int callers = 40;
		int limit = 10;
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		CountDownLatch ready = new CountDownLatch(callers);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger allowed = new AtomicInteger();
		List<Future<?>> tasks = new ArrayList<>();
		try {
			for (int i = 0; i < callers; i++) {
				tasks.add(executor.submit(() -> {
					ready.countDown();
					try {
						go.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						return;
					}
					if (limiter.tryAcquire("shared", limit, MINUTE).isAllowed()) {
						allowed.incrementAndGet();
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			// when
			go.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
			// 작업이 예외로 끝났다면 허용 개수가 우연히 맞아도 실패로 드러나도록 모든 작업의 정상 종료를 확인한다.
			for (Future<?> task : tasks) {
				task.get(1, TimeUnit.SECONDS);
			}
		} finally {
			go.countDown();
			executor.shutdownNow();
		}
		// then
		assertThat(allowed.get()).isEqualTo(limit);
	}
}
