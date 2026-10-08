# 계정 관리 · 회원 탈퇴 API 계약서

- 작성: developer(백엔드) / 2026-10-08
- 근거: `.company/plans/계정-관리-탈퇴.md` (열린 질문 Q1~Q8 은 기획서 권장 기본값으로 확정: 즉시 삭제, `token_version` 채택, 비밀번호+확인 체크, 로그인 시도 제한은 범위 밖)
- 구현: `account/` 패키지, `V3__user_token_version.sql`. 기존 인증 계약(`signup`/`login`/`GET /api/me`)은 바뀌지 않았다.
- 공통: 둘 다 로그인 필요(`Authorization: Bearer <accessToken>`). 오류 형식은 `{"code","message"}`(message 는 한국어).

## 1. 비밀번호 변경 `PUT /api/me/password`

요청
```json
{ "currentPassword": "password123", "newPassword": "brandNewPass1" }
```
성공 200 (이 기기가 바꿔 쓸 새 토큰. 이전에 발급된 모든 토큰은 무효가 된다)
```json
{ "accessToken": "eyJ..." }
```

새 비밀번호 규칙: 가입과 같다(8자 이상) + **UTF-8 72바이트 이하**(BCrypt 한계). 영문·숫자는 72자, 한글은 24자까지. 현재 비밀번호와 같으면 거절.

| HTTP | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 필드 비어 있음, 새 비밀번호 8자 미만/72바이트 초과, 새 비밀번호가 현재와 같음 |
| 400 | `CURRENT_PASSWORD_MISMATCH` | 현재 비밀번호가 틀림 (**401 이 아니다**) |
| 401 | `UNAUTHORIZED` | 토큰 없음·무효·만료·탈퇴한 사용자·다른 기기에서 비밀번호를 바꾼 뒤의 옛 토큰 |
| 409 | `PASSWORD_CHANGE_CONFLICT` | 같은 계정의 비밀번호 변경이 동시에 겹쳐 이 요청이 졌다(데이터는 그대로). 잠시 뒤 다시 시도 안내. 강제 로그아웃 아님 |
| 429 | `TOO_MANY_ATTEMPTS` | 비밀번호 확인 실패 한도 초과(3절). `Retry-After` 헤더(초) |

## 2. 회원 탈퇴 `POST /api/me/withdraw`

요청 (`confirm` 은 "삭제되는 내용을 확인했어요" 체크박스 값. **true 여야 한다**. 기획서 초안에는 없던 필드, 6절 참고)
```json
{ "password": "password123", "confirm": true }
```
성공 204 본문 없음. 계정과 모든 데이터가 **즉시 영구 삭제**된다(되돌릴 수 없음).

| HTTP | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `password` 비어 있음, `confirm` 없음/false |
| 400 | `CURRENT_PASSWORD_MISMATCH` | 비밀번호가 틀림. 데이터는 그대로 |
| 401 | `UNAUTHORIZED` | 토큰 없음·무효·이미 탈퇴·옛 토큰 |
| 429 | `TOO_MANY_ATTEMPTS` | 3절 |
| 500 | `INTERNAL_ERROR` | 삭제 도중 서버 오류. 전부 롤백되어 데이터 그대로 → "다시 시도" 안내 |

삭제 범위(한 DB 트랜잭션, `users` 행 삭제 + `on delete cascade`): pets, medications, med_logs, daily_logs, events, medication_reminders, device_tokens, reminder_dispatches. 커밋 뒤 사진 폴더(`<PHOTO_DIR>/<userId>/`)도 삭제한다. 사진 삭제가 실패해도 탈퇴는 성공(204)하고 서버 로그에 warn 만 남는다.

## 3. 속도 제한 (두 API 공통)

- 사용자(토큰의 id)별 인메모리 카운터. **15분 안에 비밀번호 확인 실패 5회** → 가장 오래된 실패로부터 15분이 지날 때까지 429 `TOO_MANY_ATTEMPTS` + `Retry-After: <남은 초>`(올림). 잠긴 동안은 맞는 비밀번호도 429.
- 비밀번호 비교 **전에** 시도 1회를 먼저 세고(동시 요청으로 한도를 넘길 수 없다), 비밀번호가 맞으면 초기화한다. 두 API 가 같은 카운터를 쓴다. 검증 오류(400 `VALIDATION_ERROR`)와 429 응답은 실패로 세지 않는다.
- CORS: 브라우저 JS 가 `Retry-After` 를 읽을 수 있도록 서버가 `Access-Control-Expose-Headers: Retry-After` 를 내린다(실요청 응답에 실림).
- 설정: `app.account.max-failures`(5), `app.account.failure-window`(15m).
- 한계: 서버 재시작·다중 인스턴스에서는 카운터가 초기화/분리된다(MVP 단일 인스턴스 전제). 로그인 API 에는 제한이 없다(범위 밖).

- 가입(`/api/auth/signup`)도 이제 UTF-8 72바이트 초과(한글 25자 이상) 비밀번호를 400 `VALIDATION_ERROR` 로 거절한다(이전엔 500). 로그인에 72바이트 초과를 보내면 401(불일치).

## 4. 토큰 무효화 규칙

- `users.token_version`(기본 0)이 JWT 의 `ver` 클레임과 다르면 401. `ver` 가 없는 기존 토큰은 0 으로 본다 → 이번 배포로 기존 사용자가 다시 로그인할 필요는 없다.
- 비밀번호 변경 시 버전이 +1 → 다른 기기·유출된 토큰은 다음 요청에서 401. **변경을 요청한 기기는 응답의 새 토큰으로 교체해야 한다**(이전 토큰도 즉시 401).
- 로그인·가입 응답 형식은 그대로(`ver` 는 토큰 내부 값).
- 탈퇴한 사용자의 토큰은 이후 모두 401. 같은 이메일로 재가입하면 새 사용자 id 의 빈 계정이다(옛 토큰은 계속 무효).

## 5. 클라이언트 주의

1. **400 과 401 을 구분한다.** 현재(탈퇴) 비밀번호가 틀린 것은 400 `CURRENT_PASSWORD_MISMATCH` 이므로 **강제 로그아웃하면 안 된다**. 폼에 "비밀번호가 맞지 않아요" 를 보여 준다. 401 만 세션 만료로 처리한다.
2. 비밀번호 변경 성공 시 응답의 `accessToken` 으로 저장된 토큰을 **바로 교체**한다. 안 하면 다음 요청부터 401 로 로그아웃된다.
3. 탈퇴 204 후: 저장된 토큰·로컬 데이터(캐시, 알림 기기 토큰 등록 상태)를 지우고 로그인 화면으로 이동한다. 이후 어떤 API 도 401 이다(탈퇴 직전 푸시 1건이 기기에 도착할 수 있다 — 최대 1분 주기 1회분).
4. 429 는 `Retry-After`(초) 만큼 폼을 잠그고 안내한다. 강제 로그아웃하지 않는다.
5. 새 비밀번호 검증은 글자 수가 아니라 **UTF-8 바이트(72)** 기준이다. 한글은 24자까지.
6. 다른 기기에서 비밀번호를 바꾸면 이 기기는 다음 요청에서 401 → 기존 흐름대로 로그인 화면.

## 6. 변경 이력 · 기획서와 다른 점

- 2026-10-08 신규. 기획서 초안의 탈퇴 요청은 `{"password"}` 뿐이고 확인 체크는 클라이언트 전용이었으나, 구현 요청에 따라 서버도 `confirm: true` 를 요구한다(체크박스 값을 그대로 보내면 된다).
