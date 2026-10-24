package com.getddo.api.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.getddo.core.notification.service.EventStartNotificationService;
import com.getddo.core.notification.service.NotificationJobService;

/** DB의 미완료 작업을 주기적으로 처리한다. 프로세스 내 작업 큐에만 의존하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Slf4j
@EnableScheduling
@ConditionalOnProperty(name = "getddo.notification.worker.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationWorker {
	private final NotificationJobService service;
	private final EventStartNotificationService starts;

	/** 정기 폴링에서 호출할 알림 생성·발송 서비스를 연결한다. */
	public NotificationWorker(NotificationJobService service, EventStartNotificationService starts) {
		this.service = service;
		this.starts = starts;
	}

	/** 시작 작업 등록·생성은 최대 20개, 모의 발송은 최대 100건 처리한다. 등록 실패는 기존 작업을 막지 않는다. */
	@Scheduled(fixedDelayString = "${getddo.notification.worker.poll-delay:1000}")
	public void poll() {
		try {
			for (int count = 0; count < 20; count++) {
				if (!starts.registerNextDueEvent()) {
					break;
				}
			}
		} catch (RuntimeException failure) {
			log.warn("Event start notification registration failed: exceptionType={}", failure.getClass().getName());
		}
		for (int count = 0; count < 20; count++) {
			if (!service.processNextJob()) {
				break;
			}
		}
		service.processNextDeliveries();
	}
}
