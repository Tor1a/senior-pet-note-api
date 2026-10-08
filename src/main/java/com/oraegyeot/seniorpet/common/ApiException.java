package com.oraegyeot.seniorpet.common;

import org.springframework.http.HttpStatus;

/** 서비스 계층에서 던지는 예외. GlobalExceptionHandler 가 {code, message} 로 바꿔 응답한다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private ApiException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 429 응답의 Retry-After(초). 없으면 null. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 없는 리소스 + 남의 리소스 모두 이 예외로 응답한다(존재 여부를 노출하지 않기 위함). */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 데이터를 찾을 수 없습니다.");
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    /** 400 VALIDATION_ERROR — 서비스 계층의 규칙 검증 실패(예: 증상 조합, 일정에 없는 시각). */
    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /** 400 INVALID_FILE — 사진 형식 오류 */
    public static ApiException invalidFile(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", message);
    }

    /** 413 FILE_TOO_LARGE — 사진 5MB 초과 */
    public static ApiException fileTooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "사진은 5MB 이하만 올릴 수 있습니다.");
    }

    /** 400 CURRENT_PASSWORD_MISMATCH — 401 이 아니다(클라이언트가 401 을 세션 만료로 보고 로그아웃함). */
    public static ApiException currentPasswordMismatch() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다.");
    }

    /** 409 PASSWORD_CHANGE_CONFLICT — 같은 계정의 비밀번호 변경이 동시에 겹쳤다(다시 시도). */
    public static ApiException passwordChangeConflict() {
        return new ApiException(HttpStatus.CONFLICT, "PASSWORD_CHANGE_CONFLICT",
                "다른 기기에서 계정 정보가 바뀌었어요. 잠시 후 다시 시도해 주세요.");
    }

    /** 429 TOO_MANY_ATTEMPTS — 비밀번호 확인 연속 실패 한도 초과. Retry-After 헤더(초)를 함께 내린다. */
    public static ApiException tooManyAttempts(long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "비밀번호를 여러 번 잘못 입력했어요. 잠시 후 다시 시도해 주세요.", retryAfterSeconds);
    }
}
