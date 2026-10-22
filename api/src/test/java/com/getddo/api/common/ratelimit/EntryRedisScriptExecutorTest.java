package com.getddo.api.common.ratelimit;

import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryRedisScriptExecutorTest {
	@Test
	@DisplayName("Lua 리소스 읽기 실패는 리소스 이름과 원래 I/O 원인을 보존한다")
	void resourceFailurePreservesCause() {
		// given / when / then
		assertThatThrownBy(() -> EntryRedisScriptExecutor.script("missing-entry-script"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("missing-entry-script")
				.hasCauseInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("전용 풀은 용량·대기·연결 검증을 제한하면서 자동 재전송을 금지한다")
	void boundedPoolDoesNotReconnect() {
		// given
		Duration timeout = Duration.ofMillis(300);
		LettuceClientConfiguration current = LettuceClientConfiguration.builder().commandTimeout(timeout)
				.clientName("entry-test").useSsl().and().build();
		// when
		LettuceClientConfiguration configured = EntryRedisScriptExecutor.pooledConfiguration(current);
		// then
		assertThat(configured).isInstanceOf(LettucePoolingClientConfiguration.class);
		LettucePoolingClientConfiguration pooled = (LettucePoolingClientConfiguration) configured;
		assertThat(pooled.getPoolConfig().getMaxTotal()).isEqualTo(8);
		assertThat(pooled.getPoolConfig().getMaxIdle()).isEqualTo(8);
		assertThat(pooled.getPoolConfig().getMinIdle()).isZero();
		assertThat(pooled.getPoolConfig().getMaxWaitDuration()).isEqualTo(timeout);
		assertThat(pooled.getPoolConfig().getTestOnBorrow()).isTrue();
		assertThat(pooled.getPoolConfig().getTestOnReturn()).isTrue();
		assertThat(pooled.getPoolConfig().getBlockWhenExhausted()).isTrue();
		ClientOptions options = pooled.getClientOptions().orElseThrow();
		assertThat(options.isAutoReconnect()).isFalse();
		assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
		assertThat(pooled.getCommandTimeout()).isEqualTo(timeout);
		assertThat(pooled.isUseSsl()).isTrue();
		assertThat(pooled.isVerifyPeer()).isTrue();
		assertThat(pooled.getClientName()).contains("entry-test");
	}
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
