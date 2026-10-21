package com.getddo.core.mission.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.mission.repository.MissionRepository;

/**
 * 운영 기간이 끝난 미션의 상태를 {@code ENDED}로 바꾸는 진입점.
 *
 * <p>제출 가능 여부는 서버가 제출을 받은 시각과 운영 기간으로 판단하므로 이 서비스가 늦어져도 지난 미션은 제출을 받지
 * 않는다. 이 서비스는 관리자 목록·상세와 상태 필터에 보이는 저장 상태를 기간과 맞춘다. 한 번의 갱신은 같은 조건으로 다시
 * 실행해도 결과가 같아, 서버 여러 대가 동시에 실행해도 충돌하지 않는다.</p>
 */
@Service
@RequiredArgsConstructor
public class MissionEndService {

	private final MissionRepository missionRepository;
	private final TimeProvider timeProvider;

	/**
	 * 종료 시각이 지났는데 아직 운영 중인 미션을 모두 종료 상태로 바꾼다. 종료 시각 정각부터 종료로 본다.
	 *
	 * @return 이번에 종료한 미션 수. 0이면 바꿀 미션이 없다
	 */
	@Transactional
	public int endDue() {
		Instant now = timeProvider.now().truncatedTo(ChronoUnit.MICROS);
		return missionRepository.endExpired(now);
	}
}
