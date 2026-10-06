package com.oraegyeot.seniorpet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 시니어펫 노트 백엔드 진입점.
 * UserDetailsServiceAutoConfiguration 제외: 기본 인메모리 사용자(임시 비밀번호)를 만들지 않는다. 인증은 JWT 만 쓴다.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SeniorPetApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeniorPetApplication.class, args);
    }
}
