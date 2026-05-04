package com.example.personareport.report.service;

import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.pipeline.PipelineJavaService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 리포트 생성 파이프라인 오케스트레이터. @Async 백그라운드 실행, PipelineProgress에 진행상황 기록. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPipelineService {

    private static final int TOTAL_STEPS_BASE = 4;

    private final PipelineProgressService progressService;
    private final PipelineJavaService pipelineJava;

    @Value("${app.pipeline.scripts-dir:/Users/ssg/dev/datasets/scripts}")
    private String scriptsDirPath;

    @Value("${app.pipeline.python-bin:python3}")
    private String pythonBin;

    @Value("${GEMINI_API_KEY:}")
    private String geminiApiKey;

    @Value("${app.pipeline.db-host:localhost}")
    private String dbHost;
    @Value("${app.pipeline.db-port:5432}")
    private String dbPort;
    @Value("${app.pipeline.db-name:precustomer}")
    private String dbName;
    @Value("${app.pipeline.db-user:postgres}")
    private String dbUser;
    @Value("${app.pipeline.db-password:postgres}")
    private String dbPassword;

    /** 비동기로 전체 파이프라인 실행. 크롤링→이미지분석→타겟프로필→페르소나선별→반응생성→최종리포트. */
    @Async
    public void runDetailPagePipeline(Long orderId, List<Path> imagePaths) {
        boolean hasImages = imagePaths != null && !imagePaths.isEmpty();
        int totalSteps = TOTAL_STEPS_BASE + (hasImages ? 2 : 0);

        PipelineProgress progress = progressService.findById(orderId)
                .orElseGet(() -> progressService.save(PipelineProgress.start(orderId, totalSteps)));

        try {
            // Step 0: URL 크롤링 (Python 유지 - requests+BeautifulSoup 의존)
            progress.advanceStep("URL 페이지 크롤링 중");
            progressService.save(progress);
            runPython("crawl_page_snapshot.py", "--order-id", orderId.toString());

            // Step 1: 이미지 분석 (Python 유지 - Gemini 호출)
            if (hasImages) {
                progress.advanceStep("이미지 분석 중 (Gemini)");
                progressService.save(progress);
                List<String> args = new ArrayList<>(List.of("--order-id", orderId.toString(),
                        "--provider", "gemini", "--model-version", "gemini-2.5-flash-lite"));
                if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                    args.add("--api-key"); args.add(geminiApiKey);
                }
                for (Path p : imagePaths) { args.add("--image-path"); args.add(p.toAbsolutePath().toString()); }
                runPython("analyze_detail_page_images.py", args.toArray(new String[0]));
            }

            // Step 2-5: Java 파이프라인 (DeepSeek Feign Client)
            progress.advanceStep("상품 타겟 프로필 생성 중");
            progressService.save(progress);
            pipelineJava.generateTargetProfile(orderId);

            progress.advanceStep("페르소나 30명 선별 중");
            progressService.save(progress);
            var personas = pipelineJava.selectPersonas(orderId, 30);

            progress.advanceStep("페르소나 반응 생성 중");
            progressService.save(progress);
            pipelineJava.generateReactions(orderId, personas);

            progress.advanceStep("최종 리포트 취합 중");
            progressService.save(progress);
            pipelineJava.generateFinalReport(orderId);

            progress.complete();
            progressService.save(progress);
            log.info("파이프라인 전체 완료 orderId={}", orderId);

        } catch (Exception e) {
            log.error("파이프라인 예외 orderId={}: {}", orderId, e.getMessage(), e);
            progress.fail(e.getMessage());
            progressService.save(progress);
        }
    }

    private void runPython(String scriptName, String... extraArgs) {
        Path scriptPath = Path.of(scriptsDirPath).resolve(scriptName);
        List<String> command = new ArrayList<>();
        command.add(pythonBin);
        command.add(scriptPath.toAbsolutePath().toString());
        command.addAll(List.of("--host", dbHost, "--port", dbPort, "--dbname", dbName,
                "--user", dbUser, "--password", dbPassword));
        command.addAll(List.of(extraArgs));
        log.info("Python 실행: {} | {}", scriptName, String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Python 실패: {} exit={}", scriptName, exitCode);
            }
        } catch (Exception e) {
            log.error("Python 예외: {} error={}", scriptName, e.getMessage());
        }
    }
}
