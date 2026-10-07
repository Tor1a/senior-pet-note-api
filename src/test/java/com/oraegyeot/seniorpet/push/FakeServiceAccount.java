package com.oraegyeot.seniorpet.push;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 테스트용 "형식만 맞는" 서비스 계정 JSON(base64). 실행할 때마다 RSA 키를 새로 만든다.
 * 실제 Firebase 프로젝트와 무관하며 네트워크 호출에 쓰지 않는다(FirebaseApp 초기화·메시지 생성까지만 검증).
 */
final class FakeServiceAccount {

    static final String PROJECT_ID = "test-only-project";

    private FakeServiceAccount() {
    }

    static String base64() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pkcs8 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + pkcs8 + "\n-----END PRIVATE KEY-----\n";
            String json = """
                    {
                      "type": "service_account",
                      "project_id": "%s",
                      "private_key_id": "test-key-id",
                      "private_key": "%s",
                      "client_email": "test@%s.iam.gserviceaccount.com",
                      "client_id": "1234567890",
                      "token_uri": "https://oauth2.googleapis.com/token"
                    }
                    """.formatted(PROJECT_ID, pem.replace("\n", "\\n"), PROJECT_ID);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
