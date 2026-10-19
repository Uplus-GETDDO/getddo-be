package com.getddo.core.event.repository;

/** 이벤트 이미지의 상대 키와 검증된 내용을 외부 객체 저장소에 저장한다. */
public interface EventImageStorage {
	void upload(String imageKey, String contentType, byte[] contents);
}
