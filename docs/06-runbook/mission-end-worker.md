# 미션 종료 워커 확인

GD-269의 운영 종료 시각이 지난 미션을 종료(`ENDED`)로 바꾸는 워커의 실행 방식과 확인 절차다. 미션 상태·제출 규칙의 원본은 공용 [미션 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/mission.md)이다.

## 동작

- `MissionEndWorker`가 기본 60초(이전 폴링이 끝난 뒤)마다 `MissionEndService.endDue`를 호출한다.
- 한 번의 갱신이 `status = ACTIVE`이고 `ends_at <= 지금`인 미션을 `ENDED`로 바꾸고 `updated_at`을 기준 시각으로 쓴다. 종료 시각 정각부터 종료로 본다. 이미 종료한 미션은 건드리지 않는다.
- 같은 조건으로 다시 실행해도 결과가 같아서 서버가 여러 대여도 충돌하지 않는다. 별도의 행 잠금이나 분산 잠금이 없다.
- 제출 가능 여부는 이 워커에 의존하지 않는다. 서버가 제출을 받은 시각이 운영 기간 안인지와 저장 상태가 `ACTIVE`인지를 함께 보므로, 워커가 멈추거나 늦어도 지난 미션은 제출을 받지 않는다. 워커는 관리자 목록·상세의 `status`와 상태 필터를 기간과 맞춘다.
- 기간 안에 도착한 제출은 검증이 종료 뒤에 끝나도 달성·보상을 반영하는 기존 규칙(도착 시각 기준)이 그대로 적용된다.
- 워커는 알림·추첨 워커가 쓰는 공용 스케줄러가 아니라 전용 스레드 1개(`mission-end-`)에서 돈다. 갱신이 잠금을 기다리며 오래 걸려도 다른 워커가 밀리지 않는다. 종료할 때는 진행 중인 갱신이 끝나기를 최대 30초 기다린다.

## 설정

| 속성 | 기본값 | 설명 |
| --- | --- | --- |
| `getddo.mission.end-worker.enabled` | `true` | `false`로 이 워커만 끈다 |
| `getddo.mission.end-worker.poll-delay` | `60000` | 폴링 간격(밀리초) |

- 통합 테스트 프로필(`application-test.properties`)에서는 워커를 끈다.
- 새 마이그레이션은 없다. 조회는 `missions` 테이블의 `status`, `ends_at` 조건이며 이 조건에 맞는 복합 인덱스는 두지 않았다. 미션 수가 지금처럼 적으면 폴링당 비용이 작아 문제가 없다. 미션이 수천 건 이상으로 늘거나 `Missions ended` 처리와 폴링 간격 사이의 지연이 눈에 띄면 `(status, ends_at)` 인덱스를 새 마이그레이션으로 추가하는 것을 검토한다. 실제 실행 계획은 측정하지 않았다.

## 확인 방법

- 성공 로그: `Missions ended: count=N`(종료한 미션이 있을 때만 남긴다). 실패 로그: `Mission end failed: exceptionType=...`. 예외 클래스만 남기고 메시지와 스택 트레이스는 남기지 않는다.
- 밀림 확인: 종료 시각이 지났는데 `ACTIVE`로 남은 미션이 계속 있으면 워커가 꺼져 있거나 실패 중이다.

  ```sql
  select count(*) from missions
  where status = 'ACTIVE' and ends_at <= utc_timestamp(6);
  ```

- 시각은 UTC로 저장한다. 위 쿼리는 읽기 전용이며 데이터를 바꾸지 않는다.
- 이 문서는 종료한 미션을 다시 `ACTIVE`로 되돌리는 절차를 정하지 않는다.
