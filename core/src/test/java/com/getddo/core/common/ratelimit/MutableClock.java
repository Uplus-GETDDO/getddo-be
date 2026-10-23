package com.getddo.core.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 시각을 옮길 수 있는 시계. */
final class MutableClock extends Clock {

	private volatile Instant now;

	MutableClock(Instant initial) {
		this.now = initial;
	}

	void set(Instant instant) {
		this.now = instant;
	}

	void advance(Duration duration) {
		this.now = now.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}
}
