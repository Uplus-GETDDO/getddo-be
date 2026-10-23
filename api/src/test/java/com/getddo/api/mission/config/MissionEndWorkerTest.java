package com.getddo.api.mission.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.StandardEnvironment;

import com.getddo.core.mission.service.MissionEndService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MissionEndWorkerTest {

	private static final long POLL_DELAY_MILLIS = 60_000;

	@Mock
	private MissionEndService service;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(MissionEndWorker.class)
			.withBean(MissionEndService.class, () -> mock(MissionEndService.class));

	@Test
	@DisplayName("워커는 기본으로 등록되고 enabled=false이면 등록되지 않는다")
	void registersWorkerOnlyWhenEnabled() {
		contextRunner.run(context -> assertThat(context).hasSingleBean(MissionEndWorker.class));
		contextRunner.withPropertyValues("getddo.mission.end-worker.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(MissionEndWorker.class));
		contextRunner.withPropertyValues("getddo.mission.end-worker.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(MissionEndWorker.class));
	}

	@Test
	@DisplayName("폴링 간격이 1ms 미만이면 워커를 만들 수 없다")
	void rejectsNonPositivePollDelay() {
		// given
		// when
		// then
		assertThatThrownBy(() -> new MissionEndWorker(service, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MissionEndWorker(service, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> new MissionEndWorker(service, 1)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("컨텍스트가 모두 만들어진 뒤 전용 스레드가 폴링을 시작하고 컨텍스트가 닫히면 멈춘다")
	void startsAfterContextRefresh() throws InterruptedException {
		// given
		CountDownLatch polled = new CountDownLatch(1);
		AtomicReference<String> threadName = new AtomicReference<>();
		MissionEndService recording = mock(MissionEndService.class);
		when(recording.endDue()).thenAnswer(invocation -> {
			threadName.set(Thread.currentThread().getName());
			polled.countDown();
			return 0;
		});
		// when
		new ApplicationContextRunner()
				.withUserConfiguration(MissionEndWorker.class)
				.withBean(MissionEndService.class, () -> recording)
				.run(context -> {
					// then
					assertThat(polled.await(10, TimeUnit.SECONDS)).isTrue();
					assertThat(threadName.get()).startsWith(MissionEndWorker.THREAD_NAME_PREFIX);
				});
	}

	/** 싱글톤 초기화가 끝난 시점(ContextRefreshedEvent 직전)에 워커가 이미 시작했는지 기록한다. */
	static final AtomicReference<Boolean> RUNNING_BEFORE_REFRESH = new AtomicReference<>();

	@Configuration(proxyBeanMethods = false)
	static class StartProbeConfig {

		@Bean
		SmartInitializingSingleton startProbe(MissionEndWorker worker) {
			return () -> RUNNING_BEFORE_REFRESH.set(worker.isRunning());
		}
	}

	@Test
	@DisplayName("빈 초기화가 끝나는 시점에는 폴링을 시작하지 않고 컨텍스트 refresh 이벤트 뒤에 시작하며, 컨텍스트가 닫히면 멈춘다")
	void startsOnlyAfterContextRefreshAndStopsOnClose() {
		// given
		RUNNING_BEFORE_REFRESH.set(null);
		AtomicReference<MissionEndWorker> workerRef = new AtomicReference<>();
		// when
		new ApplicationContextRunner()
				.withUserConfiguration(MissionEndWorker.class, StartProbeConfig.class)
				.withBean(MissionEndService.class, () -> mock(MissionEndService.class))
				.run(context -> {
					// then: refresh 이벤트 뒤에는 시작했다
					workerRef.set(context.getBean(MissionEndWorker.class));
					assertThat(workerRef.get().isRunning()).isTrue();
				});
		// then: 싱글톤 초기화가 끝난 시점에는 아직 시작하지 않았고, 컨텍스트가 닫힌 뒤에는 멈췄다
		assertThat(RUNNING_BEFORE_REFRESH.get()).isFalse();
		assertThat(workerRef.get().isRunning()).isFalse();
	}

	@Test
	@DisplayName("컨텍스트를 닫으면 @PreDestroy로 진행 중인 갱신이 끝나기를 기다린 뒤 종료하고 이후에는 폴링하지 않는다")
	void contextCloseWaitsForInFlightPoll() throws Exception {
		// given
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		AtomicBoolean interrupted = new AtomicBoolean();
		MissionEndService blocking = mock(MissionEndService.class);
		when(blocking.endDue()).thenAnswer(invocation -> {
			calls.incrementAndGet();
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted.set(true);
			}
			return 0;
		});
		ExecutorService closer = Executors.newSingleThreadExecutor();
		try {
			new ApplicationContextRunner()
					.withUserConfiguration(MissionEndWorker.class)
					.withPropertyValues("getddo.mission.end-worker.poll-delay=10")
					.withBean(MissionEndService.class, () -> blocking)
					.run(context -> {
						assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

						// when: 갱신이 진행 중인 동안 컨텍스트를 닫는다
						Future<?> closing = closer.submit(context::close);

						// then: 갱신이 끝나기 전에는 닫기가 끝나지 않는다
						assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS))
								.isInstanceOf(TimeoutException.class);
						release.countDown();
						closing.get(10, TimeUnit.SECONDS);
						int callsAtClose = calls.get();
						Thread.sleep(100);
						assertThat(interrupted).isFalse();
						assertThat(calls.get()).isEqualTo(callsAtClose);
					});
		} finally {
			release.countDown();
			closer.shutdownNow();
		}
	}

	@Test
	@DisplayName("종료는 진행 중인 갱신을 interrupt하지 않고 끝나기를 기다리며 종료한 뒤에는 더 폴링하지 않는다")
	void stopWaitsForInFlightPollAndStopsPolling() throws Exception {
		// given
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		AtomicBoolean interrupted = new AtomicBoolean();
		when(service.endDue()).thenAnswer(invocation -> {
			calls.incrementAndGet();
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				interrupted.set(true);
			}
			return 0;
		});
		MissionEndWorker worker = new MissionEndWorker(service, 10);
		ExecutorService stopper = Executors.newSingleThreadExecutor();
		try {
			worker.start();
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

			// when: 갱신이 진행 중인 동안 종료한다
			Future<?> stopping = stopper.submit(worker::stop);

			// then: 갱신이 끝나기 전에는 종료가 끝나지 않는다
			assertThatThrownBy(() -> stopping.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			release.countDown();
			stopping.get(10, TimeUnit.SECONDS);
			int callsAtStop = calls.get();
			Thread.sleep(100);
			assertThat(interrupted).isFalse();
			assertThat(calls.get()).isEqualTo(callsAtStop);
		} finally {
			release.countDown();
			worker.stop();
			stopper.shutdownNow();
		}
	}

	@Test
	@DisplayName("폴링 간격은 getddo.mission.end-worker.poll-delay 속성(기본 60000ms)을 따른다")
	void pollDelayFollowsProperty() throws NoSuchMethodException {
		// given
		Value value = MissionEndWorker.class.getConstructor(MissionEndService.class, long.class)
				.getParameters()[1].getAnnotation(Value.class);
		// when
		// then
		assertThat(value.value()).isEqualTo("${getddo.mission.end-worker.poll-delay:60000}");
		StandardEnvironment environment = new StandardEnvironment();
		TestPropertyValues.of("getddo.mission.end-worker.poll-delay=15000").applyTo(environment);
		assertThat(environment.resolvePlaceholders(value.value())).isEqualTo("15000");
		assertThat(new StandardEnvironment().resolvePlaceholders(value.value())).isEqualTo("60000");
	}

	@Test
	@DisplayName("시작하지 않고 종료해도 예외가 나지 않는다")
	void stopWithoutStartIsSafe() {
		// given
		MissionEndWorker worker = new MissionEndWorker(service, POLL_DELAY_MILLIS);
		// when
		// then
		assertThatCode(worker::stop).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("시작하면 공용 스케줄러가 아닌 전용 스레드가 첫 폴링을 바로 실행하고 종료는 여러 번 불러도 안전하다")
	void startRunsFirstPollOnDedicatedThread() throws InterruptedException {
		// given
		CountDownLatch polled = new CountDownLatch(1);
		AtomicReference<String> threadName = new AtomicReference<>();
		when(service.endDue()).thenAnswer(invocation -> {
			threadName.set(Thread.currentThread().getName());
			polled.countDown();
			return 0;
		});
		MissionEndWorker started = new MissionEndWorker(service, POLL_DELAY_MILLIS);
		try {
			// when
			started.start();
			// then
			assertThat(polled.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(threadName.get()).startsWith(MissionEndWorker.THREAD_NAME_PREFIX);
		} finally {
			started.stop();
			started.stop();
		}
	}

	@Test
	@DisplayName("폴링마다 종료 처리를 한 번 요청한다")
	void pollRequestsEndOnce() {
		// given
		MissionEndWorker worker = new MissionEndWorker(service, POLL_DELAY_MILLIS);
		when(service.endDue()).thenReturn(0);

		// when
		worker.poll();

		// then
		verify(service, times(1)).endDue();
	}

	@Test
	@DisplayName("종료 처리가 실패해도 예외를 밖으로 던지지 않고 원문 메시지 없이 클래스만 기록해 다음 폴링을 막지 않는다")
	void pollSwallowsFailureWithoutMessage(CapturedOutput output) {
		// given
		MissionEndWorker worker = new MissionEndWorker(service, POLL_DELAY_MILLIS);
		when(service.endDue()).thenThrow(new IllegalStateException("비밀 내부 메시지"));

		// when
		// then
		assertThatCode(worker::poll).doesNotThrowAnyException();
		assertThat(output.getOut()).contains("Mission end failed").contains("java.lang.IllegalStateException")
				.doesNotContain("비밀 내부 메시지");
	}
}
