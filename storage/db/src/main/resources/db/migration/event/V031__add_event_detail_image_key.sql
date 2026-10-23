ALTER TABLE events
    ADD COLUMN detail_image_key VARCHAR(500) NULL DEFAULT NULL
    COMMENT '이벤트 상세 설명 이미지의 S3 저장 키' AFTER image_key;
