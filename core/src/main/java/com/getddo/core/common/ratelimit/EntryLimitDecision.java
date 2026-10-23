package com.getddo.core.common.ratelimit;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/** 공개 응답과 분리된 응모 원자 평가 결과다. 기존 ACTIVE이면 구간 정보 없이 차단만 반환한다. */
@Getter
public final class EntryLimitDecision {
	private final EntryLimitResult result;
	private final EntryRecordingStatus recordingStatus;
	private final long retryAfterSeconds;
	private final EntryWindowSnapshot userWindow;
	private final EntryWindowSnapshot eventWindow;
	private final EntryBlock block;

	public EntryLimitDecision(EntryLimitResult result, EntryRecordingStatus recordingStatus,
			long retryAfterSeconds, EntryWindowSnapshot userWindow, EntryWindowSnapshot eventWindow, EntryBlock block) {
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(recordingStatus, "recordingStatus");
		if (result == EntryLimitResult.ALLOWED ? retryAfterSeconds != 0 : retryAfterSeconds < 1) {
			throw new IllegalArgumentException("허용은 대기 시간 0, 거절·차단은 1초 이상이어야 한다.");
		}
		this.result = result;
		this.recordingStatus = recordingStatus;
		this.retryAfterSeconds = retryAfterSeconds;
		this.userWindow = userWindow;
		this.eventWindow = eventWindow;
		this.block = block;
	}

	/** 최초 종료 시각을 기준으로 올림하며 차단 중 최소 1초를 보장한다. */
	public static EntryLimitDecision blocked(EntryBlock block, Instant now) {
		long millis = block.getBlockedUntil().toEpochMilli() - now.toEpochMilli();
		return new EntryLimitDecision(EntryLimitResult.BLOCKED, EntryRecordingStatus.NOT_APPLICABLE,
				Math.max(1, (millis + 999) / 1000), null, null, block);
	}
}
