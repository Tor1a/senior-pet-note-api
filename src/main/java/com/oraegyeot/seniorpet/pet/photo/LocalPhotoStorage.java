package com.oraegyeot.seniorpet.pet.photo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로컬 디스크 사진 저장소(MVP).
 *
 * 경로 조작 방지:
 * 1) 파일명은 서버가 만든다(UUID 32자리 + 허용 확장자). 업로드 파일명은 쓰지 않는다.
 * 2) 읽기·삭제 전 경로가 "<uuid>/<32자리 hex>.<jpg|png|webp>" 형식인지 정규식으로 확인한다(../ 불가).
 * 3) 정규화한 절대 경로가 저장 폴더 안에 있는지 한 번 더 확인한다.
 */
@Component
public class LocalPhotoStorage implements PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalPhotoStorage.class);

    private static final Pattern SAFE_PATH = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{32}\\.(jpg|png|webp)$");

    private final Path root;

    public LocalPhotoStorage(PhotoProperties props) {
        String dir = (props.dir() == null || props.dir().isBlank()) ? "./data/photos" : props.dir();
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String save(UUID userId, byte[] data, ImageType type) {
        String path = userId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + type.extension();
        Path target = resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 저장 실패", e);
        }
        return path;
    }

    @Override
    public Optional<byte[]> load(String path) {
        try {
            return Optional.of(Files.readAllBytes(resolve(path)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("사진 읽기 실패", e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("사진 파일 삭제 실패(무시): {}", e.getMessage());
        }
    }

    /** 경로 검증 후 절대 경로로 바꾼다. 규칙에 맞지 않으면 IllegalArgumentException. */
    Path resolve(String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("허용되지 않는 사진 경로");
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("저장 폴더 밖 경로");
        }
        return resolved;
    }
}
