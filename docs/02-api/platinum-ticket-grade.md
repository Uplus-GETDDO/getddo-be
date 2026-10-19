# GD-228 플래티넘 응모권 등급 (1단계: 등급 추가)

- 작업 브랜치: `feat/GD-228`
- 공용 결정: [ADR-025 플래티넘 응모권 추가](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/03-decisions/025-platinum-ticket-grade.md), 확률·가중치 원본은 [응모권 규칙](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/02-domain/ticket.md#게임미션-보상의-등급-추첨). 이 문서는 백엔드 구현 범위와 판단만 설명한다.

## 확정된 값 (스펙)

| 등급 | 획득 확률 | 장당 추첨 가중치 |
| --- | --- | --- |
| 브론즈 | 79.98% | 1 |
| 실버 | 18% | 3 |
| 골드 | 2% | 5 |
| 플래티넘 | 0.02% | 10 |

실제 지급 수량은 보상 건당 1장이다. 출석(일일·연속)은 브론즈 고정을 유지한다.

## 이번 단계에서 한 것

플래티넘을 **받아서 다룰 수 있게** 했다. 플래티넘의 **실제 지급은 켜지 않았다**(기본 확률 0%). 이유는 아래 "지급을 켜는 순서"를 본다.

- `TicketGrade.PLATINUM` 추가. 등급별 맵을 `TicketGrade.values()`로 채우는 곳은 자동으로 `PLATINUM` 키를 갖는다. 그래서 응모 응답의 `requestedTicketsByGrade`·`deductedTicketsByGrade`와 보유 조회의 `countByGrade`에 `PLATINUM`(0장이어도) 키가 새로 생긴다.
- 마이그레이션 [V029](../../storage/db/src/main/resources/db/migration/ticket/V029__add_platinum_ticket_grade.sql): `tickets.grade`·`reward_policies.reward_ticket_type` ENUM에 `PLATINUM`을 추가하고, `event_entries.requested_platinum_count`(기본 0)를 더하며 등급별 요청 장수 CHECK를 `>= 0`으로 다시 정의했다. 기존 행은 그대로다.
- 응모 저장·조회 경로(`EventEntryEntity`, `EntryMapper`, `EntryRepositoryImpl`)가 플래티넘 요청 장수를 읽고 쓴다. 관리자 응모 내역과 내 응모 이력도 같은 경로를 쓴다.
- 등급 추첨기 `RandomTicketGradeDrawer`를 1만 분율(basis point)로 바꿨다. 0.02%를 표현하려면 0~99 대신 0~9999를 뽑아야 한다.

## 등급 확률 설정

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `getddo.ticket.grade-probability.silver-bp` | 1800 | 실버 18% |
| `getddo.ticket.grade-probability.gold-bp` | 200 | 골드 2% |
| `getddo.ticket.grade-probability.platinum-bp` | **0** | 플래티넘. 확정값은 2(0.02%)이며 지급을 켤 때 설정한다 |

브론즈는 10000에서 세 값을 뺀 나머지다. 어느 값이든 음수이거나 세 값의 합이 10000을 넘으면 서버가 시작할 때 실패한다. 플래티넘이 0이면 기존과 같은 80/18/2 구간으로 동작한다.

## 지급을 켜는 순서 (왜 기본 0인가)

GD-228은 등급·차감·저장 경로를 추가하고, GD-230은 추첨의 플래티넘 가중치 10 지원을 구현한다. GD-230 코드의 `DrawSnapshotService`·`DrawStoredVerification`은 Rules 3/4·EntryEvidence 2에서 `브론즈 + 실버×3 + 골드×5 + 플래티넘×10`을 계산·검증한다. 기존 Rules 1/2 입력에 실제 PLATINUM USE 근거가 들어온 경우에만 증거 오류로 거절한다. 상세는 [추첨 저장 문서](../03-database/drawing-selection-storage.md#gd-230-플래티넘-입력과-저장-호환)를 따른다.

1. GD-228(등급 추가, 지급 꺼짐)은 PR #126으로 병합됐다.
2. 플래티넘 가중치·근거 구조와 기존 스냅샷 호환은 GD-230(PR #128)에서 구현했다. PR 병합·배포 및 검증 완료 여부는 별도로 확인한다. 통합 테스트는 사용자 요청으로 중단했으며 완료하지 않았다.
3. 이후 GD-229에서 `platinum-bp`를 2로 설정해 지급을 켠다. GD-230은 지급 확률을 변경하지 않는다.

지급 확률 0은 새 저장 형식 쓰기를 막지 않는다. GD-230부터 플래티넘이 없는 응모도 Rules 3/4로 저장하므로, 신규 형식을 읽지 못하는 구버전 서버와 혼용하지 않는다. 새 형식 생성 후 롤백이 필요하면 Rules 3/4·EntryEvidence 2를 읽을 수 있는 호환 버전을 사용한다. 구버전으로 되돌리기 위해 저장된 JSON을 덮어쓰지 않는다. 이 문구는 배포·롤백 검증 완료를 뜻하지 않는다.

## 영향과 알려야 할 것

- 프론트: 응모·응모 이력·보유 조회 응답의 등급별 맵에 `PLATINUM` 키가 추가된다. 등급 표시와 목업을 확인해야 한다.
- 게임 보상: 게임 담당(전민규)의 보상 응답에도 플래티넘 등급이 나갈 수 있다.
- 추첨: 위 순서의 2단계는 추첨 담당(석종수)의 GD-230이며, 실제 지급 활성화는 GD-229다.

## 검증

- 단위: `RandomTicketGradeDrawerTest`(플래티넘 켜진 구간 경계 7997/7998·9797/9798·9997/9998, 꺼진 구간이 기존 80/18/2와 같음, 출석 브론즈 고정, 난수 범위, 설정 합·음수 검증), 응모·관리자 응모권 컨트롤러 테스트(플래티넘 요청·필터 허용, 알 수 없는 등급은 400), `DrawSnapshotServiceTest`·`DrawStoredVerificationTest`(신규 입력의 플래티넘 가중치 10·사용 개수 검증과 기존 형식에 실제 플래티넘 근거를 넣은 경우 거절).
- 실제 MySQL: `PlatinumTicketIntegrationTest`(플래티넘 보유→응모 차감→`requested_platinum_count` 저장→이력 조회, 보유 수 변화, 상한 초과로 거절된 요청의 플래티넘 장수 저장). 통합 테스트 컨텍스트가 `ddl-auto=validate`로 새 ENUM·컬럼 매핑을 검증한다.
