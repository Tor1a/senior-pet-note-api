package com.oraegyeot.seniorpet.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** API 의 "HH:mm" 시각 문자열 ↔ LocalTime 변환. 초 단위는 쓰지 않는다. */
public final class TimeOfDay {

    /** 00:00 ~ 23:59 */
    public static final String REGEX = "^([01]\\d|2[0-3]):[0-5]\\d$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private TimeOfDay() {
    }

    /** "HH:mm" 형식이 아니면 400 VALIDATION_ERROR */
    public static LocalTime parse(String text) {
        if (text == null || !PATTERN.matcher(text).matches()) {
            throw ApiException.validation("시각은 HH:mm 형식이어야 합니다.");
        }
        return LocalTime.parse(text, FORMAT);
    }

    public static String format(LocalTime time) {
        return time == null ? null : time.format(FORMAT);
    }
}
