package com.oraegyeot.seniorpet.dailylog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 루틴 횟수 입력(walkCount·poopCount). 요청 JSON 에서 "생략"과 "명시적 null"을 구분하려는 타입이다.
 * 요청 DTO 필드가 null(자바) = 키를 보내지 않음(기존 값 유지),
 * {@link #CLEAR} = 명시적 null(지움), 그 밖에는 보낸 숫자. 범위·정수 검증은 DailyLogService 가 한다.
 * (Optional 은 Jackson 이 생략도 Optional.empty 로 채워 구분되지 않아 쓰지 않는다.)
 */
@JsonDeserialize(using = RoutineCountInput.Deserializer.class)
public record RoutineCountInput(BigDecimal value) {

    /** 명시적 null */
    public static final RoutineCountInput CLEAR = new RoutineCountInput(null);

    static class Deserializer extends JsonDeserializer<RoutineCountInput> {
        @Override
        public RoutineCountInput deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                return new RoutineCountInput(p.getDecimalValue());
            }
            return (RoutineCountInput) ctxt.handleUnexpectedToken(RoutineCountInput.class, p); // 문자열 등은 400
        }

        /** JSON 의 명시적 null */
        @Override
        public RoutineCountInput getNullValue(DeserializationContext ctxt) {
            return CLEAR;
        }

        /** 키를 보내지 않음. 기본 구현은 getNullValue 를 쓰므로 반드시 null 로 덮어써야 "생략"과 구분된다. */
        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }
}
