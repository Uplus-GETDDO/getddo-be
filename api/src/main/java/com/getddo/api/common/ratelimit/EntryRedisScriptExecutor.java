package com.getddo.api.common.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 응모 쓰기는 자동 재전송 없는 전용 연결에서 EVAL 한 번으로 실행한다. */
class EntryRedisScriptExecutor {
	private final StringRedisTemplate source;
	private LettuceConnectionFactory factory;
	private StringRedisTemplate template;

	EntryRedisScriptExecutor(StringRedisTemplate source) {
		this.source = source;
	}

	/** Lua 리소스를 읽으며 네트워크 접근은 하지 않는다. */
	static byte[] script(String name) {
		try {
			return new ClassPathResource("redis/" + name + ".lua").getContentAsByteArray();
		} catch (IOException exception) {
			throw new IllegalStateException("응모 제한 Lua 리소스를 읽지 못했다: " + name, exception);
		}
	}

	/** EVALSHA fallback이나 애플리케이션 재시도 없이 한 번만 전송한다. */
	String execute(byte[] script, List<String> keys, List<String> args) {
		byte[][] values = Stream.concat(keys.stream(), args.stream())
				.map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
		// 타임아웃은 미실행을 뜻하지 않는다. 카운터 증가가 끝난 뒤 응답만 유실될 수 있어 재전송하지 않는다.
		byte[] result = connectionTemplate().execute((RedisCallback<byte[]>) connection ->
				connection.scriptingCommands().eval(script, ReturnType.VALUE, keys.size(), values));
		if (result == null) {
			throw new IllegalStateException("응모 제한 결과가 없다.");
		}
		return new String(result, StandardCharsets.UTF_8);
	}

	/** 공유 Redis 설정은 보존하고, 원본 한 대 구성의 접속 설정만 전용 연결에 재사용한다. */
	private synchronized StringRedisTemplate connectionTemplate() {
		if (template != null) {
			return template;
		}
		if (!(source.getConnectionFactory() instanceof LettuceConnectionFactory original)
				|| original.isClusterAware() || original.isRedisSentinelAware()) {
			throw new IllegalStateException("응모 자동 차단은 단일 Redis Lettuce 연결이 필요하다.");
		}
		factory = new LettuceConnectionFactory(original.getStandaloneConfiguration(),
				pooledConfiguration(original.getClientConfiguration()));
		// 공유 연결 대신 전용 풀에서 대여·반납한다. 닫힌 연결은 검증에서 폐기하고 EVAL은 재전송하지 않는다.
		factory.setShareNativeConnection(false);
		try {
			factory.afterPropertiesSet();
			template = new StringRedisTemplate(factory);
			return template;
		} catch (RuntimeException failure) {
			try {
				factory.destroy();
			} catch (RuntimeException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			} finally {
				factory = null;
			}
			throw failure;
		}
	}

	/** 풀 용량과 대기 시간을 제한하고 대여·반납 시 연결 유효성을 검사한다. 명령 재전송은 계속 금지한다. */
	static LettuceClientConfiguration pooledConfiguration(LettuceClientConfiguration current) {
		GenericObjectPoolConfig<StatefulConnection<?, ?>> pool = new GenericObjectPoolConfig<>();
		pool.setMaxTotal(8);
		pool.setMaxIdle(8);
		pool.setMinIdle(0);
		pool.setTestOnBorrow(true);
		pool.setTestOnReturn(true);
		pool.setBlockWhenExhausted(true);
		pool.setMaxWait(current.getCommandTimeout());
		// 연결 복구 뒤 대기 명령이 다시 전송되는 것을 막는다. 끊어진 연결은 이후 요청에서 풀을 통해 교체한다.
		ClientOptions options = current.getClientOptions().orElseGet(ClientOptions::create)
				.mutate().autoReconnect(false)
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
		LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettucePoolingClientConfiguration.builder()
				.poolConfig(pool)
				.commandTimeout(current.getCommandTimeout()).shutdownTimeout(current.getShutdownTimeout())
				.shutdownQuietPeriod(current.getShutdownQuietPeriod()).clientOptions(options);
		current.getClientResources().ifPresent(builder::clientResources);
		current.getClientName().ifPresent(builder::clientName);
		current.getRedisCredentialsProviderFactory().ifPresent(builder::redisCredentialsProviderFactory);
		if (current.isUseSsl()) {
			LettuceClientConfiguration.LettuceSslClientConfigurationBuilder ssl = builder.useSsl();
			// FULL·CA·NONE을 그대로 복사해 원본 Redis 연결과 인증서 검증 수준을 맞춘다.
			ssl.verifyPeer(current.getVerifyMode());
			if (current.isStartTls()) {
				ssl.startTls();
			}
			builder = ssl.and();
		}
		return builder.build();
	}

	/** 애플리케이션 종료 시 이 평가기 소유 연결만 정리한다. */
	synchronized void destroy() {
		if (factory != null) {
			factory.destroy();
			factory = null;
			template = null;
		}
	}
}
