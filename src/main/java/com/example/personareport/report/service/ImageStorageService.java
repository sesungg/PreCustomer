package com.example.personareport.report.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class ImageStorageService {

    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "image/webp", "image/png", "image/jpeg"
    );
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final int MAX_IMAGE_COUNT = 10;

    private final Path uploadDir;

    public ImageStorageService(@Value("${app.upload.dir:./uploads}") String uploadDirPath) {
        this.uploadDir = Path.of(uploadDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("업로드 디렉토리를 생성할 수 없습니다: " + this.uploadDir, e);
        }
    }

    public String storeImages(Long orderId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }

        List<MultipartFile> validFiles = files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();

        if (validFiles.isEmpty()) {
            return null;
        }

        if (validFiles.size() > MAX_IMAGE_COUNT) {
            throw new ImageUploadException("이미지는 최대 " + MAX_IMAGE_COUNT + "장까지 업로드할 수 있습니다.");
        }

        List<String> paths = new ArrayList<>();
        for (MultipartFile file : validFiles) {
            validateImage(file);
            String filename = storeFile(orderId, file);
            paths.add(filename);
        }

        return String.join("\n", paths);
    }

    private void validateImage(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ImageUploadException("파일 크기는 10MB를 초과할 수 없습니다: " + file.getOriginalFilename());
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new ImageUploadException(
                    "지원하지 않는 이미지 형식입니다. WebP, PNG, JPG만 업로드 가능합니다: " + file.getOriginalFilename()
            );
        }
    }

    private String storeFile(Long orderId, MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf('.'));
        }
        String filename = orderId + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;

        try {
            Path targetPath = uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("이미지 저장: {}", targetPath);
            return filename;
        } catch (IOException e) {
            throw new ImageUploadException("이미지 저장 중 오류가 발생했습니다.", e);
        }
    }

    public List<Path> resolvePaths(String imagePaths) {
        if (imagePaths == null || imagePaths.isBlank()) {
            return List.of();
        }
        return Arrays.stream(imagePaths.split("\n"))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(uploadDir::resolve)
                .filter(Files::exists)
                .toList();
    }

    public static class ImageUploadException extends RuntimeException {
        public ImageUploadException(String message) {
            super(message);
        }

        public ImageUploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
