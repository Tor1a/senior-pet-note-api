package com.oraegyeot.seniorpet.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.push.fcm.* 설정.
 * enabled = true 면 실제 FCM 발송(환경변수 FCM_ENABLED, 기본 false).
 * credentialsBase64 = 서비스 계정 JSON 을 base64 로 인코딩한 값(환경변수 FCM_CREDENTIALS_BASE64 로만 받는다. 파일·저장소에 두지 않는다).
 */
@ConfigurationProperties("app.push.fcm")
public record FcmProperties(boolean enabled, String credentialsBase64) {

    /** 로그·toString 에 비밀값이 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "FcmProperties[enabled=" + enabled + ", credentialsBase64=" + (isBlank() ? "(없음)" : "(설정됨)") + "]";
    }

    private boolean isBlank() {
        return credentialsBase64 == null || credentialsBase64.isBlank();
    }
}
