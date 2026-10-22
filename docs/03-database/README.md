# ERD·설명

- [플레이 검증 기준과 최초 결과 저장](game-play-storage.md): GD-170 저장 모델·트랜잭션 경계·V020 업그레이드.

추첨 후보 재사용·결과 복합 FK와 선정·공개 분리의 근거는 [ADR-0003](../04-decisions/0003-drawing-snapshots-and-publications.md)을 참고한다. 최신 DBML에는 공개 버전별 포함 결과와 재추첨 관리자 확인 이력을 반영했다. GD-93에서 초기 Flyway SQL에 반영했으며, 실제 DB 적용과 서비스 코드의 호환성 수정은 후속 작업이다.

- [API·DB 대응과 스키마 검토](api-schema-mapping.md): 공용 API 초안과 현재 Flyway SQL의 대응 및 백엔드 저장 설계 검토 항목.
- [GD-138 개별 당첨 취소·재추첨 저장](award-cancellation-storage.md): 취소·등록 원자성, 멱등 키와 GD-87 공통 실행 연결.
- [GD-84·GD-130·GD-85·GD-87 추첨 저장·공통 실행](drawing-selection-storage.md): 후보·결과와 실패·재시도·동일 실행 재개 계약. 구현은 검토 대기이며 GD-138 실제 연결 기준 전체 test/build를 검증했다.
- [통합 스키마 DBML](schema.dbml): 사용자 최종 SQL을 기준으로 최신화하고 티켓·추첨 근거 제약을 보완한 44개 테이블의 초기 스키마 기준. UNIQUE는 indexes, FK는 Ref, CHECK는 checks 블록으로 정의한다. MySQL 생성식은 DBML 주석에 기록한다.
- [MySQL 마이그레이션](../../storage/db/src/main/resources/db/migration): V001~V011에 DBML의 44개 테이블과 UNIQUE·FK·CHECK를 반영했다. 사용자 요청에 따라 초기 세팅으로 기존 번호를 유지했다. 적용 이력이 있는 DB에는 체크섬 충돌이 발생할 수 있으므로 적용 여부 확인이 필요하다. 기존 지갑·원장 기반 코드와 변경된 컬럼을 사용하는 코드는 담당자가 후속 수정해야 한다.
- [출석·연속 출석·미션·게임 테이블 분리 설계](reward-tables.md): 대체됨. DDL 작성 전의 옛 논리 설계 초안이다. 초기 스키마의 기준은 `schema.dbml`, 이후 추가분을 포함한 실제 스키마의 기준은 마이그레이션이다.

티켓·추첨 SQL 초안은 사용자 요청으로 정리했다. 티켓 전용·전체 SQL은 Git 커밋 `850f3ba`에, 기존 추첨 SQL은 삭제 이전 Git 이력에 보존되어 있다. 최신 설계 기준은 `schema.dbml`이다.

GD-84의 [V016](../../storage/db/src/main/resources/db/migration/drawing/V016__add_initial_draw_terminal_states.sql)는 최초 추첨 실행에 응모자 없음·전원 제외 정상 종료 상태를 추가했다. GD-130 이후 새 실행은 전원 제외 상태를 만들지 않으며 과거 조회 호환성을 위해 V007·V016과 상태 정의를 유지한다. 현재 동작은 [추첨 저장 설계](drawing-selection-storage.md)를 따른다.

이벤트의 신규 종료 상태는 `NO_ENTRANTS`(응모자 없음)를 사용하며 `NO_ELIGIBLE_ENTRANTS`(전원 추첨 제외)는 사용하지 않는다. 기존 V006·DBML과 Java enum의 값은 DB 상태 매핑을 위해 보존하며, 이를 신규 공개 API의 상태 계약으로 해석하지 않는다. 이벤트 조회의 현재 상태 필터 테스트도 폐지된 상태를 생성하거나 검색 대상으로 사용하지 않는다. 과거 이벤트 상태의 공개 조회 처리는 [공용 이벤트 API](https://github.com/GETDDO/getddo-spec/blob/main/05-api/event.md)에 명시된 별도 검토 사항이다. `event_participants`에는 추첨 제외 상태·사유를 대체하는 정보를 추가하지 않는다.

## 마이그레이션 번호 규칙

Flyway 버전 번호는 중복될 수 없지만 연속일 필요는 없다. 예를 들어 `V012__a.sql`, `V014__b.sql`, `V015__c.sql`만 있으면 존재하는 파일을 `12 → 14 → 15` 순서로 적용한다. `V013`이 없다는 이유로 오류가 발생하거나 실행을 기다리지 않는다. 번호의 빈 구간과 SQL 간 의존성은 별개이므로, `V014`가 `V013`에서 만드는 테이블·컬럼을 사용한다면 필요한 변경이 먼저 적용되어야 한다.

세부 버전은 점(`.`)으로 표기한다. 예를 들어 `V010.1__a.sql`, `V010.2__b.sql`처럼 작성한다. 버전과 설명 사이의 구분자는 밑줄 두 개(`__`)다.

이미 높은 버전을 적용한 DB에 낮은 버전의 파일을 나중에 추가하는 경우는 별도로 확인한다. 기본 설정인 `outOfOrder=false`에서는 뒤늦게 추가한 낮은 버전이 적용되지 않으며 검증 오류가 발생할 수 있다. `outOfOrder=true`로 적용하려면 기존 DB와 새 DB의 실행 순서가 달라져도 SQL 의존성과 최종 스키마에 문제가 없는지 검증해야 한다.

공유·운영 DB에 적용된 마이그레이션의 번호와 내용은 유지하고 후속 변경은 새 버전으로 추가한다. 임시 번호로 개인 DB에 적용한 파일도 번호만 바꾸면 적용 이력과 불일치하므로, 재생성 가능한 개인 테스트 DB에서 최종 번호와 실행 순서를 검증한다. 공유 DB의 적용 이력을 임의로 수정하지 않는다.

정수 번호와 세부 버전의 사용 기준, 번호 배정 시점·담당자 및 동시 PR 충돌 처리 방식은 아직 이 문서에서 확정하지 않는다. 병합 순서에 맞춰 최종 번호를 확정하는 방식과 타임스탬프 버전은 검토 가능한 제안이며 팀 합의 후 기록한다.

- [Flyway 버전 마이그레이션과 명명 규칙](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [버전 번호 변경과 적용 이력](https://documentation.red-gate.com/fd/renaming-a-migration-script-192840442.html)
- [순서 외 실행 설정](https://documentation.red-gate.com/fd/flyway-out-of-order-setting-277579015.html)
- [검증 오류 코드](https://documentation.red-gate.com/fd/validate-error-codes-287015530.html)

GD-87의 운영 미적용 [V019 실행 제어 필드](../../storage/db/src/main/resources/db/migration/drawing/V019__add_draw_execution_control.sql)는 신규 테이블 없이 기존 draw_runs의 4개 nullable 필드·제약·인덱스를 확장한다. 이 워크트리의 DBML에는 해당 구현안을 표시했으며 운영 DB 적용 완료를 의미하지 않는다. V019는 Testcontainers 임시 MySQL에서 적용·검증했다. 2026-10-12 확인한 원격 dev b2be294에는 V019가 반영되어 있으며 GD-138 브랜치의 V019와 파일 경로·SQL 내용이 동일하다. 사용자 요청으로 GD-170의 V020과 충돌하던 GD-138 신규 마이그레이션 번호를 미사용 V022로 변경했고 SQL 내용은 유지했다. V017/V019와 [V022 취소 멱등 키](../../storage/db/src/main/resources/db/migration/drawing/V022__add_award_cancellation_request_key.sql)를 포함한 최초 검증에서는 마이그레이션 17개를 확인했다. 최신 dev의 V021을 함께 보존한 병합에서는 18개이며, 병합 후 재검증은 GD-72 코드의 컴파일 오류로 실행되지 못했다. V022의 nullable request_key·UNIQUE는 임시 MySQL에 적용했고 운영 DB에는 적용하지 않았다. 공유·운영 DB 적용 순서는 별도로 확인한다.

- [응모 차단 이력 전환·수동 시드](../06-runbook/entry-abuse-migration.md): GD-142 새 마이그레이션 적용 전제와 실패 확인.
