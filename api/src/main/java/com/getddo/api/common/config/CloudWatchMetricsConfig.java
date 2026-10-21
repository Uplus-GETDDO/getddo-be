package com.getddo.api.common.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * 명시적으로 활성화한 서버에서만 풀·JVM·기존 공용 스케줄러 지표를 CloudWatch로 전송한다.
 * 자격 증명은 클라이언트 생성 시 조회하지 않으며 전송 실패는 백그라운드 publisher에서 처리한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "getddo.metrics.enabled", havingValue = "true")
public class CloudWatchMetricsConfig {

	private static final Map<String, Set<String>> DIMENSIONS = Map.ofEntries(
		Map.entry("hikaricp.connections.active", Set.of("pool")),
		Map.entry("hikaricp.connections.idle", Set.of("pool")),
		Map.entry("hikaricp.connections.max", Set.of("pool")),
		Map.entry("hikaricp.connections.pending", Set.of("pool")),
		Map.entry("hikaricp.connections.acquire", Set.of("pool")),
		Map.entry("hikaricp.connections.timeout", Set.of("pool")),
		Map.entry("tomcat.threads.busy", Set.of("name")),
		Map.entry("tomcat.threads.current", Set.of("name")),
		Map.entry("tomcat.threads.config.max", Set.of("name")),
		Map.entry("jvm.memory.used", Set.of("area", "id")),
		Map.entry("jvm.memory.max", Set.of("area", "id")),
		Map.entry("jvm.gc.pause", Set.of("action", "cause", "gc")),
		Map.entry("executor.active", Set.of("name")),
		Map.entry("executor.queued", Set.of("name")),
		Map.entry("executor.completed", Set.of("name"))
	);

	private final AtomicBoolean publishingEnabled = new AtomicBoolean();

	/** 기본 인증 체인을 지연 생성하며 EC2에서는 인스턴스 역할의 임시 자격 증명을 사용한다. */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean(AwsCredentialsProvider.class)
	DefaultCredentialsProvider metricsCredentialsProvider() {
		return DefaultCredentialsProvider.builder().build();
	}

	/** 리전을 명시해 기본 리전 체인의 IMDS 조회를 피하고 네트워크 전송에 유한 제한을 둔다. */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean(CloudWatchAsyncClient.class)
	CloudWatchAsyncClient cloudWatchAsyncClient(@Value("${getddo.metrics.region:}") String region,
		AwsCredentialsProvider credentialsProvider) {
		if (region.isBlank()) {
			throw new IllegalArgumentException("지표 전송 활성화 시 AWS_REGION이 필요합니다.");
		}
		return CloudWatchAsyncClient.builder()
			.region(Region.of(region))
			.credentialsProvider(credentialsProvider)
			.httpClientBuilder(NettyNioAsyncHttpClient.builder()
				.connectionTimeout(Duration.ofMillis(500))
				.connectionAcquisitionTimeout(Duration.ofMillis(500))
				.readTimeout(Duration.ofSeconds(2))
				.writeTimeout(Duration.ofSeconds(2)))
			.overrideConfiguration(builder -> builder
				.apiCallAttemptTimeout(Duration.ofSeconds(2))
				.apiCallTimeout(Duration.ofSeconds(3))
				.retryStrategy(retry -> retry.maxAttempts(1)))
			.build();
	}

	/** Boot가 자동 구성하지 않는 CloudWatch registry를 등록하고 전송 대상과 차원을 제한한다. */
	@Bean
	CloudWatchMeterRegistry cloudWatchMeterRegistry(CloudWatchAsyncClient client,
		@Value("${getddo.metrics.server:}") String server,
		@Value("${getddo.metrics.environment:dev}") String environment,
		@Value("${getddo.metrics.namespace:GETDDO/API}") String namespace,
		@Value("${getddo.metrics.step:60s}") String step) {
		if (!Set.of("a", "c").contains(server)) {
			throw new IllegalArgumentException("지표 전송 활성화 시 GETDDO_METRICS_SERVER는 a 또는 c여야 합니다.");
		}
		if (!"dev".equals(environment) || !"GETDDO/API".equals(namespace) || !"60s".equals(step)) {
			throw new IllegalArgumentException("지표 전송 계약은 environment=dev, namespace=GETDDO/API, step=60s입니다.");
		}
		CloudWatchConfig config = key -> "cloudwatch.enabled".equals(key)
			? Boolean.toString(publishingEnabled.get()) : Map.of(
			"cloudwatch.namespace", "GETDDO/API",
			"cloudwatch.step", "60s",
			"cloudwatch.readTimeout", "3s"
		).get(key);
		CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
		registry.config().meterFilter(new MeterFilter() {
			@Override
			public MeterFilterReply accept(Meter.Id id) {
				if (!DIMENSIONS.containsKey(id.getName())
					|| (id.getName().startsWith("jvm.memory.") && !"heap".equals(id.getTag("area")))
					|| (id.getName().startsWith("executor.") && !"taskScheduler".equals(id.getTag("name")))) {
					return MeterFilterReply.DENY;
				}
				return MeterFilterReply.NEUTRAL;
			}

			@Override
			public Meter.Id map(Meter.Id id) {
				Set<String> keys = DIMENSIONS.getOrDefault(id.getName(), Set.of());
				return id.replaceTags(Tags.of(id.getTags().stream()
					.filter(tag -> keys.contains(tag.getKey())).toList())
					.and("environment", "dev", "server", server));
			}
		});
		return registry;
	}

	/** 시작 실패 시 종료 전송도 하지 않도록 앱 준비 완료 뒤에만 publisher를 시작한다. */
	@Bean
	ApplicationListener<ApplicationReadyEvent> cloudWatchPublisher(CloudWatchMeterRegistry registry) {
		return event -> {
			publishingEnabled.set(true);
			registry.start(new NamedThreadFactory("cloudwatch-metrics-publisher"));
		};
	}
}
