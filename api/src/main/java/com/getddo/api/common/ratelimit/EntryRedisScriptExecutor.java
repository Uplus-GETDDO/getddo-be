package com.getddo.api.common.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.lettuce.core.ClientOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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
			throw new IllegalStateException("응모 제한 Lua 리소스를 읽지 못했다.");
		}
	}

	/** EVALSHA fallback이나 애플리케이션 재시도 없이 한 번만 전송한다. */
	String execute(byte[] script, List<String> keys, List<String> args) {
		byte[][] values = java.util.stream.Stream.concat(keys.stream(), args.stream())
				.map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
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
		LettuceClientConfiguration current = original.getClientConfiguration();
		ClientOptions options = current.getClientOptions().orElseGet(ClientOptions::create)
				.mutate().autoReconnect(false)
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
		LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
				.commandTimeout(current.getCommandTimeout()).shutdownTimeout(current.getShutdownTimeout())
				.shutdownQuietPeriod(current.getShutdownQuietPeriod()).clientOptions(options);
		current.getClientResources().ifPresent(builder::clientResources);
		current.getClientName().ifPresent(builder::clientName);
		current.getRedisCredentialsProviderFactory().ifPresent(builder::redisCredentialsProviderFactory);
		if (current.isUseSsl()) {
			LettuceClientConfiguration.LettuceSslClientConfigurationBuilder ssl = builder.useSsl();
			if (!current.isVerifyPeer()) {
				ssl.disablePeerVerification();
			}
			if (current.isStartTls()) {
				ssl.startTls();
			}
			builder = ssl.and();
		}
		factory = new LettuceConnectionFactory(original.getStandaloneConfiguration(), builder.build());
		// 명령 종료 시 연결을 닫아 다음 HTTP 요청은 새 연결로 복구할 수 있게 한다.
		factory.setShareNativeConnection(false);
		factory.afterPropertiesSet();
		template = new StringRedisTemplate(factory);
		return template;
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
