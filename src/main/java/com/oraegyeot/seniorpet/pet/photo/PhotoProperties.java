package com.oraegyeot.seniorpet.pet.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.photo.* 설정. dir = 사진 저장 폴더(기본 ./data/photos, 환경변수 PHOTO_DIR). */
@ConfigurationProperties("app.photo")
public record PhotoProperties(String dir) {
}
