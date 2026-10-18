-- 기존 URL과 배너 행을 유지한다. 기존 URL에서 이벤트 ID를 추측하거나 일괄 삭제하지 않는다.
ALTER TABLE banners
  ADD COLUMN event_id binary(16) NULL COMMENT '연결 이벤트 ID. 이전 URL 행은 관리자 연결 전 NULL',
  ADD KEY idx_banners_event_id (event_id),
  ADD CONSTRAINT fk_banners_event FOREIGN KEY (event_id) REFERENCES events (id);

-- 빈 목록에서도 최대 5개·전체 순서 변경을 직렬화하는 단일 잠금 행.
-- 사용자/업무 데이터가 아닌 고정 제어 행이므로 UUID·Auditing Entity로 만들지 않는다.
CREATE TABLE banner_catalog_lock (
  id tinyint NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT chk_banner_catalog_lock CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO banner_catalog_lock (id) VALUES (1);
