package com.oraegyeot.seniorpet.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.FirebaseApp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    @Test
    void 웹_푸시에_먹였어요_액션_1개가_있고_나머지_설정은_그대로다() throws Exception {
        FcmPushSender sender = new FcmPushSender(new FcmProperties(true, FakeServiceAccount.base64()),
                Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));
        try {
            String collapseKey = "11111111-1111-1111-1111-111111111111:2026-10-12:08:00";
            Map<String, String> data = Map.of("type", "med_reminder", "medicationId", "m1", "petId", "p1",
                    "recordDate", "2026-10-12", "scheduledTime", "08:00");
            var multicast = sender.buildMessage(List.of("token-a"),
                    new PushMessage("투약 시간이에요", "초코 · 아조딜 1캡슐", data, collapseKey));
            Method m = multicast.getClass().getDeclaredMethod("getMessageList");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) m.invoke(multicast);
            assertThat(messages).hasSize(1);
            JsonNode json = new ObjectMapper().valueToTree(toMap(messages.get(0)));

            JsonNode web = json.get("webpush");
            assertThat(web.get("notification").get("actions")).hasSize(1);
            assertThat(web.get("notification").get("actions").get(0).get("action").asText()).isEqualTo("taken");
            assertThat(web.get("notification").get("actions").get(0).get("title").asText()).isEqualTo("먹였어요");
            assertThat(web.get("notification").get("tag").asText()).isEqualTo(collapseKey);
            assertThat(web.get("notification").get("icon").asText()).isEqualTo("/pwa-192x192.png");
            assertThat(web.get("headers").get("TTL").asText()).isEqualTo("3600");

            assertThat(json.get("notification").get("title").asText()).isEqualTo("투약 시간이에요");
            assertThat(json.get("notification").get("body").asText()).isEqualTo("초코 · 아조딜 1캡슐");
            assertThat(new ObjectMapper().convertValue(json.get("data"), Map.class)).isEqualTo(data);

            JsonNode android = json.get("android");
            assertThat(android.get("priority").asText()).isEqualTo("high");
            assertThat(android.get("ttl").asText()).isEqualTo("3600s");
            assertThat(android.get("collapse_key").asText()).isEqualTo(collapseKey);
            assertThat(android.has("notification")).isFalse();
            JsonNode apns = json.get("apns");
            assertThat(apns.get("headers").get("apns-priority").asText()).isEqualTo("10");
            assertThat(apns.get("headers").get("apns-collapse-id").asText()).isEqualTo(collapseKey);
            assertThat(apns.get("headers").get("apns-expiration").asText()).isEqualTo(String.valueOf(Instant.parse("2026-10-12T01:00:00Z").getEpochSecond()));
            assertThat(apns.get("payload").get("aps").get("sound").asText()).isEqualTo("default");
            assertThat(apns.get("payload").get("aps").size()).isEqualTo(1);
        } finally {
            sender.destroy();
        }
    }

    /** 투약 알림이 아닌 메시지(할 일 알림 등)에는 웹 액션 버튼이 없고, 그 밖의 설정은 투약 알림과 같다. */
    @Test
    void 할_일_알림_웹_푸시에는_액션_버튼이_없고_나머지_설정은_같다() throws Exception {
        FcmPushSender sender = new FcmPushSender(new FcmProperties(true, FakeServiceAccount.base64()),
                Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));
        try {
            String collapseKey = "todo:11111111-1111-1111-1111-111111111111:day_before:2026-10-14";
            Map<String, String> data = Map.of("type", "todo_reminder", "todoId", "t1", "petId", "p1",
                    "dueDate", "2026-10-14", "kind", "day_before");
            JsonNode json = firstMessageJson(sender, new PushMessage("내일 일정이 있어요", "초코 · 접종", data, collapseKey));

            JsonNode web = json.get("webpush");
            JsonNode actions = web.get("notification").get("actions");
            assertThat(actions == null || actions.isEmpty()).isTrue();
            assertThat(web.get("notification").get("tag").asText()).isEqualTo(collapseKey);
            assertThat(web.get("notification").get("icon").asText()).isEqualTo("/pwa-192x192.png");
            assertThat(web.get("headers").get("TTL").asText()).isEqualTo("3600");
            assertThat(json.get("notification").get("title").asText()).isEqualTo("내일 일정이 있어요");
            assertThat(new ObjectMapper().convertValue(json.get("data"), Map.class)).isEqualTo(data);
            assertThat(json.get("android").get("priority").asText()).isEqualTo("high");
            assertThat(json.get("android").get("collapse_key").asText()).isEqualTo(collapseKey);
            assertThat(json.get("apns").get("headers").get("apns-priority").asText()).isEqualTo("10");
            assertThat(json.get("apns").get("headers").get("apns-collapse-id").asText()).isEqualTo(collapseKey);
            assertThat(json.get("apns").get("payload").get("aps").get("sound").asText()).isEqualTo("default");
        } finally {
            sender.destroy();
        }
    }

    @Test
    void data_type_이_없거나_다른_값이면_액션_버튼이_없다() throws Exception {
        FcmPushSender sender = new FcmPushSender(new FcmProperties(true, FakeServiceAccount.base64()), Clock.systemUTC());
        try {
            for (Map<String, String> data : List.of(Map.<String, String>of(), Map.of("type", "other"))) {
                JsonNode actions = firstMessageJson(sender, new PushMessage("t", "b", data, "k"))
                        .get("webpush").get("notification").get("actions");
                assertThat(actions == null || actions.isEmpty()).as(data.toString()).isTrue();
            }
        } finally {
            sender.destroy();
        }
    }

    private static JsonNode firstMessageJson(FcmPushSender sender, PushMessage message) throws Exception {
        var multicast = sender.buildMessage(List.of("token-a"), message);
        Method m = multicast.getClass().getDeclaredMethod("getMessageList");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) m.invoke(multicast);
        return new ObjectMapper().valueToTree(toMap(messages.get(0)));
    }

    /** Message 의 package-private wrapForTransport() 와 firebase-admin 이 쓰는 JSON 직렬화로 FCM 요청 본문(message 부분)을 얻는다. */
    private static Map<String, Object> toMap(Message message) throws Exception {
        Method wrap = Message.class.getDeclaredMethod("wrapForTransport", boolean.class);
        wrap.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) wrap.invoke(message, false);
        String body = GsonFactory.getDefaultInstance().toString(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) parsed.get("message");
        return inner;
    }
}
