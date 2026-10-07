package com.oraegyeot.seniorpet.push;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushNotification;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * FCM 발송기(app.push.fcm.enabled=true 일 때만 PushConfig 가 등록).
 *
 * - 서비스 계정 키는 환경변수 FCM_CREDENTIALS_BASE64(JSON 을 base64 인코딩)로만 받는다. 프로젝트 id 도 이 JSON 에서 읽는다.
 *   비었거나 해석할 수 없으면 생성자에서 실패 → 서버가 시작되지 않는다. 키 내용은 로그에 남기지 않는다.
 * - FirebaseApp 은 이름을 붙여 한 번만 초기화하고, 컨텍스트 종료 시 정리한다.
 * - sendEachForMulticast 로 토큰별 결과를 받는다. 오류 코드 → PushResult 매핑은 classify()(순수 함수).
 * - 일시 오류는 재시도하지 않는다(MVP, 최대 1회 발송).
 */
public class FcmPushSender implements PushSender, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    static final String APP_NAME = "senior-pet-note";

    /** 알림 유효시간. 기기가 꺼져 있다가 1시간 뒤에 켜지면 지난 투약 알림은 버린다. */
    static final Duration TTL = Duration.ofHours(1);

    /** 웹 알림 아이콘(클라이언트 PWA 아이콘) */
    static final String WEB_ICON = "/pwa-192x192.png";

    private final FirebaseApp app;
    private final FirebaseMessaging messaging;
    private final Clock clock;

    public FcmPushSender(FcmProperties props, Clock clock) {
        this.clock = clock;
        ServiceAccountCredentials credentials = parseCredentials(props.credentialsBase64());
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(credentials.getProjectId())
                .build();
        // 같은 JVM 에서 컨텍스트가 다시 뜨는 경우(테스트 등)를 대비해 기존 앱이 있으면 지우고 새로 만든다
        for (FirebaseApp existing : FirebaseApp.getApps()) {
            if (existing.getName().equals(APP_NAME)) {
                existing.delete();
            }
        }
        this.app = FirebaseApp.initializeApp(options, APP_NAME);
        this.messaging = FirebaseMessaging.getInstance(app);
        log.info("FCM 발송기 사용 (Firebase 프로젝트 id: {})", options.getProjectId());
    }

    /**
     * base64 → 서비스 계정 JSON → 자격증명. 실패하면 IllegalStateException(키 내용은 메시지에 넣지 않는다).
     * 서비스 계정 타입만 받는다(GoogleCredentials.fromStream 은 임의 타입을 받아 deprecated).
     */
    static ServiceAccountCredentials parseCredentials(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "FCM_ENABLED=true 이지만 환경변수 FCM_CREDENTIALS_BASE64 가 비어 있습니다(서비스 계정 JSON 을 base64 로 넣으세요).");
        }
        byte[] json;
        try {
            json = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("환경변수 FCM_CREDENTIALS_BASE64 가 올바른 base64 가 아닙니다.");
        }
        ServiceAccountCredentials credentials;
        try {
            credentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "환경변수 FCM_CREDENTIALS_BASE64 를 서비스 계정 JSON 으로 해석할 수 없습니다(" + e.getClass().getSimpleName() + ").");
        }
        if (credentials.getProjectId() == null || credentials.getProjectId().isBlank()) {
            throw new IllegalStateException("FCM 서비스 계정 JSON 에 project_id 가 없습니다.");
        }
        return credentials;
    }

    @Override
    public List<PushResult> send(List<String> tokens, PushMessage message) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        BatchResponse batch;
        try {
            batch = messaging.sendEachForMulticast(buildMessage(tokens, message));
        } catch (FirebaseMessagingException e) {
            // 인증 실패(서비스 계정 키 문제) 등 요청 전체가 실패한 경우
            log.error("FCM 발송 전체 실패: errorCode={}, messagingErrorCode={}",
                    e.getErrorCode(), e.getMessagingErrorCode());
            return tokens.stream().map(t -> new PushResult(t, PushResult.Status.FAILED)).toList();
        }
        List<SendResponse> responses = batch.getResponses();
        List<PushResult> results = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            SendResponse r = responses.get(i);
            if (r.isSuccessful()) {
                results.add(PushResult.success(tokens.get(i)));
            } else {
                MessagingErrorCode code = r.getException() == null ? null : r.getException().getMessagingErrorCode();
                PushResult.Status status = classify(code);
                if (code == MessagingErrorCode.INVALID_ARGUMENT) {
                    log.error("FCM INVALID_ARGUMENT(토큰은 삭제하지 않음, 메시지·토큰 형식 확인 필요): token={}",
                            LoggingPushSender.mask(tokens.get(i)));
                } else if (status == PushResult.Status.FAILED) {
                    log.warn("FCM 발송 실패(재시도 안 함): token={}, messagingErrorCode={}",
                            LoggingPushSender.mask(tokens.get(i)), code);
                }
                results.add(new PushResult(tokens.get(i), status));
            }
        }
        return results;
    }

    /**
     * FCM 오류 코드 → 토큰 결과.
     * 토큰이 확실히 무효인 경우(UNREGISTERED, 다른 프로젝트 토큰 SENDER_ID_MISMATCH)만 INVALID_TOKEN(토큰 삭제).
     * INVALID_ARGUMENT 는 토큰뿐 아니라 메시지 payload 문제일 수도 있어 삭제하지 않고 FAILED + ERROR 로그(전 사용자 토큰 삭제 방지).
     * 그 밖(UNAVAILABLE, INTERNAL, QUOTA_EXCEEDED, THIRD_PARTY_AUTH_ERROR, 코드 없음)은 FAILED(토큰 유지).
     */
    static PushResult.Status classify(MessagingErrorCode code) {
        if (code == null) {
            return PushResult.Status.FAILED;
        }
        return switch (code) {
            case UNREGISTERED, SENDER_ID_MISMATCH -> PushResult.Status.INVALID_TOKEN;
            default -> PushResult.Status.FAILED;
        };
    }

    /**
     * 플랫폼별 옵션: Android 우선순위 HIGH·TTL 1시간, APNs 우선순위 10·만료 1시간, 웹 TTL 1시간. 같은 회차는 collapseKey 로 합친다.
     * addAllTokens 는 firebase-admin 9.10+ 에서 FID(addAllFids) 권장으로 deprecated 이지만,
     * 클라이언트 계약(docs/api-reminders.md)이 "FCM 등록 토큰"이라 그대로 쓴다. FID 전환은 클라이언트와 함께 후속 검토.
     */
    @SuppressWarnings("deprecation")
    MulticastMessage buildMessage(List<String> tokens, PushMessage message) {
        long expiresAt = clock.instant().plus(TTL).getEpochSecond();
        return MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                .putAllData(message.data())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setTtl(TTL.toMillis())
                        .setCollapseKey(message.collapseKey())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .putHeader("apns-expiration", String.valueOf(expiresAt))
                        .putHeader("apns-collapse-id", message.collapseKey())
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .setWebpushConfig(WebpushConfig.builder()
                        .putHeader("TTL", String.valueOf(TTL.toSeconds()))
                        .setNotification(WebpushNotification.builder()
                                .setTag(message.collapseKey())
                                .setIcon(WEB_ICON)
                                .build())
                        .build())
                .build();
    }

    @Override
    public void destroy() {
        app.delete();
    }
}
