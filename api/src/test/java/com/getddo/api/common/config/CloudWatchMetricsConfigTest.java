package com.getddo.api.common.config;

import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.binder.tomcat.TomcatMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CloudWatchMetricsConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(CloudWatchMetricsConfig.class);

	@Test
	@DisplayName("기본 로컬·테스트 설정은 AWS 클라이언트와 publisher를 생성하지 않는다")
	void disabledByDefault() {
		// when / then
		runner.run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(CloudWatchAsyncClient.class)
				.doesNotHaveBean(CloudWatchMeterRegistry.class).doesNotHaveBean(AwsCredentialsProvider.class);
		});
	}

	@Test
	@DisplayName("활성화해도 시작 중 자격 증명을 조회하거나 지표를 전송하지 않는다")
	void enabledWithoutStartupCredentialsLookup() {
		// given
		AwsCredentialsProvider credentials = mock(AwsCredentialsProvider.class);
		// when / then
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> runner
			.withPropertyValues("GETDDO_METRICS_ENABLED=true", "GETDDO_METRICS_SERVER=c", "AWS_REGION=ap-northeast-2")
			.withBean(AwsCredentialsProvider.class, () -> credentials)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(CloudWatchMeterRegistry.class);
				context.getBean(CloudWatchMeterRegistry.class).stop();
				CloudWatchAsyncClient client = context.getBean(CloudWatchAsyncClient.class);
				assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("ap-northeast-2");
				assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
					.contains(Duration.ofSeconds(3));
				assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout())
					.contains(Duration.ofSeconds(2));
				verifyNoInteractions(credentials);
			}));
		verifyNoInteractions(credentials);
	}

	@Test
	@DisplayName("앱 준비 완료 전에는 publisher를 시작하지 않고 완료 이벤트 후에만 시작한다")
	void startsPublishingOnlyAfterApplicationReady() {
		// given
		CloudWatchAsyncClient client = client();
		enabled(client).run(context -> {
			CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
			CloudWatchConfig config = (CloudWatchConfig) ReflectionTestUtils.getField(registry, "config");
			Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "getddo").register(registry);
			assertThat(config.enabled()).isFalse();
			assertThat(ReflectionTestUtils.getField(registry, "scheduledExecutorService")).isNull();
			verify(client, never()).putMetricData(any(PutMetricDataRequest.class));
			// when
			context.publishEvent(new ApplicationReadyEvent(new SpringApplication(CloudWatchMetricsConfig.class),
				new String[0], context.getSourceApplicationContext(), Duration.ZERO));
			// then
			assertThat(config.enabled()).isTrue();
			assertThat(ReflectionTestUtils.getField(registry, "scheduledExecutorService")).isNotNull();
			registry.stop();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "c"})
	@DisplayName("Stage 배포 환경변수로 시작하고 선택한 환경과 서버 차원을 사용한다")
	void startsWithStagingDeploymentProperties(String server) {
		// given
		CloudWatchAsyncClient client = client();
		// when / then
		runner.withPropertyValues("GETDDO_METRICS_ENABLED=true", "GETDDO_METRICS_SERVER=" + server,
			"GETDDO_METRICS_ENVIRONMENT=staging", "GETDDO_METRICS_NAMESPACE=GETDDO/API",
			"GETDDO_METRICS_STEP=60s", "AWS_REGION=ap-northeast-2")
			.withBean(CloudWatchAsyncClient.class, () -> client).run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(CloudWatchMeterRegistry.class);
				CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
				Gauge gauge = Gauge.builder("hikaricp.connections.active", () -> 2)
					.tags("pool", "getddo", "environment", "dev").register(registry);
				assertThat(gauge.getId().getTag("environment")).isEqualTo("staging");
				assertThat(gauge.getId().getTag("server")).isEqualTo(server);
				verifyNoInteractions(client);
			});
	}

	@Test
	@DisplayName("publisher 시작 실패를 격리하고 예약 자원을 정리하며 종료 전송도 하지 않는다")
	void publisherStartupFailureIsIsolated() {
		// given
		CloudWatchMetricsConfig configuration = new CloudWatchMetricsConfig();
		CloudWatchAsyncClient client = client();
		CloudWatchMeterRegistry registry = spy(configuration.cloudWatchMeterRegistry(
			client, "a", "dev", "GETDDO/API", "60s"));
		try {
			Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "getddo").register(registry);
			CloudWatchConfig config = (CloudWatchConfig) ReflectionTestUtils.getField(registry, "config");
			doAnswer(invocation -> {
				registry.start(thread -> {
					throw new RejectedExecutionException("publisher thread unavailable");
				});
				return null;
			}).when(registry).start(any(NamedThreadFactory.class));
			// when / then
			assertThatCode(() -> configuration.cloudWatchPublisher(registry).onApplicationEvent(null))
				.doesNotThrowAnyException();
			assertThat(config.enabled()).isFalse();
			assertThat(ReflectionTestUtils.getField(registry, "scheduledExecutorService")).isNull();
			assertThat(ReflectionTestUtils.getField(registry, "meterPollingService")).isNull();
		} finally {
			registry.close();
		}
		verify(client, never()).putMetricData(any(PutMetricDataRequest.class));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("직접 지정한 지표 활성화 설정과 Tomcat MBean 활성화 설정이 일치한다")
	void tomcatMbeanRegistryFollowsMetricsProperty(boolean enabled) {
		// when / then
		runner.withPropertyValues("getddo.metrics.enabled=" + enabled, "getddo.metrics.server=a")
			.withBean(CloudWatchAsyncClient.class, this::client).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getEnvironment().getProperty("server.tomcat.mbeanregistry.enabled", Boolean.class))
					.isEqualTo(enabled);
			});
	}

	@Test
	@DisplayName("실제 registry의 CloudWatch 응답 대기 상한은 3초이다")
	void cloudWatchReadTimeoutIsThreeSeconds() {
		// when / then
		enabled(client()).run(context -> {
			CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
			CloudWatchConfig config = (CloudWatchConfig) ReflectionTestUtils.getField(registry, "config");
			assertThat(config.readTimeout()).isEqualTo(Duration.ofSeconds(3));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "b", "a,c", "request-123"})
	@DisplayName("서버 차원은 a 또는 c만 허용한다")
	void rejectsInvalidServer(String server) {
		// given
		CloudWatchAsyncClient client = client();
		// when / then
		enabled(client).withPropertyValues("getddo.metrics.server=" + server).run(context -> {
			assertThat(context).hasFailed();
			verify(client, never()).putMetricData(any(PutMetricDataRequest.class));
		});
	}

	@Test
	@DisplayName("활성화 시 리전이 없으면 기본 리전 체인에 접근하지 않고 설정 오류로 종료한다")
	void requiresExplicitRegion() {
		// when / then
		runner.withPropertyValues("getddo.metrics.enabled=true", "getddo.metrics.server=a", "getddo.metrics.region=")
			.run(context -> assertThat(context).hasFailed()
				.getFailure().hasRootCauseMessage("지표 전송 활성화 시 AWS_REGION이 필요합니다."));
	}

	@ParameterizedTest
	@ValueSource(strings = {"getddo.metrics.namespace=other", "getddo.metrics.step=1s"})
	@DisplayName("dev와 staging 모두 합의된 namespace·60초 간격을 벗어나는 설정을 거부한다")
	void rejectsContractDrift(String property) {
		// when / then
		for (String environment : List.of("dev", "staging")) {
			enabled(client()).withPropertyValues("GETDDO_METRICS_ENVIRONMENT=" + environment, property)
				.run(context -> assertThat(context).hasFailed());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "prod", "STAGING", "dev,staging"})
	@DisplayName("dev와 staging 이외의 지표 환경은 시작 시 거부한다")
	void rejectsUnsupportedEnvironment(String environment) {
		// given
		CloudWatchAsyncClient client = client();
		// when / then
		enabled(client).withPropertyValues("GETDDO_METRICS_ENVIRONMENT=" + environment).run(context -> {
			assertThat(context).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class);
			verify(client, never()).putMetricData(any(PutMetricDataRequest.class));
		});
	}

	@Test
	@DisplayName("풀·Tomcat·heap·GC·사용 중인 공용 스케줄러만 허용하고 동적 차원을 제거한다")
	void filtersAndTags() {
		// given
		enabled(client()).run(context -> {
			CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
			registry.stop();
			// when
			Gauge.builder("hikaricp.connections.active", () -> 2).tags("pool", "getddo", "userId", "123")
				.register(registry);
			Gauge.builder("tomcat.threads.busy", () -> 1).tag("name", "http-nio-8080").register(registry);
			new JvmMemoryMetrics().bindTo(registry);
			Gauge.builder("executor.active", () -> 1).tag("name", "taskScheduler").register(registry);
			Gauge.builder("executor.active", () -> 1).tag("name", "applicationTaskExecutor").register(registry);
			registry.counter("http.server.requests", "uri", "/users/123").increment();
			registry.counter("custom.metric", "userId", "123").increment();
			// then
			assertThat(registry.getMeters()).allSatisfy(meter -> {
				assertThat(meter.getId().getTag("environment")).isEqualTo("dev");
				assertThat(meter.getId().getTag("server")).isEqualTo("a");
				assertThat(meter.getId().getTag("userId")).isNull();
			});
			assertThat(registry.find("hikaricp.connections.active").gauge()).isNotNull();
			assertThat(registry.find("tomcat.threads.busy").gauge()).isNotNull();
			assertThat(registry.find("jvm.memory.used").tag("area", "heap").gauges()).isNotEmpty();
			assertThat(registry.find("jvm.memory.used").tag("area", "nonheap").gauges()).isEmpty();
			assertThat(registry.find("executor.active").gauges()).hasSize(1);
			assertThat(registry.find("http.server.requests").meters()).isEmpty();
			assertThat(registry.find("custom.metric").meters()).isEmpty();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"dev", "staging"})
	@DisplayName("CloudWatch 요청의 이름·선택한 환경의 전체 차원·단위·60초 전송 계약을 검증한다")
	void exportedWireContract(String environment) {
		// given
		CloudWatchAsyncClient client = client();
		enabled(client).withPropertyValues("GETDDO_METRICS_ENVIRONMENT=" + environment).run(context -> {
			CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
			registry.stop();
			MockClock clock = new MockClock();
			// Step meter 생성 전 시계를 고정해 60초를 실제로 기다리지 않는다.
			ReflectionTestUtils.setField(registry, "clock", clock);
			Gauge.builder("hikaricp.connections.active", () -> 2)
				.tags("pool", "getddo", "environment", "dev").register(registry);
			Timer.builder("hikaricp.connections.acquire").tag("pool", "getddo").register(registry)
				.record(20, TimeUnit.MILLISECONDS);
			registry.counter("hikaricp.connections.timeout", "pool", "getddo").increment();
			Timer.builder("jvm.gc.pause").tags("action", "end of minor GC", "cause", "Allocation Failure",
				"gc", "G1 Young Generation").register(registry).record(7, TimeUnit.MILLISECONDS);
			clock.add(Duration.ofSeconds(60));
			// when
			ReflectionTestUtils.invokeMethod(registry, "publish");
			// then
			ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
			verify(client).putMetricData(captor.capture());
			PutMetricDataRequest request = captor.getValue();
			assertThat(request.namespace()).isEqualTo("GETDDO/API");
			assertThat(((CloudWatchConfig) ReflectionTestUtils.getField(registry, "config")).step())
				.isEqualTo(Duration.ofSeconds(60));
			assertThat(request.metricData()).extracting(MetricDatum::metricName).containsExactlyInAnyOrder(
				"hikaricp.connections.active.value", "hikaricp.connections.timeout.count",
				"hikaricp.connections.acquire.sum", "hikaricp.connections.acquire.count",
				"hikaricp.connections.acquire.avg", "hikaricp.connections.acquire.max",
				"jvm.gc.pause.sum", "jvm.gc.pause.count", "jvm.gc.pause.avg", "jvm.gc.pause.max");
			assertThat(request.metricData()).allSatisfy(datum -> {
				assertThat(datum.storageResolution()).isEqualTo(60);
				if (datum.metricName().startsWith("jvm.gc.pause")) {
					assertThat(datum.dimensions()).extracting(dimension -> dimension.name() + "=" + dimension.value())
						.containsExactlyInAnyOrder("environment=" + environment, "server=a", "action=end of minor GC",
							"cause=Allocation Failure", "gc=G1 Young Generation");
				} else {
					assertThat(datum.dimensions()).extracting(dimension -> dimension.name() + "=" + dimension.value())
						.containsExactlyInAnyOrder("environment=" + environment, "server=a", "pool=getddo");
				}
			});
			assertThat(request.metricData()).filteredOn(datum -> datum.metricName().endsWith("acquire.sum"))
				.singleElement().satisfies(datum -> {
					assertThat(datum.unit()).isEqualTo(StandardUnit.MILLISECONDS);
					assertThat(datum.value()).isEqualTo(20);
				});
		});
	}

	@Test
	@DisplayName("실제 Tomcat·JVM·executor binder의 대시보드용 전송 이름과 차원을 검증한다")
	void dashboardBinderWireContract() throws Exception {
		// given
		CloudWatchAsyncClient client = client();
		MBeanServer mbeans = mock(MBeanServer.class);
		ObjectName pool = new ObjectName("Tomcat:type=ThreadPool,name=\"http-nio-8080\"");
		when(mbeans.queryNames(any(ObjectName.class), isNull())).thenAnswer(invocation -> {
			ObjectName pattern = invocation.getArgument(0);
			return pattern.apply(pool) ? Set.of(pool) : Set.of();
		});
		when(mbeans.getAttribute(any(ObjectName.class), any(String.class))).thenReturn(10);
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		try (TomcatMetrics tomcat = new TomcatMetrics(null, Tags.empty(), mbeans)) {
			tomcat.setJmxDomain("Tomcat");
			enabled(client).run(context -> {
				CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
				registry.stop();
				tomcat.bindTo(registry);
				new JvmMemoryMetrics().bindTo(registry);
				new ExecutorServiceMetrics(executor, "taskScheduler", Tags.empty()).bindTo(registry);
				// when
				ReflectionTestUtils.invokeMethod(registry, "publish");
				// then
				ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
				verify(client).putMetricData(captor.capture());
				List<MetricDatum> metrics = captor.getValue().metricData();
				assertThat(metrics).extracting(MetricDatum::metricName).contains(
					"tomcat.threads.busy.value", "tomcat.threads.current.value", "tomcat.threads.config.max.value",
					"jvm.memory.used.value", "jvm.memory.max.value", "executor.active.value",
					"executor.queued.value", "executor.completed.count");
				assertThat(metrics).filteredOn(metric -> metric.metricName().startsWith("tomcat."))
					.hasSize(3).allSatisfy(metric -> assertThat(metric.dimensions())
						.extracting(dimension -> dimension.name() + "=" + dimension.value())
						.containsExactlyInAnyOrder("environment=dev", "server=a", "name=http-nio-8080"));
				assertThat(metrics).filteredOn(metric -> metric.metricName().startsWith("jvm.memory."))
					.isNotEmpty().allSatisfy(metric -> {
						assertThat(metric.dimensions()).extracting(dimension -> dimension.name())
							.containsExactlyInAnyOrder("environment", "server", "area", "id");
						assertThat(metric.dimensions()).anySatisfy(dimension -> {
							assertThat(dimension.name()).isEqualTo("area");
							assertThat(dimension.value()).isEqualTo("heap");
						});
						assertThat(metric.unit()).isEqualTo(StandardUnit.BYTES);
					});
				assertThat(metrics).filteredOn(metric -> metric.metricName().startsWith("executor."))
					.hasSize(3).allSatisfy(metric -> assertThat(metric.dimensions())
						.extracting(dimension -> dimension.name() + "=" + dimension.value())
						.containsExactlyInAnyOrder("environment=dev", "server=a", "name=taskScheduler"));
			});
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("AWS 전송 실패가 호출자에게 전파되지 않고 다음 수집도 가능하다")
	void failedPublisherDoesNotBreakCollection() {
		// given
		CloudWatchAsyncClient client = client();
		when(client.putMetricData(any(PutMetricDataRequest.class)))
			.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("AWS unavailable")));
		enabled(client).run(context -> {
			CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
			registry.stop();
			Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "getddo").register(registry);
			// when / then
			assertTimeoutPreemptively(Duration.ofSeconds(1), () -> ReflectionTestUtils.invokeMethod(registry, "publish"));
			assertThat(registry.get("hikaricp.connections.active").gauge().value()).isEqualTo(2);
		});
	}

	@Test
	@DisplayName("응답 없는 AWS future 대기는 제한되며 요청 스레드의 수집을 막지 않는다")
	void stalledPublisherIsBounded() {
		// given
		CloudWatchAsyncClient client = client();
		CompletableFuture<PutMetricDataResponse> response = new CompletableFuture<>();
		when(client.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);
		enabled(client).run(context -> {
			CloudWatchMeterRegistry registry = context.getBean(CloudWatchMeterRegistry.class);
			registry.stop();
			Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "getddo").register(registry);
			// when
			CompletableFuture<Void> publishing = CompletableFuture.runAsync(
				() -> ReflectionTestUtils.invokeMethod(registry, "publish"));
			// then
			assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
				assertThat(registry.get("hikaricp.connections.active").gauge().value()).isEqualTo(2));
			assertTimeoutPreemptively(Duration.ofSeconds(5), publishing::join);
			response.complete(PutMetricDataResponse.builder().build());
		});
	}

	private ApplicationContextRunner enabled(CloudWatchAsyncClient client) {
		return runner.withPropertyValues("getddo.metrics.enabled=true", "getddo.metrics.server=a")
			.withBean(CloudWatchAsyncClient.class, () -> client);
	}

	private CloudWatchAsyncClient client() {
		CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
		when(client.putMetricData(any(PutMetricDataRequest.class)))
			.thenReturn(CompletableFuture.completedFuture(PutMetricDataResponse.builder().build()));
		return client;
	}
}
