package com.getddo.api.common.ratelimit;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.getddo.core.common.ratelimit.RateLimitPolicy;

/**
 * {@code getddo.rate-limit} 설정을 바인딩하는 불변 설정 객체다.
 *
 * <p>정책 이름은 {@link RateLimited#policy()}와 같다. 한도는 운영 수치라서 코드에 두지 않고 설정으로 조정한다.</p>
 *
 * @param enabled 끄면 어떤 요청도 제한하지 않는다. 시연·부하 테스트에서 한도를 풀 때 쓴다
 * @param policies 정책 이름별 한도. 설정에 없는 정책은 제한하지 않는다
 */
@ConfigurationProperties("getddo.rate-limit")
public record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue Map<String, Policy> policies) {

	public RateLimitProperties {
		policies = policies == null ? Map.of() : Map.copyOf(policies);
	}

	/**
	 * 한 정책의 한도.
	 *
	 * @param limit 창 안에서 사용자 한 명이 보낼 수 있는 최대 요청 수
	 * @param window 창의 길이. 창이 끝나면 횟수가 처음부터 다시 센다
	 * @param scopedLimit 경로 변수(이벤트·미션 등) 하나당 한도. 비워 두면 범위별 한도를 두지 않는다
	 */
	public record Policy(
			int limit,
			@DefaultValue("1m") Duration window,
			Integer scopedLimit) {

		public Policy {
			new RateLimitPolicy(limit, window, scopedLimit);
		}

		/** 설정 값을 저장소와 공유하는 순수 정책으로 변환한다. */
		public RateLimitPolicy toDomain() {
			return new RateLimitPolicy(limit, window, scopedLimit);
		}
	}
}
