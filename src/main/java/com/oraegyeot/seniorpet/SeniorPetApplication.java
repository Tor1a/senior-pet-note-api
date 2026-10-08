package com.oraegyeot.seniorpet;

import java.util.TimeZone;
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
        // JVM 시간대는 UTC 로 고정한다. LocalTime(med_logs.scheduled_time)이 JVM 시간대와 무관하게 입력한 그대로 저장된다.
        // 서비스 기준 시간대는 별개로 app.zone(APP_ZONE)이 정한다.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SeniorPetApplication.class, args);
    }
}
