package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 푸시 본문 말줄임(코드 포인트 기준) 단위 테스트. */
class ReminderMessageTextTest {

    @Test
    void 경계_10자는_그대로_11자는_앞9자_말줄임() {
        assertThat(ReminderDispatcher.truncate("1234567890", 10)).isEqualTo("1234567890");
        assertThat(ReminderDispatcher.truncate("12345678901", 10)).isEqualTo("123456789…");
    }

    @Test
    void 이모지는_한_글자로_센다() {
        String ten = "😀".repeat(10);
        assertThat(ReminderDispatcher.truncate(ten, 10)).isEqualTo(ten);
        assertThat(ReminderDispatcher.truncate(ten + "😀", 10)).isEqualTo("😀".repeat(9) + "…");
    }

    @Test
    void 앞뒤_공백은_지우고_null은_빈_문자열() {
        assertThat(ReminderDispatcher.truncate("  초코  ", 10)).isEqualTo("초코");
        assertThat(ReminderDispatcher.truncate(null, 10)).isEmpty();
    }

    @Test
    void 본문_조립과_용량_생략() {
        assertThat(ReminderDispatcher.body("초코", "아조딜", "1캡슐")).isEqualTo("초코 · 아조딜 1캡슐");
        assertThat(ReminderDispatcher.body("초코", "레나메진", null)).isEqualTo("초코 · 레나메진");
        assertThat(ReminderDispatcher.body("초코", "레나메진", "  ")).isEqualTo("초코 · 레나메진");
    }

    @Test
    void 각_부분_상한이_적용된다() {
        String body = ReminderDispatcher.body("가".repeat(11), "나".repeat(21), "다".repeat(11));
        assertThat(body).isEqualTo("가".repeat(9) + "… · " + "나".repeat(19) + "… " + "다".repeat(9) + "…");
    }
}
