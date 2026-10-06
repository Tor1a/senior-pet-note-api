package com.oraegyeot.seniorpet.common;

import org.springframework.http.HttpStatus;

/** 서비스 계층에서 던지는 예외. GlobalExceptionHandler 가 {code, message} 로 바꿔 응답한다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
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
}
