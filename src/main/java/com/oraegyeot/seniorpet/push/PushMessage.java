package com.oraegyeot.seniorpet.push;

import java.util.Map;

/**
 * 플랫폼 공통 푸시 메시지.
 *
 * @param title       알림 제목
 * @param body        알림 본문
 * @param data        앱에 전달할 값(모두 문자열). 클라이언트는 이 값으로 화면을 연다
 * @param collapseKey 같은 키의 알림은 기기에서 하나로 합쳐진다(재전송 시 중복 표시 방지)
 */
public record PushMessage(String title, String body, Map<String, String> data, String collapseKey) {
}
