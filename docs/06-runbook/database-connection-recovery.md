# DB 연결 장애 응답과 연결 복구

- 상태: 검토 대기 — 구현 검증과 실제 운영 적용 결과는 PR 및 인계 결과를 확인한다.
- 공용 API 계약 원본: [공통 API](https://github.com/GETDDO/getddo-spec/blob/main/05-api/common.md).

## 판별 범위와 확인 순서

MVC의 Controller·사용자 문맥 조회·Service 트랜잭션 시작/실행/커밋에서 전파된 예외의 원인과 JDBC `nextException`을 확인한다. SQLState `08` 계열 또는 Spring JDBC/Hibernate의 연결 전용 예외 타입이 근거다. 풀 고갈도 연결 획득 실패지만 RDS 전환을 뜻하지 않는다. 일반 트랜잭션 생성 실패·SQLTransientConnectionException 자체·쿼리 timeout·문법·무결성·deadlock은 이 근거 없이 503으로 바꾸지 않는다. 기존 업무 오류와 429의 Retry-After는 유지한다.

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
| Connector/J socketTimeout | 기본 미설정(드라이버 기본 0) / JDBC URL 명시적 설정 5000ms | 장기 업무 검증 전 기본 배포에서 변경하지 않음 |
| JVM 양의 DNS TTL | 30초 | JVM 전체 DNS 조회 |
| JVM 음의 DNS TTL | 5초 | 조회 실패 캐시 |

`monitoring`의 기본 배포는 socketTimeout을 지정하지 않는다. 장기 SQL·집계·추첨·워커의 statement/transaction 예산과 정상 처리를 검증한 뒤에만 기존 Parameter Store의 `SPRING_DATASOURCE_URL`에 `socketTimeout=5000`을 명시한다. 중복 속성이 없도록 URL을 서버 내부에서 파싱해 확인하고, TLS 등 나머지 속성은 유지한다. [설정 반영 절차](parameter-store.md)를 통해 순차 배포하고 실제 드라이버 값을 확인한다. 새로운 Parameter Store 항목·프로파일이나 인프라 파일 변경은 필요하지 않다. 검증 전에는 URL의 socket 제한을 변경하지 않는다. rollback 시에는 이전 Parameter Store 버전을 정상 절차로 반영하고 이미지/직전 app.env도 확인한다.

수치는 전체 HTTP 요청 시간 보장이 아니다. socketTimeout은 통신 읽기 제한이며 쿼리 취소·롤백 완료나 전체 업무 실행시간을 보장하지 않는다. 장기 집계·추첨·워커의 각 SQL이 5초를 넘으면 정상 업무도 연결 실패로 종료될 수 있다. 대표 정상 API·장기 업무 검증 결과와 별도 statement/transaction 예산을 확인한 뒤 운영 반영한다. 운영 성능이 확인되지 않았다면 시작값을 최종 운영값으로 표시하지 않는다.

JDBC URL의 같은 속성은 Hikari data-source-properties보다 드라이버에서 우선할 수 있다. 배포 전 `/etc/getddo/app.env`의 URL을 서버 내부에서 파싱해 timeout·autoReconnect 관련 속성과 DNS 사용 여부만 확인한다. env나 URL 전체를 출력하지 않는다. Spring 환경변수의 Hikari override도 확인한다. 비밀 Parameter Store 값은 기존 [설정 반영 절차](parameter-store.md)를 따른다.

[Dockerfile](../../Dockerfile)은 [보안 속성 파일](../../config/rds-ha.security)을 `/app/rds-ha.security`로 복사하고 `-Djava.security.properties=/app/rds-ha.security`로 기본 보안 설정에 추가한다. `-Dnetworkaddress.cache.ttl`만으로 적용을 주장하지 않는다. 이미지 안에서 같은 옵션의 `java -XshowSettings:security:properties -version` 결과 중 두 DNS 속성만 확인하고, 실행 프로세스의 JVM 옵션도 대조한다. URL의 IP 고정·autoReconnect·자동 쿼리 재실행은 추가하지 않는다. 끊어진 연결의 폐기와 새 연결 획득은 기존 Hikari/드라이버 동작을 사용한다.

## health·정상 배포·복구

2026-10-24 기준 운영 ALB health 경로는 `/v3/api-docs`다. 이 경로는 DB 가용성 검사가 아니다. Compose의 `restart: unless-stopped`는 HTTP health 실패만으로 앱을 재시작하지 않는다. 별도 liveness 자동화를 확인 없이 있다고 가정하지 않는다. monitoring 포트의 종합 health는 DB 장애 때 실패할 수 있으며 ALB 경로와 구분한다.

기존 [순차 CD](container-cd.md)로 같은 이미지의 A→C 교체, 각 컨테이너의 revision SHA·이미지 ID·실행 상태, ALB target healthy, 정상 읽기 API를 확인한다. `/v3/api-docs` 200만으로 DB 업무 성공을 주장하지 않는다. 쓰기 확인은 기존 검증용 사용자·대상이 있을 때만 수행한다. 운영 장애·재부팅·강제 전환·연결 차단·부하 시험은 수행하지 않는다.

실패 시 기존 CD의 이전 이미지·Compose·직전 app.env 복구를 사용한다. `/opt/getddo/current`와 배포 기록의 이전 이미지 ID를 확인하고 기존 런북대로 한 대씩 복구한다. 이 변경은 스키마를 바꾸지 않는다. 복구 뒤 DNS 파일·timeout 설정도 이전 이미지·설정과 일치하는지 확인한다.

## 검증 증거의 구분

- MockMvc 단위: 연결 예외와 wrapper, 기존 4xx/429/500, 사용자 문맥 조회, 원인 순환 방어.
- 설정 단위: 실제 배포 프로파일의 Hikari 바인딩·MySQL 드라이버 속성 해석 및 새 Java 21 프로세스의 보안 속성.
- 테스트 MySQL: 테스트 전용으로 socketTimeout 5000ms를 명시하고 SELECT SLEEP(6)의 통신 timeout과 8초 목표, JDBC 물리 connection_id 교체, 실제 Spring JPA Service·Repository 트랜잭션의 풀 고갈·socket 장애 후 정상 조회를 검증한다. 이 테스트의 SLEEP은 테스트 컨테이너에서만 실행한다.
- 응모·미션 MySQL: beforeCommit 연결 예외 주입의 롤백과 afterCommit 예외 주입의 응답 유실을 구분하고, 동일 키 재전송·다른 본문 충돌·단일 차감/지급을 검증한다. 실제 소켓을 끊는 시험이나 실제 commit 응답 유실 재현과는 구분한다.
- 실제 운영 RDS 전환 시간·장애 중 503·프론트 키 유지·대표 장기 업무 성능은 별도 증거가 필요하다. 실행하지 않은 항목은 NOT_RUN으로 남긴다.

근거: [Connector/J 네트워크 속성](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-networking.html), [HikariCP 설정과 연결 제한](https://github.com/brettwooldridge/HikariCP), [Java 21 DNS 보안 속성](https://docs.oracle.com/en/java/javase/21/core/java-networking.html).
