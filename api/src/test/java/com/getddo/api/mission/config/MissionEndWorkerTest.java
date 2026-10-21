package com.getddo.api.mission.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
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
		assertThatThrownBy(() -> new MissionEndWorker(service, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MissionEndWorker(service, -1)).isInstanceOf(IllegalArgumentException.class);
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
