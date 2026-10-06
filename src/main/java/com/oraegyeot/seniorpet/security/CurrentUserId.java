package com.oraegyeot.seniorpet.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 로그인 사용자 id(UUID)를 주입한다.
 * 예) public PetResponse get(@CurrentUserId UUID userId, @PathVariable UUID id)
 *
 * 규칙: 사용자 id 는 반드시 이 어노테이션으로만 얻는다.
 * 요청 본문·쿼리·경로로 받은 user_id 는 절대 신뢰하지 않는다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {
}
