package com.getddo.core.abuse.domain;

import java.util.Objects;

import lombok.Getter;

/** GD-142의 목록 모델과 허용된 최초 판정 근거를 합친 관리자 상세 조회 결과다. */
@Getter
public final class AbuseCaseDetail {

	private final AbuseCaseSummary summary;
	private final AbuseEvidence evidence;

	/** 상세 근거가 없거나 손상된 행을 정상 상세로 대체하지 않는다. */
	public AbuseCaseDetail(AbuseCaseSummary summary, AbuseEvidence evidence) {
		this.summary = Objects.requireNonNull(summary, "summary");
		this.evidence = Objects.requireNonNull(evidence, "evidence");
	}
}
