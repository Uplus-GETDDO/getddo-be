package com.getddo.api.event.dto.response;

/** imageKey는 등록·수정 요청에, imageUrl은 화면 미리보기에 사용한다. */
public record EventImageUploadResponse(String imageKey, String imageUrl) {
}
