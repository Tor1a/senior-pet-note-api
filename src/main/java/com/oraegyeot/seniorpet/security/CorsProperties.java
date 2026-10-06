package com.oraegyeot.seniorpet.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.cors.* 설정. 허용할 웹 주소 목록(환경변수 CORS_ALLOWED_ORIGINS, 쉼표 구분). */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
