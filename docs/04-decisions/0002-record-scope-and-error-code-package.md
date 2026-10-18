# ADR-0002: record 사용 범위와 도메인 오류 코드 위치

- 상태: 제안
- 작성자 / 날짜: 윤태형(초안 Claude, 담당자 확인 전) / 2026-09-30
- 검토자:
- 관련 작업 기록 / PR: [GD-46](https://ureca4.atlassian.net/browse/GD-46), [PR #9](https://github.com/GETDDO/getddo-be/pull/9), [윤태형 2026-09-30 작업 기록](../05-worklogs/윤태형/2026-09-30.md)
- 대체한 ADR / 대체된 ADR: 해당 없음

## 배경

`code-style.md` §3에는 "JSON 요청·응답 DTO는 `record`를 기본으로 한다"는 문장만 있었고, 다른 곳의 `record` 사용은 정하지 않았다. 2026-09-30 팀 대화에서 팀원들은 "DTO만 record"로 알고 있었지만, 실제 코드는 다음과 같았다.

- `core/common/pagination`의 `PageQuery`, `PageResult`, `CursorQuery`, `CursorResult`가 `record`였다.
- 응모권 도메인(`GrantCommand`, `GrantSource`, `GrantResult`, `GrantSourceClaim`, `TicketWallet`, `TicketWalletPeriod`, `TicketLedger`, `TicketLedgerAllocation`)과 알림 도메인(`Notification`)이 `record`였다.

도메인 오류 코드 enum의 패키지도 정해져 있지 않아 `TicketErrorCode`, `NotificationErrorCode`가 각 도메인의 `domain` 패키지에 있었다.

## 결정과 이유

1. **`record`는 JSON 요청·응답 DTO에만 사용한다.** `core`의 도메인 객체·값 객체·서비스 입출력 값과 공통 페이징 모델은 `private final` 필드와 getter(`getXxx()`, boolean은 `isXxx()`)를 가진 불변 클래스로 작성하고, 값 비교가 필요하면 `equals`·`hashCode`를 구현한다.
2. **도메인 오류 코드 enum은 `core/<도메인>/exception` 패키지에 둔다.** 공통 계약인 `ErrorCode`·`BusinessException`은 `core/common/exception`에 둔다.

확인된 근거는 팀원들이 알고 있던 규칙("DTO만 record")과 문서·코드를 일치시키기 위한 2026-09-30 팀 논의다. 그 밖의 결정 이유는 담당자 확인 후 보강한다.

규칙 본문은 [`code-style.md`](../01-conventions/code-style.md) §3·§7, 패키지 배치는 [루트 README](../../README.md#패키지-구조)에 반영했다.

## 검토한 대안

- **식별자가 있는 도메인 객체만 클래스로 두고, 값 객체·서비스 입출력 값·페이징 모델은 `record`를 허용하는 안.** 변경 범위가 `TicketWallet`, `TicketLedger` 두 개로 줄고 공통 페이징 모델과 공개 계약(`GrantCommand` 등)을 바꾸지 않는다. 팀 논의에서 1번 결정을 택했으며, 이 안을 채택하지 않은 이유는 기록되지 않았다(담당자 확인 필요).
- `CommonErrorCode`의 위치(`api/common/exception`)는 이번 결정에서 바꾸지 않는다. 이동 여부는 논의되지 않았다.

## 영향과 재검토 조건

- PR #9에서 응모권 8개와 공통 페이징 4개를 불변 클래스로 바꾸고 접근자를 getter로 바꿨다. 이 타입을 쓰는 코드는 `items()` 대신 `getItems()`처럼 getter를 사용해야 한다. 알림 컨트롤러의 호출 3곳은 함께 수정했다.
- 아직 남은 적용 대상: 알림의 `Notification`, `NotificationRepositoryImpl` 내부 `Cursor`, `NotificationErrorCode`의 패키지 이동(알림 담당).
- 미결정: 테스트 코드의 `record`(시드·테스트 전용 값)에도 이 규칙을 적용할지.
- 응모권 지급 인터페이스 설계 문서 §2는 `record` 시그니처 기준이라 갱신이 필요하다.
- getter·`equals`·`hashCode`를 직접 작성해야 하므로 값 객체의 코드량이 늘어난다. 이 부담이 크다고 판단되면 대안(값 객체의 `record` 허용)을 다시 검토한다.
