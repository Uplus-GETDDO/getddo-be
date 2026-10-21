# CloudWatch 앱 지표

## 범위와 활성화

Spring Boot 4.1.1 Actuator와 Boot 관리 Micrometer 1.17.1을 사용한다. Boot의 기본 지원 registry에 CloudWatch가 없어 `CloudWatchMetricsConfig`에서 명시적으로 등록한다. AWS SDK는 기존 S3와 같은 BOM 2.56.0이다.

기본값은 비활성화다. 로컬/테스트에서는 AWS 클라이언트와 publisher를 생성하지 않는다. 배포 시 기존 app.env에 다음 비밀 아닌 값을 전달한다. [배포 예제](../../infra/app.env.example)는 기존 DB·Redis·이미지 설정에 추가하는 조각이며 기본 비활성화다. 예제의 빈 리전·서버 값을 활성화 전에 채우고 아래 IAM·전달 조건을 확인한다. 환경·namespace·간격의 중복 예제 할당은 생략하며 앱 기본값과 전송 계약은 유지한다.

```dotenv
GETDDO_METRICS_ENABLED=true
GETDDO_METRICS_ENVIRONMENT=dev
GETDDO_METRICS_NAMESPACE=GETDDO/API
GETDDO_METRICS_STEP=60s
GETDDO_METRICS_SERVER=a
AWS_REGION=ap-northeast-2
```

C에서는 `GETDDO_METRICS_SERVER=c`로 바꾼다. 활성화 시 서버 a/c, 명시적 리전, 환경 dev, namespace GETDDO/API, 간격 60s를 검사한다. 다른 값은 설정 오류다. 서버별 식별자는 공유 Parameter Store 단일 값으로 덮어쓰지 않는다. 기존 설정 조회 도구의 허용 목록·검증, 배포 env 생성, Compose 전달과 롤백 백업/복원에서 이 값을 함께 보존해야 한다. 이 런북의 코드만 추가했다고 배포 도구 연동이나 AWS 수신 완료로 판단하지 않는다.

EC2 인스턴스 역할과 SDK 기본 자격 증명 체인을 사용한다. 정적 키를 app.env에 넣지 않는다. 컨테이너의 IMDSv2 접근과 인스턴스 metadata hop limit을 확인한다. 앱 역할은 `cloudwatch:PutMetricData`와 `cloudwatch:namespace=GETDDO/API` 조건을 허용해야 한다. CWAgent 권한과 앱 전송 권한은 각각 확인한다.

## 전송 계약

Namespace는 `GETDDO/API`, 공통 차원은 `environment=dev`, `server=a|c`, 간격과 storage resolution은 60초다. 다음 표는 Micrometer 이름이 아닌 **CloudWatch로 실제 전송되는 이름**이다. 표의 차원은 공통 차원 외 추가되는 전체 차원이다.

| CloudWatch 이름 | 추가 차원 | 단위·의미 |
| --- | --- | --- |
| `hikaricp.connections.active.value` | `pool=getddo` | 사용 중 연결 수 |
| `hikaricp.connections.idle.value` | `pool=getddo` | 유휴 연결 수 |
| `hikaricp.connections.max.value` | `pool=getddo` | 풀 최대 연결 수 |
| `hikaricp.connections.pending.value` | `pool=getddo` | 연결 대기 스레드 수 |
| `hikaricp.connections.acquire.sum/count/avg/max` | `pool=getddo` | 각각 `.sum`, `.count`, `.avg`, `.max` 이름. count는 Count, 나머지는 Milliseconds |
| `hikaricp.connections.timeout.count` | `pool=getddo` | 직전 step 연결 획득 timeout 횟수, Count |
| `tomcat.threads.busy.value` | `name=http-nio-8080` | 사용 중 스레드 수 |
| `tomcat.threads.current.value` | `name=http-nio-8080` | 현재 스레드 수 |
| `tomcat.threads.config.max.value` | `name=http-nio-8080` | 최대 스레드 수 |
| `jvm.memory.used.value`, `jvm.memory.max.value` | `area=heap`, `id` | JVM heap 메모리 풀별 Bytes |
| `jvm.gc.pause.sum/count/avg/max` | `action`, `cause`, `gc` | 각각 `.sum`, `.count`, `.avg`, `.max` 이름. count는 Count, 나머지는 Milliseconds |
| `executor.active.value`, `executor.queued.value` | `name=taskScheduler` | 기존 공용 스케줄러의 활성 스레드·대기 작업 수 |
| `executor.completed.count` | `name=taskScheduler` | 직전 step 완료 작업 수, Count |

게이지의 별도 base unit이 CloudWatch 표준 단위에 없으면 None이다. 연결·Tomcat·executor 수치는 값으로 해석한다. Timer의 avg/max는 해당 step 관측이 있을 때만 전송된다. sum/count는 step 값이며 대시보드에서 count의 Sum을 읽거나 count/60으로 초당 값을 계산한다. 긴 기간에 각 step avg를 단순 평균해 전체 평균으로 해석하지 않는다. Hikari는 풀 초기화 뒤, GC는 실제 이벤트 뒤 지표가 생길 수 있다.

`id`는 JVM이 제공하는 메모리 풀 이름(예: G1 Eden Space), GC의 action/cause/gc는 JVM이 제공하는 제한된 분류다. JVM 종류에 따라 값이 달라지므로 대시보드에서 실제 전체 차원을 보존해 조회한다. Tomcat connector 이름은 기본 8080 NIO 설정 기준이며 포트/프로토콜 변경 시 달라진다. 실제 차원은 CloudWatch 수신 후 다시 확인한다.

CloudWatch registry만 명시적 meter 허용 목록을 적용하고 위 추가 차원 외 태그는 제거한다. HTTP URI·사용자 ID·요청 ID·SHA는 전송하지 않는다. 미사용 applicationTaskExecutor를 수집하지 않는다. Bean으로 노출하지 않는 MissionEnd/TicketExpiry/EventLifecycle 전용 스케줄러는 이번 수집에서 제외하며 새 풀을 만들지 않는다.

## 접근과 실패 처리

Actuator HTTP/JMX exposure는 모두 제외하고 HTTP discovery도 비활성화한다. `/actuator`, `/actuator/metrics`, `/actuator/info`, `/actuator/health`를 공개하지 않는다. 기존 `/v3/api-docs` 배포 확인 경로는 그대로 사용하며 DB·Redis 연결 확인을 대체하지 않는다. Tomcat MBean registry는 앱 지표 활성화 시에만 활성화하며 원격 JMX 포트는 열지 않는다.

리전은 명시해 시작 시 기본 리전 체인의 IMDS 조회를 피한다. 기본 자격 증명 provider는 생성 시 조회하지 않으며 앱 준비 완료(ApplicationReadyEvent) 후 시작하는 첫 백그라운드 전송에서 조회한다. 시작 실패 시에는 종료 전송도 실행하지 않는다. 앱 요청 스레드는 AWS 전송을 기다리지 않는다. SDK는 총 1회 시도(추가 재시도 없음), attempt 2초·전체 3초, HTTP 연결/획득 500ms·읽기/쓰기 2초, registry 응답 대기 3초다. AWS/IAM/네트워크 실패는 publisher 로그로 남고 다음 주기에 다시 수집하며 업무 요청이나 앱 시작에 성공을 요구하지 않는다. 유효하지 않은 활성화 설정은 별도 시작 오류다.

전송에 실패한 배치는 보관하거나 다음 주기에 재전송하지 않는다. 다음 주기에는 새 구간의 값을 전송하므로 장애 중 누락된 60초 구간은 복구 후에도 자동 보정되지 않는다. 장애 복구 후 CloudWatch 시계열의 공백을 확인한다.

지표가 없으면 활성화 env·A/C 서버 값·실행 버전 → publisher 오류 → IAM namespace 조건 → 컨테이너 IMDSv2 → CloudWatch 네트워크 순으로 확인한다. 수집 실패를 스레드/연결 수 0으로 해석하지 않는다. 비활성화 후 재배포하면 전송이 중단되며 이미 저장된 CloudWatch 데이터가 삭제되지는 않는다.

## 검증 경계

`CloudWatchMetricsConfigTest`는 활성화/비활성화, 시작 시 자격 증명 조회 없음, 설정 경계, 허용 목록과 차원, 실제 registry의 PutMetricData 요청, Tomcat/JVM/executor binder와 실패·무응답 제한을 테스트 대역으로 검증한다. `MetricsEndpointExposureTest`는 실제 YAML과 MVC 매핑으로 Actuator 경로가 404임을 확인한다. 이는 AWS 수신·실제 운영 Hikari/Tomcat 수집·컨테이너 IMDS 검증 결과가 아니다.

배포 후 A/C에서 60초 주기 수신, 전체 차원, Tomcat MBean 지표 존재, 실제 DB 연결 뒤 Hikari 값, GC 이벤트 뒤 pause, 인스턴스별 오류 로그를 확인한다. 양쪽 역할·네트워크와 배포 env 전달은 실제 운영 인수 항목으로 남긴다.

근거: [Boot 지표 수집](https://docs.spring.io/spring-boot/reference/actuator/metrics.html), [Micrometer CloudWatch](https://docs.micrometer.io/micrometer/reference/implementations/cloudwatch.html), [1.17.1 CloudWatch 전송 구현](https://github.com/micrometer-metrics/micrometer/blob/v1.17.1/implementations/micrometer-registry-cloudwatch2/src/main/java/io/micrometer/cloudwatch2/CloudWatchMeterRegistry.java).
