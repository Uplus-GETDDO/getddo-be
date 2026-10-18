package com.getddo.core.banner.service;

/** 공개 이미지 설정 오류로 응답 생성이 실패할 변경 요청은 저장 전에 거절한다. */
public interface BannerImageConfiguration {
	void requireConfigured();
}
