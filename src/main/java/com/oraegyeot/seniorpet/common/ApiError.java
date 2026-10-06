package com.oraegyeot.seniorpet.common;

/**
 * 모든 오류 응답의 공통 형식: {"code": "...", "message": "..."}
 * code 는 클라이언트가 분기에 쓰는 고정 문자열, message 는 사람이 읽는 한국어 설명이다.
 */
public record ApiError(String code, String message) {
}
