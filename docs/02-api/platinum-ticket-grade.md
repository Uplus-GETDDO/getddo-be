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

플래티넘을 **받아서 다룰 수 있게** 했다(GD-228). 이 단계에서는 실제 지급을 켜지 않았고(기본 확률 0%), 추첨의 가중치 지원(GD-230)이 병합된 뒤 GD-229에서 기본 확률을 0.02%로 올려 지급을 켰다. 이유는 아래 "지급을 켜는 순서"를 본다.

- `TicketGrade.PLATINUM` 추가. 등급별 맵을 `TicketGrade.values()`로 채우는 곳은 자동으로 `PLATINUM` 키를 갖는다. 그래서 응모 응답의 `requestedTicketsByGrade`·`deductedTicketsByGrade`와 보유 조회의 `countByGrade`에 `PLATINUM`(0장이어도) 키가 새로 생긴다.
- 마이그레이션 [V029](../../storage/db/src/main/resources/db/migration/ticket/V029__add_platinum_ticket_grade.sql): `tickets.grade`·`reward_policies.reward_ticket_type` ENUM에 `PLATINUM`을 추가하고, `event_entries.requested_platinum_count`(기본 0)를 더하며 등급별 요청 장수 CHECK를 `>= 0`으로 다시 정의했다. 기존 행은 그대로다.
- 응모 저장·조회 경로(`EventEntryEntity`, `EntryMapper`, `EntryRepositoryImpl`)가 플래티넘 요청 장수를 읽고 쓴다. 관리자 응모 내역과 내 응모 이력도 같은 경로를 쓴다.
- 등급 추첨기 `RandomTicketGradeDrawer`를 1만 분율(basis point)로 바꿨다. 0.02%를 표현하려면 0~99 대신 0~9999를 뽑아야 한다.

## 등급 확률 설정

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `getddo.ticket.grade-probability.silver-bp` | 1800 | 실버 18% |
| `getddo.ticket.grade-probability.gold-bp` | 200 | 골드 2% |
| `getddo.ticket.grade-probability.platinum-bp` | **2** | 플래티넘 0.02% (GD-229에서 0에서 2로 변경) |

브론즈는 10000에서 세 값을 뺀 나머지라 기본값에서 79.98%다. 어느 값이든 음수이거나 세 값의 합이 10000을 넘으면 서버가 시작할 때 실패한다. 환경별로 플래티넘 지급을 끄려면 `platinum-bp`를 0으로 설정한다. 이때는 기존과 같은 80/18/2 구간으로 동작한다.

## 지급을 켜는 순서 (왜 처음엔 0이었나)

GD-228은 등급·차감·저장 경로를 추가하고, GD-230은 추첨의 플래티넘 가중치 10 지원을 구현한다. GD-230 코드의 `DrawSnapshotService`·`DrawStoredVerification`은 Rules 3/4·EntryEvidence 2에서 `브론즈 + 실버×3 + 골드×5 + 플래티넘×10`을 계산·검증한다. 기존 Rules 1/2 입력에 실제 PLATINUM USE 근거가 들어온 경우에만 증거 오류로 거절한다. 상세는 [추첨 저장 문서](../03-database/drawing-selection-storage.md#gd-230-플래티넘-입력과-저장-호환)를 따른다.

1. GD-228(등급 추가, 지급 꺼짐)은 PR #126으로 병합됐다.
2. 플래티넘 가중치·근거 구조와 기존 스냅샷 호환은 GD-230(PR #128)에서 구현해 병합했다. 배포 및 검증 완료 여부는 별도로 확인한다. 통합 테스트는 작성자가 중단했으며 완료하지 않았다.
3. GD-229에서 `platinum-bp` 기본값을 2로 올려 지급을 켰다. 지급 확률은 이 변경만 바꾸고, 이미 지급·응모·추첨된 이력은 소급 변경하지 않는다.

지급 확률은 새 저장 형식 쓰기와 무관하다. GD-230부터 플래티넘이 없는 응모도 Rules 3/4로 저장하므로, 신규 형식을 읽지 못하는 구버전 서버와 혼용하지 않는다. 새 형식 생성 후 롤백이 필요하면 Rules 3/4·EntryEvidence 2를 읽을 수 있는 호환 버전을 사용한다. 구버전으로 되돌리기 위해 저장된 JSON을 덮어쓰지 않는다. 이 문구는 배포·롤백 검증 완료를 뜻하지 않는다.

## 배포 순서와 롤백 (GD-229 병합 전 필수)

GD-229는 기본 확률을 바꾸므로 **배포하는 순간부터 게임·미션 보상에서 플래티넘이 나올 수 있다.** 아래 순서를 지킨다.

1. 추첨을 처리하는 **모든 인스턴스**에 GD-230(PR #128)이 먼저 배포돼 있어야 한다. 구버전이 하나라도 남아 있으면 GD-229를 배포하지 않는다. 구버전 서버는 플래티넘 사용 근거를 거절할 수 있고 Rules 3/4 스냅샷을 읽지 못한다.
2. GD-229를 배포한다. 배포 도중 서버가 섞여도 되는 것은 GD-230 이상끼리뿐이다.
3. 배포 직후 보상 지급과 응모가 정상인지 확인한다. 플래티넘은 0.02%라 바로 나오지 않을 수 있으니, 필요하면 운영 도구가 아니라 테스트 데이터로 `PLATINUM` 응모권을 직접 지급해 응모·추첨 경로를 확인한다.

**롤백**
- 지급만 멈추려면 코드를 되돌리지 않고 설정 `getddo.ticket.grade-probability.platinum-bp`를 0으로 두고 재시작한다. 이미 지급된 플래티넘 응모권과 저장된 이력은 그대로 남고 계속 사용·추첨할 수 있다.
- 코드를 되돌려야 하면 GD-230 이상으로만 되돌린다. GD-230 이전 버전으로는 되돌리지 않는다. Rules 3/4 스냅샷이 한 번 만들어지면 구버전이 읽지 못한다.
- 롤백하려고 저장된 JSON이나 이력을 고치지 않는다.

이 절은 절차이며, 실제 배포·롤백을 검증했다는 뜻이 아니다. 현재 어느 서버에 GD-230이 배포됐는지는 이 저장소에서 확인할 수 없어 배포 담당이 확인한다.

## 영향과 알려야 할 것

- 프론트: 응모·응모 이력·보유 조회 응답의 등급별 맵에 `PLATINUM` 키가 추가된다. 등급 표시와 목업을 확인해야 한다.
- 게임 보상: 게임 담당(전민규)의 보상 응답에도 플래티넘 등급이 나갈 수 있다.
- 추첨: 위 순서의 2단계는 추첨 담당(석종수)의 GD-230이며, 실제 지급 활성화는 GD-229다.

## 검증

- 단위: `RandomTicketGradeDrawerTest`(설정이 없을 때 기본값이 79.98/18/2/0.02인지 Spring 설정 해석으로 확인, 플래티넘 구간 경계 7997/7998·9797/9798·9997/9998, `platinum-bp`를 0으로 끈 구간이 기존 80/18/2와 같음, 출석 브론즈 고정, 난수 범위, 설정 합·음수 검증), 응모·관리자 응모권 컨트롤러 테스트(플래티넘 요청·필터 허용, 알 수 없는 등급은 400), `DrawSnapshotServiceTest`·`DrawStoredVerificationTest`(신규 입력의 플래티넘 가중치 10·사용 개수 검증과 기존 형식에 실제 플래티넘 근거를 넣은 경우 거절).
- 실제 MySQL: `PlatinumTicketIntegrationTest`(플래티넘 보유→응모 차감→`requested_platinum_count` 저장→이력 조회, 보유 수 변화, 상한 초과로 거절된 요청의 플래티넘 장수 저장). 통합 테스트 컨텍스트가 `ddl-auto=validate`로 새 ENUM·컬럼 매핑을 검증한다.
