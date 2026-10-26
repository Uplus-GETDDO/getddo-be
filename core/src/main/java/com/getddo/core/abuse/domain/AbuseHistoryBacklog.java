package com.getddo.core.abuse.domain;

import lombok.Getter;

/** 정기 복구 후 관측한 파생 대기 건수와 저널 수다. 각 집계는 조회 시점의 값이다. */
@Getter
public final class AbuseHistoryBacklog {
	private final long pending;
	private final long batch;
	private final long journals;

	public AbuseHistoryBacklog(long pending, long batch, long journals) {
		this.pending = pending;
		this.batch = batch;
		this.journals = journals;
	}
}
