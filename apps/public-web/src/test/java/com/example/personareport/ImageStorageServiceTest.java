package com.example.personareport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.personareport.report.service.ImageStorageService;
import com.example.personareport.report.service.ImageStorageService.ImageUploadException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ImageStorageServiceTest {

    private final ImageStorageService service = new ImageStorageService("./target/test-uploads");

    @Test
    void storeImages_emptyList_returnsNull() {
        assertThat(service.storeImages(1L, List.of())).isNull();
        assertThat(service.storeImages(1L, null)).isNull();
    }

    @Test
    void storeImages_tooManyFiles_throwsException() {
        List<org.springframework.web.multipart.MultipartFile> files = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> (org.springframework.web.multipart.MultipartFile) new MockMultipartFile("img" + i, "img" + i + ".png", "image/png", new byte[100]))
                .toList();
        assertThatThrownBy(() -> service.storeImages(1L, files))
                .isInstanceOf(ImageUploadException.class)
                .hasMessageContaining("최대 20장");
    }

    @Test
    void storeImages_fileTooLarge_throwsException() {
        byte[] large = new byte[11 * 1024 * 1024]; // 11MB
        MockMultipartFile file = new MockMultipartFile("img", "img.png", "image/png", large);
        assertThatThrownBy(() -> service.storeImages(1L, List.of(file)))
                .isInstanceOf(ImageUploadException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void storeImages_invalidContentType_throwsException() {
        MockMultipartFile file = new MockMultipartFile("img", "img.txt", "text/plain", "data".getBytes());
        assertThatThrownBy(() -> service.storeImages(1L, List.of(file)))
                .isInstanceOf(ImageUploadException.class)
                .hasMessageContaining("지원하지 않는 이미지 형식");
    }

    @Test
    void resolvePaths_nullOrBlank_returnsEmpty() {
        assertThat(service.resolvePaths(null)).isEmpty();
        assertThat(service.resolvePaths("  ")).isEmpty();
    }

    @Test
    void resolvePaths_nonExistentFiles_returnsEmpty() {
        assertThat(service.resolvePaths("nonexistent.jpg")).isEmpty();
    }
}
