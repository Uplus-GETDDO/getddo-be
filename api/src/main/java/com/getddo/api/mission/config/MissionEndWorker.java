package com.getddo.api.mission.config;

import java.time.Duration;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.getddo.core.mission.service.MissionEndService;

/**
 * 운영 기간이 끝난 미션을 주기적으로 종료 상태로 바꾼다.
 *
 * <p>한 번의 갱신이 조건 하나로 끝나고 같은 조건으로 다시 실행해도 결과가 같아서, 서버 여러 대가 동시에 돌아도 충돌하지
 * 않는다. 이 워커가 늦어도 제출은 서버가 받은 시각과 운영 기간으로 판단하므로 지난 미션이 제출을 받는 일은 없고, 관리자
 * 화면의 저장 상태 표시만 늦는다.</p>
 *
 * <p>{@code @Scheduled}는 스레드 하나짜리 공용 스케줄러를 알림·추첨 워커와 나눠 쓴다. 갱신이 잠금을 기다리며 오래 걸려도 그
 * 워커들이 밀리지 않도록 이 워커는 자기 전용 스레드에서 돈다. 공용 스케줄러를 대체하는 {@code TaskScheduler} 빈을 등록하면
 * 다른 워커의 스케줄러까지 바뀌므로, 빈으로 노출하지 않고 이 클래스가 직접 생성하고 종료한다.</p>
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
@ConditionalOnProperty(name = "getddo.mission.end-worker.enabled", havingValue = "true", matchIfMissing = true)
public class MissionEndWorker {

	static final String THREAD_NAME_PREFIX = "mission-end-";
	/** 종료할 때 진행 중인 갱신이 끝나기를 기다리는 최대 시간(초). 넘기면 기다리기를 멈춘다. */
	private static final int SHUTDOWN_WAIT_SECONDS = 30;

	private final MissionEndService service;
	private final Duration pollDelay;
	private ThreadPoolTaskScheduler scheduler;

	/** 종료 서비스와 폴링 간격(밀리초)을 연결한다. */
	public MissionEndWorker(MissionEndService service,
			@Value("${getddo.mission.end-worker.poll-delay:60000}") long pollDelayMillis) {
		if (pollDelayMillis < 1) {
			// 잘못된 값으로 계속 실패하며 종료 처리가 조용히 멈추는 대신 기동 때 바로 드러나게 한다.
			throw new IllegalArgumentException(
					"getddo.mission.end-worker.poll-delay는 1 이상이어야 한다: " + pollDelayMillis);
		}
		this.service = service;
		this.pollDelay = Duration.ofMillis(pollDelayMillis);
	}

	/**
	 * 컨텍스트가 모두 만들어진 뒤(다른 워커의 {@code @Scheduled} 등록 시점과 같다) 전용 스레드 하나로 이전 실행이 끝난 뒤 폴링
	 * 간격만큼 쉬고 다시 실행하도록 등록한다. 이미 시작했으면 다시 시작하지 않는다.
	 */
	@EventListener(ContextRefreshedEvent.class)
	synchronized void start() {
		if (scheduler != null) {
			return;
		}
		ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
		created.setPoolSize(1);
		created.setThreadNamePrefix(THREAD_NAME_PREFIX);
		created.setWaitForTasksToCompleteOnShutdown(true);
		created.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
		created.initialize();
		created.scheduleWithFixedDelay(this::poll, pollDelay);
		scheduler = created;
	}

	/** 폴링을 시작했고 아직 종료하지 않았으면 참이다. */
	synchronized boolean isRunning() {
		return scheduler != null;
	}

	/** 새 실행을 멈추고 진행 중인 갱신이 끝나기를 최대 {@value #SHUTDOWN_WAIT_SECONDS}초 기다린다. 여러 번 불러도 안전하다. */
	@PreDestroy
	synchronized void stop() {
		if (scheduler != null) {
			scheduler.shutdown();
			scheduler = null;
		}
	}

	/** 종료 시각이 지난 미션을 종료 처리한다. 실패해도 다음 폴링을 중단하지 않는다. */
	public void poll() {
		try {
			int ended = service.endDue();
			if (ended > 0) {
				log.info("Missions ended: count={}", ended);
			}
		} catch (RuntimeException failure) {
			// 예외 원문 메시지는 남기지 않고 원인을 좁힐 수 있게 클래스만 남긴다.
			log.warn("Mission end failed: exceptionType={}", failure.getClass().getName());
		}
	}
}
