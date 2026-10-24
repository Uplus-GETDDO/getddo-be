# DB 연결 장애 응답과 연결 복구

- 상태: 검토 대기 — 구현 검증과 실제 운영 적용 결과는 PR 및 인계 결과를 확인한다.
- 공용 API 계약 원본: [공통 API](https://github.com/GETDDO/getddo-spec/blob/main/05-api/common.md).

## 판별 범위와 확인 순서

MVC의 Controller·사용자 문맥 조회·Service 트랜잭션 시작/실행/커밋에서 전파된 예외의 원인과 JDBC `nextException`을 확인한다. SQLState `08` 계열 또는 Spring JDBC/Hibernate의 연결 전용 예외 타입이 근거다. 풀 고갈도 연결 획득 실패지만 RDS 전환을 뜻하지 않는다. 일반 트랜잭션 생성 실패·SQLTransientConnectionException 자체·쿼리 timeout·문법·무결성·deadlock은 이 근거 없이 503으로 바꾸지 않는다. 기존 업무 오류와 429의 Retry-After는 유지한다.

Hikari가 SQLState 08 연결 오류를 확인한 시점에 현재 MVC 요청 속성에 SQLState·vendor code·예외 타입의 분류 결과만 보관한다. JPA 롤백이 원래 오류를 SQLState 없는 정리 오류로 덮어쓰면 원인 사슬에 Hibernate/Spring 트랜잭션 실패가 확인된 경우에만 이 근거를 사용한다. 그 뒤 발생한 무관한 SQL 오류는 이 근거로 503으로 바꾸지 않는다. 원문 예외는 보관하지 않고 Hikari의 기본 폐기 판단도 유지한다. 요청 종료 뒤 다른 요청에 근거를 전파하지 않으며, 워커에는 HTTP 문맥을 만들지 않는다.

503은 DB 미저장 확인이 아니다. 커밋이 완료되고 응답만 유실될 수 있다. 응모·미션 클라이언트는 같은 사용자·대상·요청 키·본문으로 기존 결과를 조회해야 한다. 서버는 새 키 생성이나 연결 장애에 대한 쓰기 자동 재시도를 하지 않는다. 프론트의 키 유지·안내 표시 검증은 이 변경 범위 밖이다.

1. `Database request unavailable` 로그의 `category`, `sqlState`, `vendorCode`, `causeType`을 확인한다. SQLState가 없는 연결 전용 wrapper는 `CONNECTION_RESOURCE_UNAVAILABLE`로 구분한다. 로그에 SQL·호스트·예외 원문은 추가하지 않는다.
2. CloudWatch 로그 stream의 컨테이너 ID로 A/C를 구분하고 기존 `http.server.requests`의 서버 태그·시간 및 Hikari pending/timeout/active 지표를 대조한다. 풀 고갈인지 통신 실패인지 확인하며 RDS 상태 API를 요청마다 호출하지 않는다.
3. 필터·스케줄러·비동기 워커는 MVC Advice의 적용 대상이 아니다. 워커의 기존 실패 기록·재실행 런북을 함께 확인한다. HTTP 503으로 워커 성공 여부를 판단하지 않는다.

## 배포 설정과 제한

[배포 Compose](../../compose.deploy.yaml)가 활성화하는 `monitoring` 프로파일의 [application-monitoring.yaml](../../api/src/main/resources/application-monitoring.yaml)에 연결 설정을 모은다. 기본 로컬 프로파일의 DB 설정은 변경하지 않는다.

| 설정 | 구현 시작값 | 범위 |
| --- | --- | --- |
| Hikari connectionTimeout | 2000ms | 연결 획득 대기 |
| Hikari validationTimeout | 1000ms | 기존 연결 유효성 확인 |
| Connector/J connectTimeout | 2000ms | 새 소켓 연결 |
| Connector/J socketTimeout | 60000ms | 통신 읽기 대기. 기존 50초 행 잠금 대기보다 여유를 두며 전체 SQL·HTTP 시간은 보장하지 않음 |
| JVM 양의 DNS TTL | 30초 | JVM 전체 DNS 조회 |
| JVM 음의 DNS TTL | 5초 | 조회 실패 캐시 |

`monitoring` 기본 배포에서 `socketTimeout=60000`을 적용한다. 2026-10-24 운영 앱 계정의 읽기 전용 조회 결과는 `innodb_lock_wait_timeout=50`초, `lock_wait_timeout=31536000`초, `max_execution_time=0`이었다. 행 잠금 대기보다 10초 여유를 두어 기존 잠금 실패 분류를 가능한 한 보존하고, 통신 무응답을 무기한 기다리던 드라이버 기본값 0을 제한한다. 메타데이터 잠금·실행 제한 없는 집계까지 기존의 모든 대기를 보존하는 값은 아니다.

5초 전역 적용은 채택하지 않는다. 추첨의 `DrawSnapshotSourceReader.findParticipants`는 참여자·응모·사용 이력을 전체 조회하고 공통 statement/transaction 제한이 없다. 알림은 100명 묶음과 60초 선점 임대, 어뷰징 이력은 30초 임대를 사용하지만 임대는 JDBC 실행 제한이 아니다. 테스트 DB에서 5초 제한을 넘는 6초 SQL이 60초 제한에서 정상 완료하고 같은 물리 연결을 유지하는지 검증한다. 전체 데이터량·장기 업무의 운영 지연 상한을 증명한 것은 아니므로, 단일 읽기가 60초를 넘는 정상 업무의 실패 가능성은 남는다. 별도 풀·전역 transaction 제한·도메인 재시도 변경은 추가하지 않는다.

수치는 전체 HTTP 요청 시간 보장이 아니다. 연결 획득은 2초 제한으로 503을 반환할 수 있고 TCP 단절도 드라이버가 확인하면 503으로 분류한다. 통신 무응답은 읽기 제한 60초까지 기다릴 수 있어 대표 짧은 API의 8초 목표와 클라이언트 10초 이전 반환을 충족하지 못하며 클라이언트 timeout이 먼저 발생할 수 있다. socketTimeout은 쿼리 취소·롤백 완료나 전체 업무 실행시간을 보장하지 않고 여러 읽기·정리 작업의 합도 60초를 넘을 수 있다. 503이나 클라이언트 timeout을 미저장 증거로 해석하지 않는다.

Hikari `data-source-properties.socketTimeout`을 더 짧게 변경하려면 해당 업무의 statement/transaction 예산과 정상 처리를 먼저 검증한다. URL 중복 속성도 서버 내부에서 확인하고 TLS 등 나머지 속성·DB 주소는 유지한다. 기존 [설정 반영 절차](parameter-store.md)를 사용하며 URL을 바꾼 경우 rollback 시 이전 Parameter Store 버전도 정상 절차로 반영한다. 이번 기본값 적용은 Parameter Store나 DB 주소 변경 없이 이미지 배포로 반영·복구한다.

현재 Connector/J 9.7.0의 일반 JDBC URL은 같은 이름의 Hikari data-source-properties가 우선한다. 실제 드라이버 해석 회귀로 URL의 5000ms보다 프로파일의 60000ms가 적용되는지 확인한다. URL 문자열만으로 유효 timeout을 판단하지 않는다. 배포 전 `/etc/getddo/app.env`의 URL을 서버 내부에서 파싱해 timeout·autoReconnect 관련 속성과 DNS 사용 여부만 확인한다. env나 URL 전체를 출력하지 않는다. Spring 환경변수의 Hikari override도 확인한다. 비밀 Parameter Store 값은 기존 [설정 반영 절차](parameter-store.md)를 따른다.

[Dockerfile](../../Dockerfile)은 [보안 속성 파일](../../config/rds-ha.security)을 `/app/rds-ha.security`로 복사하고 `-Djava.security.properties=/app/rds-ha.security`로 기본 보안 설정에 추가한다. `-Dnetworkaddress.cache.ttl`만으로 적용을 주장하지 않는다. 이미지 안에서 같은 옵션의 `java -XshowSettings:security:properties -version` 결과 중 두 DNS 속성만 확인하고, 실행 프로세스의 JVM 옵션도 대조한다. URL의 IP 고정·autoReconnect·자동 쿼리 재실행은 추가하지 않는다. 끊어진 연결의 폐기와 새 연결 획득은 기존 Hikari/드라이버 동작을 사용한다.

## health·정상 배포·복구

2026-10-24 기준 운영 ALB health 경로는 `/v3/api-docs`다. 이 경로는 DB 가용성 검사가 아니다. Compose의 `restart: unless-stopped`는 HTTP health 실패만으로 앱을 재시작하지 않는다. 별도 liveness 자동화를 확인 없이 있다고 가정하지 않는다. monitoring 포트의 종합 health는 DB 장애 때 실패할 수 있으며 ALB 경로와 구분한다.

기존 [순차 CD](container-cd.md)로 같은 이미지의 A→C 교체, 각 컨테이너의 revision SHA·이미지 ID·실행 상태, ALB target healthy, 정상 읽기 API를 확인한다. `/v3/api-docs` 200만으로 DB 업무 성공을 주장하지 않는다. 쓰기 확인은 기존 검증용 사용자·대상이 있을 때만 수행한다. 운영 장애·재부팅·강제 전환·연결 차단·부하 시험은 수행하지 않는다.

실패 시 기존 CD의 이전 이미지·Compose·직전 app.env 복구를 사용한다. `/opt/getddo/current`와 배포 기록의 이전 이미지 ID를 확인하고 기존 런북대로 한 대씩 복구한다. 이 변경은 스키마를 바꾸지 않는다. 복구 뒤 DNS 파일·timeout 설정도 이전 이미지·설정과 일치하는지 확인한다.

## 검증 증거의 구분

- MockMvc 단위: 연결 예외와 wrapper, 기존 4xx/429/500, 사용자 문맥 조회, 원인 순환 방어.
- 설정 단위: 실제 배포 프로파일의 Hikari 바인딩·MySQL 드라이버 속성 해석 및 새 Java 21 프로세스의 보안 속성.
- 테스트 MySQL: 운영 60000ms에서 6초 SQL의 정상 완료·같은 물리 연결 유지를 확인한다. 장애 재현 테스트 전용으로 socketTimeout 5000ms를 명시하고 SELECT SLEEP(6)의 통신 timeout과 8초 목표, JDBC 물리 connection_id 교체, 실제 Spring JPA Service·Repository 트랜잭션의 풀 고갈·socket 장애 후 정상 조회를 검증한다. 이 테스트의 SLEEP은 테스트 컨테이너에서만 실행한다.
- 응모·미션 MySQL: beforeCommit 연결 예외 주입의 롤백과 afterCommit 예외 주입의 응답 유실을 구분하고, 동일 키 재전송·다른 본문 충돌·단일 차감/지급을 검증한다. 실제 소켓을 끊는 시험이나 실제 commit 응답 유실 재현과는 구분한다.
- 실제 운영 RDS 전환 시간·장애 중 503·프론트 키 유지·대표 장기 업무 성능은 별도 증거가 필요하다. 실행하지 않은 항목은 NOT_RUN으로 남긴다.

근거: [Connector/J 네트워크 속성](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-networking.html), [HikariCP 설정과 연결 제한](https://github.com/brettwooldridge/HikariCP), [Java 21 DNS 보안 속성](https://docs.oracle.com/en/java/javase/21/core/java-networking.html).

롤백 오류가 원래 단절 원인을 덮어쓰는 경로의 근거: [HikariCP 7.0.2 예외 콜백·기본 폐기](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java).
