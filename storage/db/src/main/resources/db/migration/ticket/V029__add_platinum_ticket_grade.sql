-- 플래티넘 응모권 등급(ADR-025)을 추가한다.
-- 기존 행은 그대로 두고 ENUM 값과 응모 요청 장수 컬럼만 늘린다. 기존 응모의 플래티넘 요청 장수는 0이다.

ALTER TABLE `tickets`
  MODIFY COLUMN `grade` ENUM ('BRONZE', 'SILVER', 'GOLD', 'PLATINUM') NOT NULL COMMENT '최초 지급 등급. 출석 BRONZE 고정, 게임·미션은 최초 확정한 무작위 등급. 이후 불변. 기존 무등급 응모권은 없다고 가정하며 있다면 일괄 BRONZE로 전환';

ALTER TABLE `reward_policies`
  MODIFY COLUMN `reward_ticket_type` ENUM ('PLATINUM', 'GOLD', 'SILVER', 'BRONZE') COMMENT '보상 등급 설정의 역할은 보완 필요. 출석 BRONZE 고정, 게임·미션은 무작위 지급이므로 고정 결과로 해석하지 않음';

ALTER TABLE `event_entries`
  ADD COLUMN `requested_platinum_count` int NOT NULL DEFAULT 0 COMMENT '요청한 플래티넘 응모권 장수' AFTER `requested_gold_count`,
  DROP CHECK `chk_entry_requested_grades`,
  ADD CONSTRAINT `chk_entry_requested_grades` CHECK (requested_bronze_count >= 0 AND requested_silver_count >= 0
    AND requested_gold_count >= 0 AND requested_platinum_count >= 0);
