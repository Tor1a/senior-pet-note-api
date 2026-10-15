# API 계약서: 진료 때 물어볼 것 메모 (v1)

- 작성: developer(백엔드), 2026-10-15
- 근거: 기능 기획서 `진료-질문-메모.md`(`.company/plans/`) 3~4장. 달라진 점은 6절에 모았다.
- 대상: 클라이언트 개발자(`senior-pet-note-client`, 웹·모바일)
- 계약을 바꾸려면 비서실장에게 먼저 알린다. 기존 API 의 형식은 바뀌지 않았다.
- 공통 규칙은 `docs/api-todos.md` 와 같다.
  - JSON 필드는 camelCase, 시각은 ISO-8601 UTC
  - 오류 형식은 `{code, message}`. **새 오류 코드는 `VISIT_QUESTION_LIMIT_REACHED` 하나**
  - 모든 API에 `Authorization: Bearer` 필요
  - 남의 리소스·없는 리소스·UUID 형식이 아닌 id 는 404 NOT_FOUND

## 0. 핵심 원칙
1. **서버는 글을 해석·추천·분류하지 않는다.** `text` 는 저장하고 돌려줄 뿐이다. 앞뒤 공백 제거 외에 변형하지 않고, 키워드 검사·중복 판정·요약·정렬 기준으로 쓰지 않는다. 서버가 질문을 만들어 내려주지 않으며 기본 데이터·예시·시드가 없다.
2. 글은 보호자가 직접 입력한 것이며 건강 관련 내용이 들어갈 수 있다. **푸시·FCM `data`·이벤트·서버 로그에 싣지 않는다.** 알림 발송 작업도 없다.
3. **400·409 등 오류 응답과 예외 메시지에 보낸 글을 되돌려 주지 않는다**(고정 문구만).
4. 소유자는 항상 로그인 사용자다. 요청 본문의 `userId`·`petId` 는 무시한다.

## 1. 엔드포인트
| 메서드 | 경로 | 요청 | 성공 응답 |
|---|---|---|---|
| GET | /api/pets/{petId}/visit-questions?status=open\|done\|all | - | 200 `{questions:[VisitQuestion]}` |
| POST | /api/pets/{petId}/visit-questions | `{text}` | 201 VisitQuestion |
| PUT | /api/visit-questions/{id} | `{text}` (전체 교체, 완료 상태는 유지) | 200 VisitQuestion |
| PUT | /api/visit-questions/{id}/completion | `{"completed": true\|false}` | 200 VisitQuestion |
| DELETE | /api/visit-questions/{id} | - | 204 (실제 삭제) |

- 목록·등록은 반려동물 소유 검증이 먼저다(남의 반려동물은 404).
- 요청 본문의 알 수 없는 필드는 무시한다.

### GET 목록
- `status` 생략 = `open`. `open` / `done` / `all` 이외의 값(빈 값, 대소문자 다름 포함)은 400 `VALIDATION_ERROR`.
- 정렬: `open` = 등록 순(`createdAt` 오름차순, 오래된 것 위), `done` = `completedAt` 내림차순(최근 완료 먼저), `all` = open 먼저 + done 뒤. 사용자당 최대 30개라 페이징이 없다.

### 완료 `PUT .../completion`
- `completed` 필수(없으면 400). 멱등: 이미 완료에 `true` 를 다시 보내면 최초 `completedAt` 유지, 이미 미완료에 `false` 는 그대로. 취소 뒤 다시 완료하면 `completedAt` 이 새 시각이 된다.
- `PUT /api/visit-questions/{id}` 는 완료 상태를 바꾸지 않는다.

## 2. 요청 규칙
| 필드 | 필수 | 규칙 |
|---|---|---|
| text | 필수 | 앞뒤 공백 제거 후 1~100자(코드 포인트 기준, 이모지 1자). 저장값은 공백 제거본. 빈 문자열·공백만·누락·null 은 400. 내부 줄바꿈은 서버가 검사·변환하지 않는다 |

PUT 은 전체 교체이며 PATCH(생략 = 유지) 의미론은 쓰지 않는다.

## 3. 응답 (VisitQuestion)
```json
{
  "id": "uuid",
  "petId": "uuid",
  "text": "약을 식전에 줘도 되는지 물어보기",
  "completed": false,
  "completedAt": null,
  "createdAt": "2026-10-15T03:02:11.781275Z",
  "updatedAt": "2026-10-15T03:02:11.781275Z"
}
```
- 필드는 항상 존재한다(7개). `completed` 는 `completedAt != null` 과 같다. `updatedAt` 은 DB 에 저장된 값과 같다.
- 목록 응답은 `{"questions":[VisitQuestion, ...]}`.

## 4. 한도
- 사용자당 최대 30개(완료 포함). 31번째 등록은 409 `VISIT_QUESTION_LIMIT_REACHED`. 동시 등록은 `pg_advisory_xact_lock` 으로 직렬화해 30개를 넘지 않는다.
- 한도는 등록(POST)에만 적용한다. 수정·완료·취소·삭제는 한도 상태에서도 된다. 삭제하면 다시 등록할 수 있다.
- message 는 바뀔 수 있으므로 클라이언트는 `code` 로 분기한다.

## 5. 오류
| HTTP | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | text 누락·공백·101자 이상, `completed` 누락, `status` 값 오류, JSON 형식 오류 |
| 401 | `UNAUTHORIZED` | 토큰 없음·잘못됨·만료 |
| 404 | `NOT_FOUND` | 없는/남의 리소스 |
| 409 | `VISIT_QUESTION_LIMIT_REACHED` | 사용자당 30개에 이미 도달한 상태에서 등록 |

## 6. 삭제
- 회원 탈퇴(`users` 삭제) 시 `on delete cascade` 로 함께 영구 삭제된다(`docs/api-account.md` 삭제 범위). 반려동물 행 삭제 시에도 cascade. 질문 삭제 API 는 실제 삭제다.

## 7. 기획서와 달라진 점
- DB 컬럼은 기획서 권장대로 `completed_at`(기획서 초안의 `done_at` 아님). 응답 필드도 `completed`/`completedAt`.
- 요청·응답 DTO 의 `toString()` 은 글을 가린다(프레임워크 TRACE 로그가 본문을 찍어도 글이 남지 않게 하는 방어).
