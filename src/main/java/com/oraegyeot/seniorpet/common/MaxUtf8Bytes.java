package com.oraegyeot.seniorpet.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/** 문자열의 UTF-8 바이트 수 상한(BCrypt 비밀번호는 72바이트). null 은 통과(@NotBlank 가 따로 잡는다). */
@Documented
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "입력이 너무 깁니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {
        private int max;

        @Override
        public void initialize(MaxUtf8Bytes a) {
            this.max = a.value();
        }

        @Override
        public boolean isValid(CharSequence s, ConstraintValidatorContext ctx) {
            return s == null || s.toString().getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
