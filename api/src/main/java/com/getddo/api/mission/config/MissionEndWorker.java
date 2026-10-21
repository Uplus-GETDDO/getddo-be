package com.getddo.api.mission.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.getddo.core.mission.service.MissionEndService;

/**
 * 운영 기간이 끝난 미션을 주기적으로 종료 상태로 바꾼다.
 *
 * <p>한 번의 갱신이 조건 하나로 끝나고 같은 조건으로 다시 실행해도 결과가 같아서, 서버 여러 대가 동시에 돌아도 충돌하지
 * 않는다. 이 워커가 늦어도 제출은 서버가 받은 시각과 운영 기간으로 판단하므로 지난 미션이 제출을 받는 일은 없고, 관리자
 * 화면의 저장 상태 표시만 늦는다.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "getddo.mission.end-worker.enabled", havingValue = "true", matchIfMissing = true)
public class MissionEndWorker {

	private final MissionEndService service;

	/** 종료 시각이 지난 미션을 종료 처리한다. 실패해도 다음 폴링을 중단하지 않는다. */
	@Scheduled(fixedDelayString = "${getddo.mission.end-worker.poll-delay:60000}")
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
