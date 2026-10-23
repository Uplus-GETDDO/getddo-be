package com.getddo.api.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 API YAML이 공유 Redis 연결 설정을 보존하는지 확인한다. */
class RedisConnectionConfigTest {
	@Test
	@DisplayName("실제 YAML은 commons-pool2가 있어도 기존 공유 Redis 연결의 자동 풀링을 켜지 않는다")
	void sharedRedisKeepsOriginalConnectionMode() {
		// given
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withConfiguration(AutoConfigurations.of(
						DataRedisAutoConfiguration.class))
				.withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.password=",
						"spring.data.redis.ssl.enabled=false")
				.run(context -> {
					// when / then: 연결을 빌려 실제 Redis에 접근하지 않는다.
					assertThat(context).hasNotFailed();
					LettuceConnectionFactory original =
							context.getBean(LettuceConnectionFactory.class);
					assertThat(original.getShareNativeConnection()).isTrue();
					assertThat(original.getClientConfiguration()).isNotInstanceOf(LettucePoolingClientConfiguration.class);
				});
	}

}
