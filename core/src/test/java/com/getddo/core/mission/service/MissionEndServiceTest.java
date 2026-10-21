package com.getddo.core.mission.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.getddo.core.common.time.TimeProvider;
import com.getddo.core.mission.repository.MissionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MissionEndServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-21T01:40:00.123456789Z");

	@Mock
	private MissionRepository missionRepository;

	private MissionEndService service() {
		return new MissionEndService(missionRepository, new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
	}

	@Test
	@DisplayName("지금 시각을 마이크로초로 잘라 종료 대상 갱신을 요청하고 종료한 수를 돌려준다")
	void endsDueMissionsAtTruncatedNow() {
		// given
		when(missionRepository.endExpired(Instant.parse("2026-10-21T01:40:00.123456Z"))).thenReturn(3);

		// when
		int ended = service().endDue();

		// then
		assertThat(ended).isEqualTo(3);
		verify(missionRepository).endExpired(Instant.parse("2026-10-21T01:40:00.123456Z"));
	}

	@Test
	@DisplayName("종료할 미션이 없으면 0을 돌려준다")
	void returnsZeroWhenNothingDue() {
		// given
		when(missionRepository.endExpired(Instant.parse("2026-10-21T01:40:00.123456Z"))).thenReturn(0);

		// when
		int ended = service().endDue();

		// then
		assertThat(ended).isZero();
	}
}
