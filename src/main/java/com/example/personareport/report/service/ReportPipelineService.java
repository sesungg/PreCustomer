package com.example.personareport.report.service;

import com.example.personareport.modules.shopping.service.ShoppingSearchService;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.pipeline.PipelineJavaService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final int TOTAL_STEPS_BASE = 6; // crawl + naver + target + select + reactions + final

    private final PipelineProgressService progressService;
    private final PipelineJavaService pipelineJava;
    private final OrderService orderService;
    private final ShoppingSearchService shoppingService;

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

    /** 비동기로 전체 파이프라인 실행 (재개 지원). 실패한 스텝부터 다시 시작한다. */
    @Async
    public void runDetailPagePipeline(Long orderId, List<Path> imagePaths) {
        boolean hasImages = imagePaths != null && !imagePaths.isEmpty();
        int totalSteps = TOTAL_STEPS_BASE + (hasImages ? 2 : 0);

        PipelineProgress progress = progressService.findById(orderId)
                .orElseGet(() -> progressService.save(PipelineProgress.start(orderId, totalSteps)));

        // 실패했던 파이프라인이면 해당 스텝부터 재개
        int skipUntil = 0;
        if ("FAILED".equals(progress.getStatus())) {
            skipUntil = progress.getCurrentStep(); // 실패한 스텝 번호
            // progress를 IN_PROGRESS로 재설정
            progress = progressService.save(PipelineProgress.start(orderId, totalSteps));
            for (int i = 0; i < skipUntil; i++) {
                progress.advanceStep("이전 단계 (건너뜀)");
            }
            progressService.save(progress);
            log.info("파이프라인 재개 orderId={} from step={}", orderId, skipUntil);
        }

        try {
            final List<Map<String, Object>>[] personasHolder = new List[1];

            // Step 0: URL 크롤링
            runStep(progress, skipUntil, 0, "URL 페이지 크롤링 중",
                    () -> runPython("crawl_page_snapshot.py", "--order-id", orderId.toString()));

            // Step 1: 네이버 쇼핑 검색 (경쟁 상품 분석)
            runStep(progress, skipUntil, 1, "네이버 쇼핑 경쟁 상품 분석 중", () -> {
                var order = orderService.getOrder(orderId);
                Integer basePrice = null;
                try { basePrice = Integer.parseInt(order.getPriceText().replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                shoppingService.executeSearch(order.getProjectName(), order.getProjectName(), basePrice,
                        null, null, null, null, true);
            });

            // Step 2: 이미지 분석
            if (hasImages) {
                runStep(progress, skipUntil, 2, "이미지 분석 중 (Gemini)", () -> {
                    List<String> args = new ArrayList<>(List.of("--order-id", orderId.toString(),
                            "--provider", "gemini", "--model-version", "gemini-2.5-flash-lite"));
                    if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                        args.add("--api-key"); args.add(geminiApiKey);
                    }
                    for (Path p : imagePaths) { args.add("--image-path"); args.add(p.toAbsolutePath().toString()); }
                    runPython("analyze_detail_page_images.py", args.toArray(new String[0]));
                });
            }

            // Step numbers: crawl=0, naver=1, image=2(opt), target=s+1, persona=s+2, reactions=s+3, final=s+4
            int s = hasImages ? 2 : 1; // offset after crawl+naver[+image]

            // Step: 타겟 프로필
            runStep(progress, skipUntil, s + 1, "상품 타겟 프로필 생성 중",
                    () -> pipelineJava.generateTargetProfile(orderId));

            // Step: 페르소나 선별
            runStep(progress, skipUntil, s + 2, "페르소나 30명 선별 중",
                    () -> personasHolder[0] = pipelineJava.selectPersonas(orderId, 30));

            // Step: 반응 생성
            runStep(progress, skipUntil, s + 3, "페르소나 반응 생성 중", () -> {
                if (personasHolder[0] == null) personasHolder[0] = pipelineJava.selectPersonas(orderId, 30);
                pipelineJava.generateReactions(orderId, personasHolder[0]);
            });

            // Step: 최종 리포트
            runStep(progress, skipUntil, s + 4, "최종 리포트 취합 중",
                    () -> pipelineJava.generateFinalReport(orderId));

            progress.complete();
            progressService.save(progress);
            orderService.markCompleted(orderId);
            log.info("파이프라인 전체 완료 orderId={}", orderId);

        } catch (Exception e) {
            log.error("파이프라인 예외 orderId={}: {}", orderId, e.getMessage(), e);
            progress.fail(e.getMessage());
            progressService.save(progress);
            orderService.markFailed(orderId);
        }
    }

    /** skipUntil보다 작은 스텝은 건너뛰고, 현재 스텝이면 실행한다. */
    private void runStep(PipelineProgress progress, int skipUntil, int stepNum, String stepName, Runnable task) {
        if (progress.getCurrentStep() < skipUntil) return; // 이미 완료된 스텝
        progress.advanceStep(stepName);
        progressService.save(progress);
        task.run();
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
