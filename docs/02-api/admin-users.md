# 관리자 사용자 조회 구현·연동 안내

공용 계약 원본은 [spec AO01/AO02](https://github.com/GETDDO/getddo-spec/blob/main/05-api/user.md)다. 이 문서는 GD-206과 GD-241의 구현 구조·검증·연결 방법을 설명한다.

## 구현 흐름

`AdminUserController → AdminUserQueryService → AdminUserQueryRepositoryImpl / 기존 UserRepository`

- 공통 CurrentUser가 헤더를 DB와 대조한다. 선택 멤버십도 대조하도록 `membershipRequired=false`를 사용하고, 활성 관리자 여부는 서비스에서 검사한다.
- 목록은 JPA Criteria API로 선택 조건을 조합한다. count와 목록에 같은 조건을 적용하고 기존 Entity·UserMapper를 재사용한다. 목록을 먼저 읽고 첫 페이지 또는 비어 있지 않은 마지막 페이지가 크기를 채우지 못하면 전체 건수를 계산해 검색 count를 생략한다. 가득 찬 페이지와 첫 페이지 이후의 빈 페이지는 정확한 건수를 별도로 센다. JPA 오프셋 범위를 넘는 페이지는 PageOffsets로 처리한다.
- 상세는 기존 사용자 저장소를 재사용한다. 호출자 확인 실패와 상세 대상 부재의 오류를 구분한다.
- 서비스의 읽기 전용 트랜잭션 안에서 목록과 DB 전체 활성 일반 사용자 수를 조회하고, API에서는 AdminUserListResponse와 AdminUserResponse로 공개 필드만 변환한다.
- V034에서 `users(created_at DESC, id DESC)`와 `users(role, status)` 인덱스를 추가한다. 깊은 OFFSET의 선행 항목 스캔 비용은 남는다. 사용자 모델·인증 동작·기존 가상 사용자 선택 API는 변경하지 않는다. UserErrorCode에는 채택한 관리자 조회 오류만 추가했다.

## FE 연결

관리자 화면에서 기존 공통 사용자 문맥 헤더와 `/api/v1/admin/users` 목록·상세를 호출한다. 실제 경로에 `/api`가 이미 있으므로 FE baseURL에 따른 접두사 중복 여부를 확인한다. 멤버십은 소문자로 표시·전달한다. 기존 `/virtual-users` 시연 계정 선택은 별도 기능이다.

검색과 페이지 변경 시 쿼리를 함께 갱신하고, 호출자 자격 오류와 없는 상세를 구분한다. 정확한 필드·기본값·코드·메시지는 위 공용 원본을 따른다. FE 담당자 구현·실제 브라우저 연결은 별도 확인이 필요하다.

## 대시보드 참여율의 분모

`GET /api/v1/admin/users`의 `data.totalUserCount`는 DB 전체의 현재 활성 일반 사용자(`USER`·`ACTIVE`) 수다. 요청에 role·status 조건을 붙이지 않아도 반환하며 검색어·멤버십·페이지 조건과 무관하다. 검색 결과가 비어도 이 값은 반환한다. 기존 `data.totalElements`는 전달한 검색 조건에 맞는 전체 건수라는 의미를 유지한다. `role=USER&status=ACTIVE`이고 ID·이름·멤버십 필터가 없으면 두 건수가 같으므로 검색 결과의 전체 건수를 재사용한다. 나머지 조건에서는 활성 일반 사용자 수를 별도로 집계하며, 캐시나 근삿값은 사용하지 않는다.

예를 들어 비활성 관리자를 검색해 `totalElements`가 1이어도 DB 전체의 활성 일반 사용자가 1,200명이면 `totalUserCount`는 1200이다. 활성 일반 사용자가 없으면 0이다. 이 필드는 사용자 한 명의 DTO나 상세 응답이 아니라 목록 응답의 페이지 필드 옆에 위치한다.

[관리자 일별 집계](admin-daily-stats.md)의 `data.totalUserCount`도 같은 조건의 현재 사용자 수를 반환한다. 대시보드가 일별 집계를 호출한다면 이 필드를 사용하고 사용자 목록을 추가로 호출할 필요가 없다. 날짜별 활동 사용자 수인 `days[].activeUserCount`와 구분하며, 조회 기간을 바꿔도 계정 상태가 같으면 분모는 같다. 두 API를 서로 다른 시점에 호출하는 동안 계정이 추가되거나 상태가 바뀌면 값은 달라질 수 있다.

배포 서버에서는 활성 관리자 문맥 헤더로 위 사용자 목록 요청과 일별 집계 요청을 호출해 HTTP 200과 각 건수 필드를 확인한다. 경로 존재나 Swagger 표시만으로 필터 적용과 응답 성공을 확인했다고 판단하지 않는다. 코드·통합 테스트 확인은 배포 서버 호출 성공과 구분한다.

## 검증

- 단위: 관리자 자격, 상세 저장소 재사용, 부재 오류, 페이지/검색어 생성자 검증.
- 실제 MySQL·MVC: 헤더/권한, AND 필터, 특수문자 검색, 안정 정렬, 전체 건수, 기본·범위 밖·큰 페이지, 공개 필드·UTC 정밀도, 조회 전후 사용자 행 비교.
- 참여율 분모: 목록의 `totalUserCount`가 필터·페이지·빈 검색 결과와 무관한 DB 전체 활성 일반 사용자 수인지, 일별 집계와 일치하는지, 비활성화 후 현재 건수가 감소하는지 확인한다.
- 실행 결과는 PR과 당일 공용 워크로그에 실제 완료 후 기록한다.

```bash
./gradlew :core:test --tests '*AdminUserQueryServiceTest' \
  :api:integrationTest --tests '*AdminUserIntegrationTest' \
  --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel
./gradlew test build --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel
```
