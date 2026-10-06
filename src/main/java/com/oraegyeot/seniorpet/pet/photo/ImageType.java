package com.oraegyeot.seniorpet.pet.photo;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** 허용하는 사진 형식(jpeg/png/webp). Content-Type 과 파일 앞부분(매직 바이트)을 둘 다 확인한다. */
public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String contentType;
    private final String extension;

    ImageType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** 요청의 Content-Type(파라미터 제외, 대소문자 무시)으로 찾는다. */
    public static Optional<ImageType> fromContentType(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String base = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.contentType.equals(base)).findFirst();
    }

    /** 저장된 파일 확장자로 찾는다(조회 응답의 Content-Type 결정용). */
    public static Optional<ImageType> fromExtension(String ext) {
        return Arrays.stream(values()).filter(t -> t.extension.equals(ext)).findFirst();
    }

    /** 파일 내용의 매직 바이트로 형식을 판별한다. */
    public static Optional<ImageType> detect(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (d.length >= 8 && Arrays.equals(Arrays.copyOf(d, 8), png)) {
            return Optional.of(PNG);
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }
}
