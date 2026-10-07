package com.oraegyeot.seniorpet.reminder;

/** 알림 반복 방식. API·DB 값은 소문자 코드(daily | weekly | interval). */
public enum RepeatType {
    /** 매일 */
    DAILY("daily"),
    /** 지정한 요일만 */
    WEEKLY("weekly"),
    /** 시작일부터 N일 간격 */
    INTERVAL("interval");

    /** DTO 검증용 정규식 */
    public static final String REGEX = "daily|weekly|interval";

    private final String code;

    RepeatType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static RepeatType fromCode(String code) {
        for (RepeatType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("알 수 없는 반복 방식: " + code);
    }
}
