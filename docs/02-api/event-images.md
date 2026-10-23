# 이벤트·경품 이미지 업로드 (GD-227)

- 상태: 검토 대기 — 구현 설명, 담당자 확인 전

## 적용 범위와 공용 명세의 관계

이 문서는 GD-227 이벤트·경품 이미지 업로드의 **백엔드 기능 계약과 구현 설명**이다. 프로젝트 전체의 공용 이미지 정책이나 FE·BE 공용 API 명세의 확정·반영 완료를 뜻하지 않는다. GD-227은 DB 스키마를 변경하지 않으며, GD-321의 상세 이미지 컬럼 확장은 아래 별도 절에서 설명한다. 공용 spec은 변경하지 않는다.

BE가 파일을 받는 방식과 JPG·PNG, **5MB = 5,000,000바이트 이하** 제한은 사용자가 이번 이벤트·경품 업로드에 대해 확정한 기준이다. 이 기준을 배너·미션 등 다른 기능의 공용 이미지 정책으로 확대하지 않는다. S3·CloudFront는 기존 인프라 설정을 재사용하는 구현이며, 이 문서에서 저장소 선택을 새로운 공용 정책으로 확정하지 않는다.

공용 [공통 API 계약](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/common.md)과 [이벤트 API](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/05-api/event.md)에는 아직 이번 업로드 엔드포인트·오류 계약이 등록되지 않았다. 공용 [미결정 사항](https://github.com/Uplus-GETDDO/getddo-spec/blob/main/00-requirements/pending-decisions.md)의 이미지 제한 항목도 그대로다. 아래 내용은 현재 BE 동작을 설명하며, 다음 항목의 공용 명세 반영은 별도 확인 대상이다.

- 이벤트·경품 담당과 FE 연동 담당: 업로드 경로, `imageKey`·`imageUrl`의 의미, JPG·PNG/5MB 적용 범위와 오류 응답을 검토한다.
- `413 HTTP-413`은 HTTP multipart 파서의 크기 초과, `400 EVENT-018`은 서비스의 파일 검증 오류라는 이번 BE 구현의 구분이다. 프로젝트 전체 오류 정책을 변경하는 것은 아니다.
- 미사용 업로드 객체와 기존 이미지 객체의 보관·삭제 책임·시점은 인프라 및 관련 기능 담당 영역의 합의 대상이다. 이 문서만으로 정리 책임이 확정됐다고 보지 않는다. 자동 정리와 기존 이미지 삭제는 이번 구현 범위에서 계속 제외한다.

## 호출 순서

1. 관리자 문맥 헤더로 `POST /api/v1/admin/events/images?target=EVENT`를 호출한다. 경품은 `target=PRIZE`다.
2. 요청은 `multipart/form-data`이고 `file` 파트에 이미지 한 장을 보낸다. JPG(`.jpg`·`.jpeg`, `image/jpeg`) 또는 PNG(`.png`, `image/png`)만 허용한다. 확장자·MIME·ImageIO가 읽은 실제 형식과 이미지 크기 헤더를 대조한다. 전체 픽셀 디코딩·재인코딩은 하지 않는다.
3. 201 공통 응답의 `data.imageKey`를 기존 이벤트·경품 등록 또는 수정 요청의 `imageKey`에 넣는다. `data.imageUrl`은 미리보기에 사용한다. 이미지가 없는 기존 요청도 계속 허용한다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "성공했습니다.",
  "data": {
    "imageKey": "events/550e8400-e29b-41d4-a716-446655440000.png",
    "imageUrl": "https://cdn.example.com/images/events/550e8400-e29b-41d4-a716-446655440000.png"
  }
}
```

위 JSON의 공통 성공 필드 값은 실제 `ResponseEnvelope.success`를 따른다. `imageKey`는 상대 키이며 S3 객체 키는 `images/` + `imageKey`다. 원본 파일명은 저장 키에 사용하지 않는다. 사용자 목록·상세와 관리자 등록·수정·목록·상세의 이벤트 및 경품 `imageUrl`도 같은 기준으로 조합한다. 공개 주소가 미설정된 기존 조회는 `imageUrl=null`을 유지하고, 신규 업로드는 거절한다.

| 조건 | 응답 |
| --- | --- |
| 빈 파일·지원하지 않는 형식·형식 위장·잘못된 헤더 | 400 `EVENT-018` |
| 컨테이너 multipart 파서의 파일/요청 크기 초과 | 413 `HTTP-413` |
| 서비스 직접 호출에서 파일 크기 초과 | 400 `EVENT-018` |
| 관리자 아님·비활성 사용자 | 403 `EVENT-006` |
| 사용자 문맥 누락·불일치 | 기존 공통 사용자 문맥 오류 |
| S3·공개 주소 미설정 또는 S3 저장 실패 | 503 `EVENT-019` |

기존 `@CurrentUser`는 시연용 사용자 문맥이며 로그인 인증을 새로 제공하는 API가 아니다. 이미지 저장에는 이벤트 ID가 필요하지 않으며, 등록 전 업로드 후 등록을 포기한 객체의 자동 정리나 기존 이미지 객체 삭제는 이번 변경에 포함하지 않는다.

## 이벤트 상세 설명 이미지 — GD-321

이 절은 GD-321의 BE 확장 계약이다. 공용 spec에 반영되거나 FE에 연결된 상태를 의미하지 않는다.

- 기존 `imageKey`는 목록용 썸네일이며, 상세 안내 이미지 1장은 별도의 선택 `detailImageKey`로 받는다. 텍스트 `description`과 경품 이미지는 유지한다.
- 상세 이미지도 `POST /api/v1/admin/events/images?target=EVENT`로 먼저 업로드한다. 반환된 `imageKey`를 이벤트 등록·수정 본문의 `detailImageKey`에 넣는다. JPG/PNG·5,000,000바이트 이하와 기존 이미지 검사 기준을 그대로 사용하며 별도 해상도 제한은 추가하지 않는다.
- `events.detail_image_key`는 `VARCHAR(500) NULL DEFAULT NULL`이다. 새 [V031](../../storage/db/src/main/resources/db/migration/event/V031__add_event_detail_image_key.sql)으로 추가하며 기존 행은 null이다. 전체 URL은 저장하지 않는다.
- 사용자 상세에는 `detailImageUrl`만 추가한다. 사용자 목록은 기존 썸네일을 유지한다. 관리자 등록·수정·목록·상세 응답에는 `detailImageKey`와 `detailImageUrl`을 추가한다.
- 이미지가 없거나 공개 기본 주소가 미설정이면 URL은 null이다. 키는 최대 500자이고 요청 DTO에서 초과 입력은 400 `COMMON-002`, 서비스 직접 호출에서는 400 `EVENT-001`로 거절한다.
- 수정은 기존과 같이 전체 교체 PUT이다. `detailImageKey`가 생략되거나 null이면 저장된 상세 이미지 연결을 제거한다. 유지하려면 관리자 응답으로 받은 키를 재전송한다. DB 연결 제거가 S3 객체 삭제를 뜻하지 않는다.
- 상세 이미지만 바꿔도 실제 수정으로 처리하고 `EVENT_UPDATE`의 변경 전후 키를 기록한다. 동일 설정 PUT은 추가 저장·감사 기록을 만들지 않으며, 일정이 같으면 일정 변경 알림을 만들지 않는다.

```json
{
  "imageKey": "events/thumbnail.png",
  "detailImageKey": "events/detail.png"
}
```

위 JSON은 등록·수정 본문의 이미지 필드만 발췌한 것이다. 기존 제목·기간·경품 등 필수 필드는 그대로 전달한다. 업로드 객체 자동 정리와 이전 이미지 삭제는 이번 범위에 포함하지 않는다.

2026-10-23 KST, dev `41af1c8f` 기반 GD-321 로컬 변경에서 대상 검증 73개가 통과했다. 실제 MySQL에서 등록·사용자/관리자 조회·상세 이미지만 수정·동일 PUT·제거·미등록 null·길이 상한·감사 키와 새 컬럼 메타데이터를 확인했다. 이어 `test build --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel --console=plain`이 12분 58초에 성공했다. 보고서 기준 일반 1,663개·통합 1,110개, 총 2,773개이며 실패·오류·건너뜀은 0개다. 배포 DB·실제 AWS·FE 연동 검증 결과를 뜻하지 않는다.

## 설정·구조

- `GETDDO_IMAGES_BUCKET`: 비공개 이미지 버킷 이름.
- `GETDDO_IMAGES_BASE_URL`: `/images/`로 끝나는 공개 CloudFront 주소. 공개 URL에 S3 접근 자격 증명을 넣지 않는다.
- `AWS_REGION`: 실제 버킷 리전. SDK의 기본 자격 증명 체인으로 배포 환경의 IAM 권한을 사용한다. 코드에 AWS 키를 넣지 않는다.
- `GETDDO_IMAGES_ENDPOINT`: S3Mock에서만 명시적으로 지정하는 HTTP 엔드포인트. 실제 AWS에서는 비워 둔다. 지정 시 path-style을 사용한다.
- `spring.servlet.multipart.max-file-size=5000000B`, `max-request-size=5100000B`. 요청 전체 상한에는 multipart 헤더 공간을 포함한다.

`core/event/service/EventImageUploadService`는 권한·종류·용량·키 생성을 맡고 `EventImageStorage`에 저장을 위임한다. `storage:s3`가 AWS SDK v2 `S3Client`로 PUT한다. 네트워크 호출 동안 DB 트랜잭션을 잡지 않으며 DB에 업로드 이력을 새로 만들지 않는다. S3 설정이 없는 로컬 실행에서도 기존 기능을 기동할 수 있도록 클라이언트는 지연 생성한다.

## 로컬 S3Mock

```bash
docker run --rm --name getddo-image-test -p 9090:9090 \
  -e COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=getddo-images-local \
  adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92
```

로컬 BE 프로세스에 다음을 주입한다. 아래 키는 S3Mock 전용 더미값이며 실제 AWS에 사용하지 않는다.

```text
AWS_REGION=ap-northeast-2
AWS_ACCESS_KEY_ID=s3mock
AWS_SECRET_ACCESS_KEY=s3mock
GETDDO_IMAGES_ENDPOINT=http://localhost:9090
GETDDO_IMAGES_BUCKET=getddo-images-local
GETDDO_IMAGES_BASE_URL=http://localhost:9090/getddo-images-local/images/
```

기존 9090 포트의 S3Mock이 있으면 새 컨테이너 대신 기존 버킷·포트를 사용한다. BE가 Docker 안에서 실행되면 `localhost`는 BE 컨테이너 자신이므로 접근 가능한 S3Mock 호스트로 지정한다.

```bash
curl -X POST 'http://localhost:8080/api/v1/admin/events/images?target=EVENT' \
  -H 'X-User-ID: DB에-존재하는-관리자-UUID' -H 'X-User-Role: ADMIN' \
  -F 'file=@small.png;type=image/png'
```

자동 통합 테스트는 전용 S3Mock·MySQL 컨테이너와 실제 HTTP 서버를 띄운다. 작은 PNG/JPG 업로드, URL에서 원본 바이트·Content-Type 조회, 이벤트·경품 등록과 목록·상세 URL 연결, 정확한 5MB 경계 및 권한·형식 오류를 검증한다.

```bash
./gradlew :core:test --tests '*EventImageUploadServiceTest' \
  :api:test --tests '*EventImageUrlsTest' --tests '*EventResponseContractTest' \
  :api:integrationTest --tests '*EventImageUploadIntegrationTest' \
  --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel
```

초기 구현 `3c25fd9e` 기준으로 2026-10-17 KST에 위 대상 검증과 `:storage:s3:test`의 총 27개 테스트가 통과했다. 작은 PNG·JPG를 BE API로 전송해 201 응답을 받고, 반환 URL의 GET 200·Content-Type·원본 바이트 일치를 확인했다. 5,000,000바이트는 업로드되고 5,000,001바이트는 HTTP 413으로 거절됐다.

같은 날 18:38 KST에 `test build --offline --no-daemon --init-script gradle/test-limits.init.gradle --max-workers=1 --no-parallel`도 17분 8초로 성공했다. JUnit 보고서 합계 2,423개(일반 1,371 / 통합 1,052), 실패·오류·건너뜀은 0개다. 새로 추가한 테스트는 10개다.

리뷰 반영 후에는 테스트 데이터 정리·이미지 생성 확인·권한별 오류 코드와 null 입력 검증을 보강했다. 20:44 KST에 변경 대상 8개(서비스 5 / HTTP·MySQL·S3Mock 3)가 통과했고, 20:52 KST에 같은 전체 명령도 7분 18초로 성공했다. 보고서 합계는 2,424개(일반 1,372 / 통합 1,052), 실패·오류·건너뜀은 0개다. null 입력 테스트 메서드 1개를 추가했으며, 변경되지 않은 검증은 Gradle의 UP-TO-DATE 결과를 재사용했다.

S3Mock 성공은 실제 AWS IAM·버킷 정책·CloudFront 배포 검증을 대신하지 않는다. 배포 환경에서는 같은 API로 업로드한 실제 키를 CloudFront URL에서 확인해야 한다.

참고: [AWS S3 SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html), [기본 자격 증명 체인](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html), [Adobe S3Mock](https://github.com/adobe/S3Mock).
