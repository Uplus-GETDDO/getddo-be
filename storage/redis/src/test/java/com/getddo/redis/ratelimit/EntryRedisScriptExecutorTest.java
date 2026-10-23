package com.getddo.redis.ratelimit;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslVerifyMode;

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
		assertThat(pooled.getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
		assertThat(pooled.getClientName()).contains("entry-test");
	}

	@ParameterizedTest
	@EnumSource(SslVerifyMode.class)
	@DisplayName("전용 연결은 원본의 FULL·CA·NONE TLS 검증 모드를 그대로 보존한다")
	void preservesTlsVerificationMode(SslVerifyMode mode) {
		// given
		LettuceClientConfiguration current = LettuceClientConfiguration.builder().useSsl()
				.verifyPeer(mode).startTls().and().build();
		// when
		LettuceClientConfiguration copied = EntryRedisScriptExecutor.pooledConfiguration(current);
		// then
		assertThat(copied.isUseSsl()).isTrue();
		assertThat(copied.isStartTls()).isTrue();
		assertThat(copied.getVerifyMode()).isEqualTo(mode);
	}

}
