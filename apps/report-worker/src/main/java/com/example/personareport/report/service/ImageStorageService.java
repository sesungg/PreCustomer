package com.example.personareport.report.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** report-worker가 주문에 저장된 이미지 경로를 실제 파일 Path로 해석한다. */
@Service
public class ImageStorageService {

    private final Path uploadDir;

    public ImageStorageService(@Value("${app.upload.dir:./uploads}") String uploadDirPath) {
        this.uploadDir = Path.of(uploadDirPath).toAbsolutePath().normalize();
    }

    /** imagePaths 문자열을 실제 파일 시스템 Path 목록으로 변환한다. 존재하지 않는 파일은 제외한다. */
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
}
