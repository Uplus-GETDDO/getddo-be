package com.getddo.core.common.ratelimit;

import java.time.Duration;

import lombok.Getter;

/**
 * 키별 요청 횟수를 세고 한도를 넘었는지 판정한다.
 *
 * <p>구현은 교체할 수 있다. 서버가 한 대인 동안은 메모리 구현({@link InMemoryRateLimiter})을 쓰고, 여러 대로 늘리면 공유
 * 저장소(Redis) 구현으로 바꾼다. 같은 키의 호출은 동시에 와도 횟수가 정확히 세어져야 한다.</p>
 */
public interface RateLimiter {

	/**
	 * 요청 한 건을 센다. 한도 안이면 허용하고, 넘으면 거절하며 다시 시도할 수 있을 때까지의 시간을 돌려준다.
	 *
	 * @param key 한도를 나누는 기준(정책·사용자·범위를 합친 값)
	 * @param limit 창 안에서 허용할 최대 요청 수
	 * @param window 창의 길이
	 */
	Decision tryAcquire(String key, int limit, Duration window);

	/** 일반 제한의 허용 여부와 재시도 시간을 함께 보관한다. */
	@Getter
	final class Decision {
		private final boolean allowed;
		private final long retryAfterSeconds;

		/** 허용은 대기 0, 거절은 대기 1초 이상이라는 불변식을 유지한다. */
		public Decision(boolean allowed, long retryAfterSeconds) {
			if (allowed ? retryAfterSeconds != 0 : retryAfterSeconds < 1) {
				throw new IllegalArgumentException("허용은 재시도 시간이 0, 거절은 1초 이상이어야 한다.");
			}
			this.allowed = allowed;
			this.retryAfterSeconds = retryAfterSeconds;
		}

		/** 집계 한도 안의 요청을 허용한다. */
		public static Decision allow() {
			return new Decision(true, 0);
		}

		/** 초 단위 재시도 시간이 포함된 일반 거절을 만든다. */
		public static Decision deny(long retryAfterSeconds) {
			return new Decision(false, Math.max(1, retryAfterSeconds));
		}
	}
}
