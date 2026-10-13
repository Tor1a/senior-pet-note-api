package com.oraegyeot.seniorpet.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * 문자열 값 입력 중 요청 JSON 의 "생략"과 "명시적 null"을 구분해야 하는 필드용 타입(날짜·열거값 등).
 * 요청 DTO 필드가 자바 null = 키를 보내지 않음(PUT 에서 기존 값 유지),
 * {@link #CLEAR} = 명시적 null(지움), 그 밖에는 보낸 문자열. 형식·범위 검증은 서비스가 한다.
 * dailylog/RoutineCountInput 과 같은 방식이다(Optional 은 Jackson 이 생략도 empty 로 채워 구분되지 않는다).
 */
@JsonDeserialize(using = PatchTextInput.Deserializer.class)
public record PatchTextInput(String value) {

    /** 명시적 null */
    public static final PatchTextInput CLEAR = new PatchTextInput(null);

    /** 요청 필드가 생략(null)이면 current 유지, CLEAR 면 null, 아니면 보낸 값. */
    public static String resolve(PatchTextInput input, String current) {
        return input == null ? current : input.value();
    }

    static class Deserializer extends JsonDeserializer<PatchTextInput> {
        @Override
        public PatchTextInput deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return new PatchTextInput(p.getText());
            }
            return (PatchTextInput) ctxt.handleUnexpectedToken(PatchTextInput.class, p); // 숫자 등은 400
        }

        /** JSON 의 명시적 null */
        @Override
        public PatchTextInput getNullValue(DeserializationContext ctxt) {
            return CLEAR;
        }

        /** 키를 보내지 않음. 기본 구현은 getNullValue 를 쓰므로 반드시 null 로 덮어써야 "생략"과 구분된다. */
        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }
}
