package com.oraegyeot.seniorpet.push;

/** 토큰 1개의 발송 결과. */
public record PushResult(String token, Status status) {

    public enum Status {
        /** 발송 성공 */
        SUCCESS,
        /** 더 이상 쓸 수 없는 토큰(앱 삭제·토큰 갱신 등). device_tokens 에서 지운다 */
        INVALID_TOKEN,
        /** 일시 오류 등 그 밖의 실패. 토큰은 유지하고 재발송하지 않는다(MVP) */
        FAILED
    }

    public static PushResult success(String token) {
        return new PushResult(token, Status.SUCCESS);
    }
}
