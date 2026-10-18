# 감사 로그 기록 기능

- 관련 작업: GD-55
- 구현 위치: `core/audit`, `storage/db/audit`
- 공용 범위: [감사 기록 명세](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/audit.md) — 기록은 유지하며 공통 감사 조회 API는 제외

각 기능의 Service가 `AuditLogService.record(AuditLogCommand)`를 호출해 기존 `audit_logs`에 변경 이력을 추가한다. A는 공통 저장 기반을, 소비 담당자는 기록할 시점·내용·실제 호출부를 담당한다. 공통 감사 검색·목록·상세 API(AU01/AU02)와 전용 조회 화면은 2026-10-15 사용자 결정으로 이번 버전에서 제외한다. 내부 기록과 기존 감사 테이블·데이터는 유지하며, 개별 업무의 운영 내역 조회는 해당 계약을 따른다. 이 작업은 공통 저장 기반과 테스트용 연결을 제공하며 실제 업무 호출부나 조회 REST API를 추가하지 않는다.

## 입력 계약

`AuditLogCommand`는 불변 클래스이며 생성자에 아래 순서로 값을 전달한다. 값은 `getActorId()`, `getBeforeData()` 같은 getter로 읽는다. ID와 생성 시각은 기존 `BaseEntity`가 UUID v7과 공통 UTC Clock으로 생성한다.

| 값 | 조건 |
| --- | --- |
| `actorId` | 검증된 사용자 문맥의 UUID. 시스템 작업은 null. 값이 있으면 기존 `users` FK를 만족해야 한다 |
| `action` | 작업 코드. 필수이며 공백 불가, 최대 60자 |
| `targetType` | 대상 종류. 필수이며 공백 불가, 최대 60자 |
| `targetId` | 대상 UUID, 필수. 여러 종류를 가리키므로 대상 테이블 FK는 없고 소비 기능이 대상의 유효성을 확인한다 |
| `reason` | 처리 사유. null 허용, 기존 TEXT 컬럼 범위 |
| `beforeData`, `afterData` | 변경 전후의 최소 필드를 담는 `Map<String, Object>`. null 허용 |
| `requestId` | 호출자가 제공하는 요청 추적 ID. null 허용, 최대 100자 |

문자열 길이는 Unicode 코드 포인트 수를 기준으로 검사하며 입력을 잘라내거나 공백을 자동 제거하지 않는다. 내부 호출의 필수값·길이·JSON 형식 오류는 `IllegalArgumentException`으로 전달한다. 공개 API의 새로운 업무 오류 계약을 정의하지 않는다.

JSON 내부에는 문자열 키 Map, List, 배열, 문자열, boolean, null, Byte·Short·Integer·Long·BigInteger·BigDecimal 및 유한한 Float·Double만 허용한다. 배열은 목록으로 복사한다. final 필드만으로는 내부 컬렉션까지 불변이 되지 않으므로 생성자에서 중첩 컬렉션도 복사해 변경 불가능하게 보관한다. 원본 데이터나 접근자를 통해 기록 내용을 바꿀 수 없다. 임의 객체·Entity·순환 참조·문자열이 아닌 키·NaN·무한대는 거절한다. JSON 내부 null은 보존하고, 전후 데이터 자체가 null이면 SQL NULL로 저장한다.

## 업무 Service에서 호출

다음은 사용 형태를 보여주는 예시다. `TEST_STATUS_CHANGE`, `TEST_TARGET`은 예시 이름이며 공용 작업 코드가 아니다. 실제 작업 코드·대상 명칭·기록할 필드는 소비 담당자가 합의한다.

```java
@Transactional
public void changeStatus(UUID actorId, UUID targetId, String nextStatus) {
    var target = targetRepository.findById(targetId).orElseThrow();
    String previousStatus = target.getStatus();
    target.changeStatus(nextStatus);
    targetRepository.save(target);

    auditLogService.record(new AuditLogCommand(
        actorId,
        "TEST_STATUS_CHANGE",
        "TEST_TARGET",
        targetId,
        null,
        Map.of("status", previousStatus),
        Map.of("status", nextStatus),
        null
    ));
}
```

사용자 요청에서는 Controller가 `@CurrentUser User`의 `getId()`를 업무 Service에 전달한다. 요청 본문의 `actorId`나 `role`로 처리자를 정하지 않는다. 기존 사용자 헤더는 시연용 사용자 선택이며 신원 인증이 아니라는 제한은 [사용자 문맥 문서](user-profile.md)를 따른다. 시스템 작업은 호출 Service가 트랜잭션을 열고 `actorId=null`로 기록한다.

요청 본문·Entity 전체, 토큰·비밀값·무관한 개인정보를 JSON으로 넘기지 않는다. 공통 Service가 필드 이름을 보고 개인정보를 자동으로 제거하지 않으므로 호출자가 필요한 필드만 선택한다. `Map.of`는 null 값을 허용하지 않는다. JSON 필드의 null이 필요하면 null을 허용하는 Map/List를 구성해 전달한다.

## 트랜잭션과 실패 처리

`record`는 `@Transactional(propagation = MANDATORY)`로 호출 업무의 트랜잭션에 참여한다. 트랜잭션이 없으면 기록을 거절한다. 다른 Spring Bean에 주입한 Service를 통해 호출하며, 직접 `new`로 생성하면 트랜잭션 프록시가 적용되지 않는다.

- 업무와 감사 기록이 함께 commit된다.
- 업무 예외로 rollback되면 감사 기록도 취소된다.
- 감사 저장 실패는 숨기지 않고 전파한다. 호출자는 예외를 삼키거나 같은 트랜잭션에서 계속 처리하지 않는다.
- SQL 실행과 FK 검사는 flush 또는 commit까지 지연될 수 있다. 반환 UUID는 할당된 기록 ID이며 최종 commit 성공을 의미하지 않는다.
- 실패 시도 자체를 보존하는 로그가 필요하면 별도 계약이 필요하다. 현재 기능은 rollback된 업무의 시도를 남기지 않는다.

`requestId`는 자동 생성하지 않으며, `Idempotency-Key`와 같은 값으로 간주하지 않는다. 같은 requestId로 여러 번 호출하면 각각 새 기록을 만든다. 업무 재시도 시 중복 방지는 소비 기능이 담당한다.

JPA Auditing은 식별자·생성 시각을 제공한다. GD-55는 처리자·대상·사유·변경 전후 정보를 저장한다. 기존 migration과 공통 Entity는 변경하지 않으며, 감사 Entity에는 수정 시각·수정 메서드가 없다. JPA 변경 감지도 비활성화하지만 DB 직접 수정까지 막는 권한 정책을 제공하는 것은 아니다.

## 검증

```bash
./gradlew :core:test --tests 'com.getddo.core.audit.*'
./gradlew :storage:db:integrationTest --tests 'com.getddo.db.audit.*'
./gradlew :api:integrationTest --tests 'com.getddo.api.audit.*'
./gradlew test
./gradlew build
```

실제 MySQL 통합 테스트와 전체 빌드는 Docker가 필요하다. storage 테스트는 Flyway 스키마 검증, JSON/FK/UTC 저장, 업무 성공·실패 및 감사 실패의 commit/rollback, 트랜잭션 없는 호출과 requestId 중복을 확인한다. API 테스트는 실제 사용자 헤더·DB 조회·감사 저장을 연결하며, 테스트 전용 Controller/Service는 운영 JAR에 포함되지 않는다.

## 공통 조회 제외 후 검증 — 2026-10-16

2026-10-15 사용자 요청으로 GD-207의 조회 전용 코드·테스트·문서17개를 제거했다. 기존 감사 기록 생산 코드·저장 테스트·마이그레이션은 유지했다. 기록 관련 단위22개·API 처리자2개·MySQL 저장6개가 성공했고(1분39초), 이후 전체 `test build --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel`도 BUILD SUCCESSFUL(28분8초)로 완료됐다. XML 총2,126개(core801/api298/api통합203/DB50/DB통합774), 실패·오류·건너뜀0이다. 변경 없는 빌드 작업은 up-to-date 결과도 사용했다. 이 결과는 커밋·푸시 전 로컬 작업 트리 기준이며 원격 CI 실행 결과가 아니다.
