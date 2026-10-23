# 검증 결과: Stage 생성·UI/CLI 소부하·결과 보관·정상 삭제

- 실행 일시·시간대: 2026-10-23, 아래 시간은 UTC(KST=UTC+9). UI 부하 02:37:01~02:37:32, CLI 부하 02:40:33~02:41:04. 설정한 run time은 각각 30초이며 시작·종료 처리 시각과 구분한다.
- 관련 Jira: [GD-308](https://ureca4.atlassian.net/browse/GD-308), [GD-311](https://ureca4.atlassian.net/browse/GD-311)
- 시나리오 기준: 합성 USER의 `GET /api/v1/events` 응답 envelope·페이지 형태 확인. [업무 범위](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/00-requirements/scope.md)를 변경하는 시험이 아니다.

## 목차

- [1. 목적과 검증 범위](#1-목적과-검증-범위)
- [2. 실행 환경과 재현 방법](#2-실행-환경과-재현-방법)
- [3. 시나리오별 결과](#3-시나리오별-결과)
- [4. 데이터 정합성 대조](#4-데이터-정합성-대조)
- [5. 부하·성능 결과](#5-부하성능-결과)
- [6. 실패·한계·후속 조치](#6-실패한계후속-조치)
- [7. 실행 원본과 산출물](#7-실행-원본과-산출물)

## 1. 목적과 검증 범위

실제 AWS에서 Stage 생성→앱·Locust 준비→HTTPS 로그인→웹 UI/CLI 부하→Grafana 대조→S3 보관·해시 재조회→정상 destroy→독립 잔여 조회를 확인했다. 단위·모의 테스트 결과와 구분한 실제 HTTP/부하·AWS 연동 인수다.

읽기 스모크의 플랫폼 연결 확인이며 전체 업무·쓰기 정합성·동시 응모·한계 성능·SLO 판정은 포함하지 않는다. 상세 운영 절차는 [인프라 main의 실행 문서](https://github.com/Uplus-GETDDO/getddo-infra/blob/main/docs/staging-loadtest.md)에 있다. 인프라는 비공개 저장소라 읽기 권한이 필요하고, 비로그인/권한 없는 계정에는 404로 보인다. 권한 있는 조직 계정으로 인프라 저장소의 `docs/staging-loadtest.md`에 접근한다. 이 보고서의 작은 CSV·요약 JSON은 현재 백엔드 저장소 권한으로 읽을 수 있다.

## 2. 실행 환경과 재현 방법

| 항목 | 실제 실행 조건 |
| --- | --- |
| 환경·도구 | 서울 AWS Stage, Locust 2.47.0, Python 3.12, boto3/botocore 1.40.59, 실제 Chromium UI·HTTPS |
| API | t3.small a/c 2대, 전용 internal HTTP ALB→8080, Stage 환경·로그·지표 분리 |
| DB·캐시 | 전용 private RDS MySQL 8.4.11 db.t4g.micro 20GiB·TLS, Redis 7.1 cache.t4g.micro 단일 노드·TLS/AUTH |
| 발생기 | t3.medium 1대, master 1개+worker 2개 |
| 데이터 | 실행별 합성 USER·excellent 멤버십, 이벤트 목록 읽기. 이벤트/응모/응모권 쓰기 데이터는 생성하지 않음 |
| 시험 조건 | UI와 CLI 순차 실행, 각 전체 사용자 2명·spawn 1명/초·run time 30초. 응답 재전송·멱등성·DB 경합·장애 주입은 해당 없음 |
| 실행 ID | `d2440eaefd6446e7a2074ea42a3cfd4c` |
| 고정 소스 | Infra `9127450bf6e5a676f1b51324b4231b107cde8ea0`, BE `e2ec9714864165750636a16c4bfce0a7bdf18438` |
| 이미지 ID | `sha256:e9c55cfe4b8cd6cfaf2eac9dee9624c713b3769a1d15aed814f4866b2b77399e` |
| 이미지 archive SHA256 | `1f3a3445b7367d0d919def9458bb469b01e80c08ecadb616bd93dd59956e2a94` |
| source bundle SHA256 | `a1a2eb3d5719755bd4f4af3c5e63f3a4d2700b9c36fc842cadb33a49abeab9be` |

실행 순서: 고정 bundle/config로 Infra `scripts/staging.py up --max-duration 120 --bundle SOURCE.tar.gz --config CONFIG.json`을 실행하고 ready를 확인했다.

1. 실제 브라우저에서 `/locust/` 로그인, Users=2/Spawn rate=1/Run time=30 입력 후 Start. 브라우저를 닫고 서버 타이머 종료·worker 통계 수신을 기다렸다.
2. UI 결과를 수집하고 Grafana를 대조한 뒤 `scripts/staging.py collect --run-id d2440eaefd6446e7a2074ea42a3cfd4c`로 자동 S3 보관·재조회를 수행했다.
3. 실제 `loadtest/control.py --origin https://dzc8oneey9mmx.cloudfront.net --username-file FILE --password-file FILE run --users 2 --spawn-rate 1 --duration 30`을 호출했다. FILE은 Parameter Store 값을 메모리에서 받아 만든 개인 tmpfs 0600 파일로 호출 후 정리했으며 원문을 인자에 넣지 않았다. 같은 master의 중복 시험을 만들지 않았다.
4. CLI 종료·통계 수신·Grafana 대조·자동 collect/readback 후 `scripts/staging.py destroy --run-id d2440eaefd6446e7a2074ea42a3cfd4c`를 수행했다. 정리 빌드 성공 뒤 별도 자격으로 서비스별 인벤토리·State·잠금·스케줄·로그인 메타데이터를 조회하고 S3 결과도 다시 읽었다.

재현 시 새 실행 ID와 검증된 bundle/config를 사용한다. 위 완료된 ID에 up을 재사용하지 않는다. 환경 생성·로그인 비밀은 런북 계약을 따르며 원문은 보고서에 보관하지 않는다. UI/CLI의 실제 시작·종료·수집 시각과 대조 시점은 [요약 JSON](assets/2026-10-23-staging-summary.json)의 `session_metadata`, `ui`, `cli`, `checked_at`을 따른다.

## 3. 시나리오별 결과

| ID | 시나리오·입력 조건 | 기대 결과 | 실제 결과·관측값 | 판정 | 근거 |
| --- | --- | --- | --- | --- | --- |
| S1 | 생성·a/c 배포·준비 | 앱 두 대·DB/Redis·worker 준비 | up 성공, a/c healthy, worker 2개 | 통과 | 요약 JSON `provenance`, session metadata |
| S2 | 실제 UI 2명/30초 | 요청·업무 응답, 브라우저 종료 후 타이머 동작 | 41건·실패 0, 업무 성공 41 | 통과 | UI CSV, JSON `ui`/`ui_grafana` |
| S3 | 실제 CLI 2명/30초 | 같은 master에서 다음 시험 | 39건·실패 0, 업무 성공 39 | 통과 | CLI CSV, JSON `cli`/`cli_grafana` |
| S4 | 각 시험 Grafana 대조 | CSV·master·API 집계 일치 | 각 시험 요청/실패/업무 성공 일치, API 합계 81=부하80+시드1. Stage6개·Prod6개 UP | 통과 | JSON 두 `*_grafana` |
| S5 | 각 collect와 최종 수집 | 색인·두 보고서 SHA256 readback | UI/CLI/최종 모두 일치. 부하/서버41개·AWS29개 쿼리 오류 없음 | 통과 | JSON `s3` |
| S6 | 정상 destroy·독립 조회·재실행 | 실제 자원 제거·보존 대상 유지 | 잔여0/State0, 잠금·예약 제거, 로그인3개 보존, 재실행 destroyed | 통과 | JSON `deletion`, 최종 acceptance 객체 |
| S7 | 삭제 후 접속·수집·이력 | Stage 제거, Prod/Grafana/결과 유지 | Locust health404, Stage 현재대상0·Prod6개UP·과거 지표·S3 결과 유지 | 통과 | JSON `routing`, `s3` |
| S8 | 최대 시간 만료에 의한 전체 삭제 | TTL 발화부터 workload 삭제 | 별도 전체 E2E 미수행. Scheduler 전달 계약·모의 시험과 구분 | 미실행 | 정상 종료 인수 범위로 제한 |

HTTP 집계는 읽기 요청 80건이다. 신규 쓰기·기존 멱등 처리 반환은 해당 없으며, 예상 업무 거절·예상 외 오류·응답 유실/시간 초과는 최종 두 시험에서 0건이다. 시드 준비의 별도 1회 조회는 부하 요청 수에 넣지 않았다.

## 4. 데이터 정합성 대조

| 검사 항목 | 기준·대조 시점 | 대상 수 | 불일치 수 | 판정 | 근거 |
| --- | --- | --- | --- | --- | --- |
| UI CSV↔Grafana 요청/실패/업무 결과 | 시험 종료·worker 통계 수신 후 | 41 요청 | 0 | 통과 | UI CSV·JSON `ui_grafana` |
| CLI CSV↔Grafana 요청/실패/업무 결과 | 같은 조건의 두 번째 시험 종료 후 | 39 요청 | 0 | 통과 | CLI CSV·JSON `cli_grafana` |
| API a/c 누적↔두 시험+시드 조회 | CLI 종료 후, 신규 부하 없음 | 81 요청 | 0 | 통과 | JSON `cli_grafana` |
| S3 색인·Locust·Grafana 해시 | 정상 수집과 삭제 후 독립 재조회 | UI/CLI/최종 각 색인+보고서 2개 | 0 | 통과 | JSON `s3` |
| 업무 DB 불변식·잔액/이력·응모/추첨 | 읽기 플랫폼 시험, 쓰기 데이터 사후 SQL 대조 없음 | 해당 없음 | 판정하지 않음 | 미실행 | 본격 업무 정합성 시험 범위 밖 |

API를 통한 실제 DB 연결·합성 사용자 조회 성공을 직접 SQL로 업무 불변식을 검증한 것으로 확대하지 않는다. 응모권·응모·당첨·알림 정합성은 이 결과로 보장하지 않는다.

## 5. 부하·성능 결과

같은 합성 사용자로 이벤트 목록을 읽는 낮은 부하다. 표의 rate·quantile은 master CSV의 Aggregated 행이며 percentile 단위는 ms다. Grafana 지연 단위는 초이며 종료 후 오래된 현재 gauge가 NaN인 것은 보존한 CSV percentile과 구분한다.

| 지표 | UI | CLI | 단위·집계 |
| --- | ---: | ---: | --- |
| 전체 사용자 / run time | 2 / 30 | 2 / 30 | 명 / 설정 초 |
| 요청 / 실패 | 41 / 0 | 39 / 0 | 각 시험 누적 |
| Requests/s | 1.4052079957 | 1.3052280855 | Locust CSV 평균 requests/s |
| p50 / p95 / p99 | 27 / 33 / 160 | 24 / 32 / 36 | Locust native CSV quantile, ms |
| 평균 응답 시간 | 24.6710332195 | 20.2981846667 | ms |
| 업무 성공 | 41 | 39 | `locust_business_total{result="success"}` |

DB 잠금 대기·교착 유도, 장애 복구 소요 시간, 쓰기 후 불일치 수는 측정하지 않았다. 성능 합격 기준은 미정이며 이 수치로 최대 처리량이나 SLO 통과를 선언하지 않는다.

## 6. 실패·한계·후속 조치

| 항목 | 관측·미확인 내용 | 후속 |
| --- | --- | --- |
| 이전 실행 수집 실패 | 별도 실행 `8f198acb76494df1819d99b6ce38ea91`에서 고정 SDK StreamingBody context의 iter_chunks 오류로 색인 수집 실패. 원본 보고서는 저장됐고 복구 rollback 후 잔여0 확인 | 코드 수정 후 본 보고서의 새 실행에서 생성→부하→수집→정상 삭제 전체 재검증 |
| 브라우저/조회 재시도 | 최종 실행의 첫 UI 자동화 시도는 오류 상세가 보존되지 않아 원인 확정 불가. master 대기를 확인한 뒤 재시도 성공. Grafana 읽기 중 URLError는 재조회 성공 | 성공한 UI/CLI 시각·원본을 별도 보존했으며 첫 시도를 성공으로 집계하지 않음 |
| 전체 TTL E2E | 만료 시각의 전체 workload 삭제는 별도 미실행 | 필요 시 별도 실행·보고서. Scheduler 전달 계약과 실제 rollback 검증은 전체 TTL E2E가 아님 |
| 업무·규모 | 읽기 smoke 한 개, 작은 표본·버스터블 사양. 쓰기 정합성·동시성·한계 성능 미실행 | 본 시험 전 업무 시나리오·기대 DB 결과·SLO·중단 기준 결정 |
| 보관·접근 | S3 결과 보관 기간 미정. S3 읽기 권한과 비공개 Infra 저장소 읽기 권한은 별도. 다른 팀원의 실제 다운로드는 미확인 | 권한 있는 팀원이 아래 key로 조회·SHA256 대조. Prometheus14일/20GB와 S3 보관을 구분 |

## 7. 실행 원본과 산출물

| 자료 | 보관 위치 | 설명 |
| --- | --- | --- |
| UI CSV 원본 | [UI stats](assets/2026-10-23-staging-ui-stats.csv) | 실제 다운로드한 작은 CSV 원본, 수정 없이 복사 |
| CLI CSV 원본 | [CLI stats](assets/2026-10-23-staging-cli-stats.csv) | 실제 다운로드한 작은 CSV 원본, 수정 없이 복사 |
| 조건·대조·해시·삭제 요약 | [summary JSON](assets/2026-10-23-staging-summary.json) | 기존 실제 acceptance에서 선택한 메타데이터와 master metrics. 비밀값·실제 사용자 없음 |
| 대용량 원본·Grafana 시계열 | 아래 비공개 S3 results 경로 | UI/CLI/최종 색인별 Locust CSV/HTML 묶음과 Grafana 쿼리·시계열 json.gz |

```text
s3://getddo-deploy-942466928325-ap-northeast-2/staging/runs/d2440eaefd6446e7a2074ea42a3cfd4c/results/
acceptance-b1fde933e01f14f49424c19f5a3afec0d4b9d0330299964574f9ba9843705cbe.json
SHA256 b1fde933e01f14f49424c19f5a3afec0d4b9d0330299964574f9ba9843705cbe
```

색인 key/SHA256은 summary JSON의 `s3.ui.index`, `s3.cli.index`, `s3.final.index`에 있다. 각 색인의 locust·monitoring key와 해시를 따라 실제 객체를 읽는다. 소스/이미지·실행 설정 식별자도 함께 보존했다. 작성 시 업로드 후 및 자원 삭제 후 readback은 수행됐지만, 보존 기간 확정 전 영구 보관을 보장하거나 팀원 전체가 이미 읽었다고 표현하지 않는다.

[근거 파일 보관 기준](assets/README.md)을 따른다. 보고서 작성으로 AWS를 다시 생성하거나 새 부하 시험을 실행하지 않았다.
