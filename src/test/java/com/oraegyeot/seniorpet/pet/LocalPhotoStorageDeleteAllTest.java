package com.oraegyeot.seniorpet.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.pet.photo.ImageType;
import com.oraegyeot.seniorpet.pet.photo.LocalPhotoStorage;
import com.oraegyeot.seniorpet.pet.photo.PhotoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LocalPhotoStorage.deleteAllOf: 사용자 폴더만 지우고 그 밖은 건드리지 않는다. */
class LocalPhotoStorageDeleteAllTest {

    @TempDir
    Path tmp;

    private LocalPhotoStorage storage(Path root) {
        return new LocalPhotoStorage(new PhotoProperties(root.toString()));
    }

    @Test
    void 사용자_폴더만_지운다() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("photos"));
        LocalPhotoStorage s = storage(root);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String pa1 = s.save(a, new byte[] {1}, ImageType.JPEG);
        s.save(a, new byte[] {2}, ImageType.PNG);
        String pb = s.save(b, new byte[] {3}, ImageType.JPEG);
        Path sibling = Files.createFile(root.resolve("keep.txt"));

        s.deleteAllOf(a);

        assertThat(Files.exists(root.resolve(a.toString()))).isFalse();
        assertThat(s.load(pa1)).isEmpty();
        assertThat(s.load(pb)).isPresent();
        assertThat(Files.exists(sibling)).isTrue();
        assertThat(Files.exists(root)).isTrue();
    }

    @Test
    void 폴더가_없어도_예외_없이_끝난다() {
        storage(tmp).deleteAllOf(UUID.randomUUID());
    }

    @Test
    void 심볼릭_링크는_따라가지_않는다() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("photos"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path precious = Files.createFile(outside.resolve("precious.txt"));
        UUID a = UUID.randomUUID();
        Path dir = Files.createDirectory(root.resolve(a.toString()));
        Files.createSymbolicLink(dir.resolve("link"), outside);

        storage(root).deleteAllOf(a);

        assertThat(Files.exists(precious)).isTrue();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void 사용자_폴더_자체가_링크여도_대상은_지우지_않는다() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("photos"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path precious = Files.createFile(outside.resolve("precious.txt"));
        UUID a = UUID.randomUUID();
        Files.createSymbolicLink(root.resolve(a.toString()), outside);

        storage(root).deleteAllOf(a);

        assertThat(Files.exists(precious)).isTrue();
    }
}
