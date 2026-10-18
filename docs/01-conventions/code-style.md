# 백엔드 코드 스타일 초안

- 상태: 검토 대기
- 적용 대상: `getddo-be`의 Java·Spring 코드

이 문서는 현재 코드와 채택된 백엔드 결정을 바탕으로 작성한 팀 합의용 초안이다. 코드 리뷰에서는 실제 동작, 계약, 정합성에 영향을 주는 항목을 우선하고, 포맷 취향만 다른 코드를 결함으로 취급하지 않는다. 공용 요구사항·도메인 정책·용어는 [`getddo-spec`](https://github.com/GETDDO/getddo-spec)이 원본이며 이 문서에서 다시 확정하지 않는다.

## 1. 모듈과 책임

- `api`는 애플리케이션 실행, HTTP 요청·응답, Controller와 API DTO를 맡는다. Controller는 요청 해석·검증과 응답 변환을 하고 업무 규칙은 Service에 위임한다.
- `core`는 도메인 객체, 업무 규칙, Service와 Repository 인터페이스를 맡는다. HTTP DTO, JPA Entity, `api`·`storage:db` 구현에 의존하지 않는다.
- `storage:db`는 JPA Entity, Repository 구현, Entity와 도메인 객체의 변환을 맡는다. 의존 방향은 [루트 README](../../README.md#모듈-의존-관계)를 따른다.
- 새 기능은 [패키지 배치 기준](../../README.md#패키지-구조)을 따르고, 이미 같은 역할을 하는 공통 객체가 있으면 재사용한다. 기능 간 계약 변경은 [담당 경계](backend-feature-assignments.md)를 먼저 확인한다.

## 2. 생성자 주입과 객체 생성

- 운영 코드의 의존성은 `private final` 필드와 생성자 주입으로 명시한다. 필드 주입이나 의존성을 바꾸는 setter는 사용하지 않는다. 테스트의 Spring 필드 주입은 예외로 둔다.
- Lombok을 사용할 수 있는 모듈에서는 `@RequiredArgsConstructor`를 사용할 수 있다. 현재 Lombok은 `api`·`core`·`storage:db`에 선언되어 있다. `api`의 알림 워커는 `@Slf4j`로 로그를 선언하고 명시적 생성자를 사용한다. `@RequiredArgsConstructor`를 전 모듈 기본 규칙으로 정하려면 별도 합의한다.
- Lombok `@AllArgsConstructor`는 사용하지 않는다. 생성 시 검증이 필요한 객체는 명시적 생성자나 정적 팩터리를 사용한다. 의존성 주입에는 기존 규칙대로 `@RequiredArgsConstructor`를 사용할 수 있다.
- `core` 도메인 객체에는 `@Builder`를 사용하지 않는다. API 요청·응답 DTO를 생성할 때는 `@Builder` 사용을 적극 권장한다. 요청 DTO에 적용할 때는 JSON 역직렬화와 입력 검증이 정상 동작하는지 확인한다.
- 최초 상태와 기본값이 있는 도메인 객체는 `User.create(...)`처럼 생성 목적을 드러내는 정적 팩터리에 초기화를 모은다. 상태가 없는 단순 값 객체까지 정적 팩터리를 강제하지 않는다. 생성 후 여러 setter를 호출해야 유효해지는 방식은 피한다.

## 3. DTO와 변환

- JSON 요청·응답 DTO는 `record`를 기본으로 한다. 이름은 역할에 따라 `XxxRequest`, `XxxResponse`로 구분한다. 폼·쿼리 파라미터 바인딩 등에서 다른 형태가 필요하면 해당 DTO의 실제 바인딩 방식을 확인한다.
- `record`는 JSON으로 주고받는 API 요청·응답 DTO에서만 사용한다. `core`의 도메인 객체·값 객체·서비스 입출력 값과 공통 페이징 모델은 클래스로 작성한다.
- 상태 변경이 필요한 도메인 객체는 범용 setter 대신 변경 목적이 드러나는 메서드를 둔다. 상태 변경이 없는 모델은 `private final` 필드를 사용한다. 필요한 접근자는 `getXxx()`·`isXxx()` 형태로 제공하며 Lombok `@Getter`를 사용할 수 있다. 값 비교가 필요하면 `equals`·`hashCode`를 함께 구현한다.
- 단순 변환의 방향은 요청 DTO의 `toDomain()`, 응답 DTO의 `from(domain)`, `storage:db` Mapper의 `toDomain()`·`toEntity()`로 표현한다. 변환 중 업무 정책을 판정하거나 DB를 조회해야 하면 변환 메서드에 넣지 않고 Service·도메인에서 처리한다.
- Entity와 도메인 사이의 단순 필드 변환은 `@Mapper(componentModel = "spring")`을 사용해 MapStruct로 생성하고 주입한다. Lombok과 함께 사용할 때는 `lombok-mapstruct-binding` annotation processor를 등록한다.
- DTO, 도메인 객체, JPA Entity를 서로 직접 대체하지 않는다. 특히 Entity와 도메인 객체를 API에 그대로 노출하지 않는다. `record`나 불변 클래스에 가변 컬렉션이 있으면 필요한 경우 복사해 외부 변경으로부터 보호한다.

## 4. 입력 검증과 업무 검증

- 요청 DTO의 필수 여부·문자열 길이·양수 여부 같은 입력 형식은 `@NotNull`, `@NotBlank`, `@Size`, `@Positive` 등으로 선언한다. Controller는 해당 요청에 `@Valid`를 적용한다.
- 상태 전이, 자격, 중복 처리 등 업무 규칙은 Service·도메인에서 검증한다. Controller에는 업무 판정을 넣지 않는다.
- 예외 조건은 먼저 검사하고 조기 반환 또는 예외로 처리해 중첩을 줄인다. 정상 흐름이나 필요한 트랜잭션 경계가 오히려 흐려진다면 형식만을 위해 분기를 바꾸지 않는다.

## 5. Entity와 조회

- JPA Entity는 필요한 범위의 `@Getter`와 JPA용 `protected` 기본 생성자를 두고, 범용 setter나 `@Data`로 모든 상태 변경을 열지 않는다. `updateTermsAgreed()`처럼 변경 목적이 드러나는 메서드에서 상태를 바꾼다. 공통 식별자·시각 필드는 `BaseEntity` 또는 `BaseUpdatableEntity`를 사용한다. 근거는 [ADR-0001](../04-decisions/0001-common-entity-uuid-auditing.md)을 따른다.
- 관련 Entity를 함께 조회하거나 변경하는 흐름에는 JPA 연관관계를 매핑한다. DB에 외래 키가 있다는 이유만으로 모든 역방향 관계를 만들지 않고, 실제 탐색 방향에 필요한 관계만 둔다. 연관관계 매핑은 `storage:db`의 Entity에만 두며 `core` 도메인 객체에 JPA 어노테이션을 붙이지 않는다.
- `@ManyToOne`·`@OneToOne`의 로딩 방식은 명시하고, 기본적으로 `FetchType.LAZY`를 사용한다. `@JoinColumn`의 컬럼명과 null 허용 여부는 기존 Flyway 스키마에 맞춘다. 필요한 연관 데이터는 조회 메서드에서 fetch join·EntityGraph·별도 조회 등으로 가져와 N+1을 확인한다.
- 양방향 관계와 `@OneToMany` 컬렉션은 실제 사용처가 있을 때만 추가한다. `CascadeType.REMOVE`와 `orphanRemoval`은 자식의 생명주기와 삭제 정책을 확인한 뒤 사용한다. 연관 Entity를 API 응답으로 직접 반환하지 않는다.
- 단건 조회에서 결과가 없을 수 있으면 Repository 인터페이스는 `Optional<T>`로 부재를 표현한다. 부재가 업무상 오류라면 Service에서 `orElseThrow()`로 도메인 오류를 결정한다. 부재가 정상 결과인 조회나 목록 조회에는 해당 계약에 맞는 반환형을 사용한다.
- 중복 처리나 잔액·상태 불일치 위험은 DB 제약과 원자적 갱신까지 검토한다. 기존 Flyway 마이그레이션은 수정하지 않고 새 파일을 추가한다.

## 6. Service와 트랜잭션

- Service 메서드의 트랜잭션은 실제 업무 단위에 맞춰 정한다. 조회 트랜잭션에는 `@Transactional(readOnly = true)`, 변경 트랜잭션에는 `@Transactional`을 사용하는 방향으로 검토한다. 모든 메서드에 어노테이션을 기계적으로 붙이지 않는다.
- Service는 `core`에 두고 `spring-context`의 `@Service`와 `spring-tx`의 `@Transactional`을 사용한다. 의존성 버전은 기존 Spring Boot BOM을 따른다. JPA Entity·Repository 구현은 계속 `storage:db`에 두며 `core`에 JPA 의존성을 추가하지 않는다.
- 함께 성공하거나 실패해야 하는 저장 작업은 Service의 하나의 트랜잭션으로 묶는다. Spring Bean을 통해 호출되는 메서드에 적용하며, 같은 객체 안에서의 직접 호출에는 새 트랜잭션 설정이 적용되지 않음을 유의한다. 기본 롤백 대상은 `RuntimeException`과 `Error`이며 checked exception까지 롤백해야 하는 업무는 `rollbackFor`를 명시한다.

## 7. 오류와 API 응답

- 도메인별 오류 코드는 `ErrorCode`를 구현한 enum으로 관리한다. 예상 가능한 업무 규칙 위반은 `BusinessException`으로 전달하고, `GlobalExceptionHandler`가 HTTP 응답으로 변환한다. `CommonErrorCode`에 도메인 전용 오류를 모으지 않는다.
- 도메인 오류 코드 enum은 `core`의 `<도메인>/exception` 패키지에 둔다(예: `core/ticket/exception/TicketErrorCode`). 공통 계약인 `ErrorCode`·`BusinessException`은 `core/common/exception`에 둔다.
- 사용자 오류는 `core/user/exception`의 `UserErrorCode`와 `BusinessException`을 상속한 `UserException`으로 묶는다. Service에서 `UserException`을 던지며 공통 예외 처리기를 그대로 사용한다.
- 성공 JSON 응답은 `ResponseEnvelope.success(data)`를 사용한다. 데이터가 없는 JSON 응답은 `success(null)`을 사용하고, 본문이 없는 HTTP 204와 구분한다. 상태 코드와 공개 메시지는 오류 의미에 맞춘다.
- 예외 원문, SQL, 비밀값, 개인정보를 공개 메시지나 로그에 넣지 않는다. API 필드·상태·오류 코드를 바꾸면 프론트엔드 계약과 호환성을 확인한다.

## 8. 시간, 주석과 테스트

- 현재 순간은 `TimeProvider` 또는 주입된 `Clock`으로 구한다. 시각 저장·비교는 UTC `Instant`, 업무 기준일·기준월은 [공용 시간 정책](https://github.com/GETDDO/getddo-spec/blob/main/00-requirements/functional-requirements.md#공통-시간-기준과-화면-표시)에 따라 KST로 계산한다.
- Javadoc은 해당 기능이 무엇을 하는지와 클래스·메서드가 맡은 책임, 주요 처리 이유를 한국어로 설명한다. 특히 트랜잭션 경계, 상태 전환, 멱등 처리처럼 코드만으로 의도가 드러나지 않는 부분을 보충한다. 코드를 그대로 읽어주는 주석은 피한다.
- 테스트에는 한국어 시나리오를 `@DisplayName`으로 표현하고, 준비·실행·검증이 있는 테스트는 `given / when / then`으로 구분한다. 현재 테스트에 이 형식이 일괄 적용되어 있지는 않으므로 새 테스트부터 적용한다. 변경 동작의 실패·경계 조건을 검증하고 시간 테스트에는 고정 시계를 사용한다.
- 변경 범위의 테스트를 먼저 실행하고 완료 전 `./gradlew test`, `./gradlew build` 결과를 확인한다. 실행하지 못한 검증은 이유를 기록한다.

## 9. 이름과 형식

- 클래스·인터페이스·enum은 `UpperCamelCase`, 메서드·필드는 `lowerCamelCase`, 상수는 `UPPER_SNAKE_CASE`로 쓴다. 도메인 용어는 [공용 용어집](https://github.com/GETDDO/getddo-spec/blob/main/02-domain/glossary.md)을 따른다.
- enum은 클래스 안에 중첩하지 않고 해당 기능의 패키지에 별도 파일로 둔다. 이름은 `DrawSelectionResultType`처럼 의미와 소속이 드러나게 짓는다. 오류 코드 enum은 기존 규칙대로 `<도메인>/exception` 패키지에 둔다.
- 모델에 Lombok `@ToString`을 붙이거나 `toString()`을 직접 재정의하지 않는다. `UUID.toString()`처럼 문자열 변환을 위해 기존 메서드를 호출하는 경우와 `record`가 자동 생성하는 메서드는 이 규칙의 대상이 아니다.
- 현재 코드에는 탭 들여쓰기가 사용되지만 `.editorconfig`나 자동 포매터 설정은 없다. 들여쓰기, 줄 길이, import 정렬은 팀이 도구와 함께 정한 뒤 적용한다. 포맷 차이만으로 기존 코드의 변경을 요구하지 않는다.
