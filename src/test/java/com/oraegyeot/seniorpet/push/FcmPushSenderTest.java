package com.oraegyeot.seniorpet.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.MessagingErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FCM 발송기 단위 테스트. 네트워크 호출 없음(실제 발송은 하지 않는다). */
class FcmPushSenderTest {

    @Test
    void 토큰_자체가_무효인_오류는_INVALID_TOKEN() {
        assertThat(FcmPushSender.classify(MessagingErrorCode.UNREGISTERED)).isEqualTo(PushResult.Status.INVALID_TOKEN);
        assertThat(FcmPushSender.classify(MessagingErrorCode.SENDER_ID_MISMATCH)).isEqualTo(PushResult.Status.INVALID_TOKEN);
    }

    @Test
    void 일시_오류와_그_밖의_오류는_FAILED() {
        assertThat(FcmPushSender.classify(MessagingErrorCode.INVALID_ARGUMENT)).isEqualTo(PushResult.Status.FAILED); // 토큰 삭제 안 함
        assertThat(FcmPushSender.classify(MessagingErrorCode.UNAVAILABLE)).isEqualTo(PushResult.Status.FAILED);
        assertThat(FcmPushSender.classify(MessagingErrorCode.INTERNAL)).isEqualTo(PushResult.Status.FAILED);
        assertThat(FcmPushSender.classify(MessagingErrorCode.QUOTA_EXCEEDED)).isEqualTo(PushResult.Status.FAILED);
        assertThat(FcmPushSender.classify(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)).isEqualTo(PushResult.Status.FAILED);
        assertThat(FcmPushSender.classify(null)).isEqualTo(PushResult.Status.FAILED);
    }

    @Test
    void 자격증명이_없거나_깨지면_생성_실패() {
        assertThatThrownBy(() -> FcmPushSender.parseCredentials(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FCM_CREDENTIALS_BASE64");
        assertThatThrownBy(() -> FcmPushSender.parseCredentials("  "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> FcmPushSender.parseCredentials("%%%not-base64%%%"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> FcmPushSender.parseCredentials("aGVsbG8=")) // "hello"
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("서비스 계정 JSON");
    }

    @Test
    void 형식이_맞는_가짜_키로_초기화와_메시지_생성까지_네트워크_없이_된다() throws Exception {
        FcmPushSender sender = new FcmPushSender(new FcmProperties(true, FakeServiceAccount.base64()), Clock.systemUTC());
        try {
            assertThat(FirebaseApp.getInstance(FcmPushSender.APP_NAME).getOptions().getProjectId())
                    .isEqualTo(FakeServiceAccount.PROJECT_ID);
            String collapseKey = "11111111-1111-1111-1111-111111111111:2026-10-12:08:00";
            var message = sender.buildMessage(List.of("token-a", "token-b"),
                    new PushMessage("투약 시간이에요", "초코 · 아조딜 1캡슐", Map.of("type", "med_reminder"), collapseKey));
            assertThat(message).isNotNull();
            assertThat(sender.send(List.of(), new PushMessage("t", "b", Map.of(), "k"))).isEmpty(); // 토큰 없으면 호출 안 함
        } finally {
            sender.destroy();
        }
        assertThat(FirebaseApp.getApps()).noneMatch(a -> a.getName().equals(FcmPushSender.APP_NAME));
    }
}
