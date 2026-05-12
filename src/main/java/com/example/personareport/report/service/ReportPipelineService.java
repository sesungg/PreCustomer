package com.example.personareport.report.service;

import com.example.personareport.modules.shopping.service.ShoppingSearchService;
import com.example.personareport.order.service.OrderService;
import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.job.ReportJobService;
import com.example.personareport.report.pipeline.PipelineQueryService;
import com.example.personareport.report.pipeline.PipelineJavaService;
import com.example.personareport.report.pipeline.PipelineSaveService;
import com.example.personareport.report.pipeline.PipelineStopRequestedException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
    private static final int REACTION_BATCH_SIZE = 3;

    private final PipelineProgressService progressService;
    private final PipelineJavaService pipelineJava;
    private final PipelineQueryService queryService;
    private final PipelineSaveService saveService;
    private final ReportJobService jobService;
    private final OrderService orderService;
    private final ShoppingSearchService shoppingService;

    @Value("${app.pipeline.scripts-dir:./scripts/pipeline}")
    private String scriptsDirPath;

    @Value("${app.pipeline.python-bin:python3}")
    private String pythonBin;

    @Value("${app.pipeline.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${app.pipeline.selected-count:30}")
    private int selectedCount;

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

    @Value("${app.pipeline.persona-candidate-limit:50000}")
    private int personaCandidateLimit;

    @Value("${app.pipeline.stale-timeout-minutes:60}")
    private long staleTimeoutMinutes = 60;

    /** 비동기로 전체 파이프라인 실행. DB 산출물 기준으로 완료된 단계는 건너뛰고 누락된 단계만 재개한다. */
    @Async
    public void runDetailPagePipeline(Long orderId, List<Path> imagePaths) {
        runPipeline(orderId, imagePaths, false, null, Duration.ofMinutes(staleTimeoutMinutes));
    }

    /** 비동기로 주문 단위 리포트 산출물을 삭제한 뒤 처음부터 재생성한다. */
    @Async
    public void regenerateDetailPagePipeline(Long orderId, List<Path> imagePaths) {
        runPipeline(orderId, imagePaths, true, null, Duration.ofMinutes(staleTimeoutMinutes));
    }

    /** report_job 워커가 호출하는 동기 실행 진입점. */
    public String executeDetailPagePipeline(Long orderId, List<Path> imagePaths, boolean regenerateFromScratch,
                                            Long reportJobId, Duration jobLeaseDuration) {
        return runPipeline(orderId, imagePaths, regenerateFromScratch, reportJobId, jobLeaseDuration);
    }

    /** 실행 중인 파이프라인을 사용자 관점에서 중지 상태로 전환하고 worker에는 협조적 중지를 요청한다. */
    public boolean requestStop(Long orderId) {
        var progressOpt = progressService.findById(orderId);
        if (progressOpt.isEmpty() || progressOpt.get().isTerminal()) {
            return false;
        }
        var progress = progressOpt.get();
        String message = progress.isStale(Duration.ofMinutes(staleTimeoutMinutes))
                ? "오래 응답하지 않는 리포트 생성을 중지 처리했습니다."
                : "사용자 요청으로 리포트 생성이 중지되었습니다.";
        boolean stopped = progressService.stop(orderId, message);
        if (stopped) {
            orderService.markStopped(orderId);
        }
        return stopped;
    }

    private String runPipeline(Long orderId, List<Path> imagePaths, boolean regenerateFromScratch,
                               Long reportJobId, Duration jobLeaseDuration) {
        boolean hasImages = imagePaths != null && !imagePaths.isEmpty();
        int totalSteps = TOTAL_STEPS_BASE + (hasImages ? 2 : 0);

        var existingProgress = progressService.findById(orderId);
        if (existingProgress.isPresent() && existingProgress.get().isActive()) {
            var activeProgress = existingProgress.get();
            if (!activeProgress.isStale(Duration.ofMinutes(staleTimeoutMinutes))) {
                log.info("이미 실행 중인 파이프라인이 있어 새 실행을 건너뜁니다. orderId={}, status={}",
                        orderId, activeProgress.getStatus());
                return activeProgress.getStatus();
            }
            activeProgress.fail("오래 응답하지 않는 기존 실행을 실패 처리하고 재개합니다.");
            progressService.save(activeProgress);
            orderService.markFailed(orderId);
            log.warn("stale 파이프라인을 실패 처리하고 재개합니다. orderId={}, staleStep={}",
                    orderId, activeProgress.getCurrentStepName());
        }

        // 파이프라인 설정
        String profileVersion = "product_target_profile_v1";
        String responseVersion = hasImages
                ? "detail_page_reaction_v2_image"
                : "detail_page_reaction_v1";
        String reportVersion = hasImages
                ? "detail_page_final_report_v2_image"
                : "detail_page_final_report_v1";

        if (reportJobId != null) {
            jobService.prepareSteps(reportJobId, hasImages);
        }

        // selectedCount는 app.pipeline.selected-count 설정값 사용 (기본 30, 운영 시 100)
        if (regenerateFromScratch) {
            saveService.clearReportArtifacts(orderId);
            log.info("주문 리포트 산출물 삭제 후 처음부터 재생성합니다. orderId={}", orderId);
        }

        // 파이프라인 시작 시 GENERATING 상태로 전이
        orderService.markGenerating(orderId);
        PipelineProgress progress = progressService.save(PipelineProgress.start(orderId, totalSteps));
        log.info("파이프라인 시작/재개 orderId={}, hasImages={}, responseVersion={}, reportVersion={}, regenerate={}",
                orderId, hasImages, responseVersion, reportVersion, regenerateFromScratch);

        try {
            List<PipelineStep> steps = new ArrayList<>();
            steps.add(new PipelineStep(
                    "crawl",
                    "URL 페이지 크롤링 중",
                    () -> queryService.hasPageSnapshot(orderId),
                    () -> runPython("crawl_page_snapshot.py", "--order-id", orderId.toString())
            ));
            steps.add(new PipelineStep(
                    "shopping",
                    "네이버 쇼핑 경쟁 상품 분석 중",
                    () -> queryService.hasReportShoppingAnalysis(orderId),
                    () -> {
                        var order = orderService.getOrder(orderId);
                        Integer basePrice = parsePrice(order.getPriceText());
                        shoppingService.executeReportSearch(orderId, order.getProjectName(), order.getProjectName(),
                                basePrice, null, null, null, null, true);
                    }
            ));

            if (hasImages) {
                List<String> expectedImagePaths = imagePaths.stream()
                        .map(p -> p.toAbsolutePath().toString())
                        .toList();
                steps.add(new PipelineStep(
                        "image_analysis",
                        "이미지 분석 중 (Gemini)",
                        () -> queryService.hasImageAnalysesForPaths(orderId, expectedImagePaths),
                        () -> {
                    List<String> args = new ArrayList<>(List.of("--order-id", orderId.toString(),
                            "--provider", "gemini", "--model-version", "gemini-2.5-flash-lite"));
                    if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                        args.add("--api-key"); args.add(geminiApiKey);
                    }
                    for (Path p : imagePaths) { args.add("--image-path"); args.add(p.toAbsolutePath().toString()); }
                    runPython("analyze_detail_page_images.py", args.toArray(new String[0]));
                }));
            }

            steps.add(new PipelineStep(
                    "target_profile",
                    "상품 타겟 프로필 생성 중",
                    () -> queryService.hasTargetProfile(orderId, profileVersion),
                    () -> pipelineJava.generateTargetProfile(orderId, profileVersion)
            ));
            steps.add(new PipelineStep(
                    "persona_selection",
                    "페르소나 " + selectedCount + "명 선별 중",
                    () -> queryService.countSelectedPersonasForLatestTargetProfile(orderId, profileVersion) >= selectedCount,
                    () -> pipelineJava.selectPersonas(orderId, selectedCount, personaCandidateLimit, true)
            ));
            steps.add(new PipelineStep(
                    "persona_reactions",
                    "페르소나 반응 생성 중",
                    () -> queryService.hasCompleteReactions(orderId, responseVersion),
                    () -> pipelineJava.generateReactions(orderId, responseVersion, REACTION_BATCH_SIZE, true,
                            () -> isStopRequested(orderId, reportJobId, jobLeaseDuration))
            ));
            steps.add(new PipelineStep(
                    "final_report",
                    "최종 리포트 취합 중",
                    () -> queryService.hasFinalReport(orderId, reportVersion),
                    () -> pipelineJava.generateFinalReport(orderId, responseVersion, reportVersion)
            ));

            for (PipelineStep step : steps) {
                throwIfStopRequested(orderId, reportJobId, jobLeaseDuration);
                runStep(progress, step, reportJobId, jobLeaseDuration);
                throwIfStopRequested(orderId, reportJobId, jobLeaseDuration);
            }

            progress.complete();
            progressService.save(progress);
            orderService.markCompleted(orderId);
            log.info("파이프라인 전체 완료 orderId={}, responseVersion={}, reportVersion={}, selectedCount={}",
                    orderId, responseVersion, reportVersion, selectedCount);
            return PipelineProgress.STATUS_COMPLETED;

        } catch (PipelineStopRequestedException e) {
            log.info("파이프라인 중지 orderId={}: {}", orderId, e.getMessage());
            progress.stop(e.getMessage());
            progressService.save(progress);
            orderService.markStopped(orderId);
            return PipelineProgress.STATUS_STOPPED;
        } catch (Exception e) {
            if (isStopRequested(orderId, reportJobId, jobLeaseDuration)) {
                log.info("파이프라인 예외를 중지 요청으로 처리 orderId={}: {}", orderId, e.getMessage());
                progress.stop("사용자 요청으로 리포트 생성이 중지되었습니다.");
                progressService.save(progress);
                orderService.markStopped(orderId);
                return PipelineProgress.STATUS_STOPPED;
            }
            log.error("파이프라인 예외 orderId={}: {}", orderId, e.getMessage(), e);
            progress.fail(e.getMessage());
            progressService.save(progress);
            orderService.markFailed(orderId);
            return PipelineProgress.STATUS_FAILED;
        }
    }

    private void throwIfStopRequested(Long orderId, Long reportJobId, Duration jobLeaseDuration) {
        if (isStopRequested(orderId, reportJobId, jobLeaseDuration)) {
            throw new PipelineStopRequestedException("사용자 요청으로 리포트 생성이 중지되었습니다.");
        }
    }

    private boolean isStopRequested(Long orderId, Long reportJobId, Duration jobLeaseDuration) {
        if (reportJobId != null) {
            jobService.heartbeat(reportJobId, jobLeaseDuration);
            if (jobService.isCancelRequested(reportJobId)) {
                return true;
            }
        }
        return progressService.isStopRequested(orderId);
    }

    /** 완료 산출물이 있으면 스킵하고, 실행 후에도 산출물을 다시 확인한다. */
    private void runStep(PipelineProgress progress, PipelineStep step, Long reportJobId, Duration jobLeaseDuration) {
        if (step.isComplete()) {
            progress.advanceStep("완료됨 - " + step.name());
            progressService.save(progress);
            if (reportJobId != null) {
                jobService.heartbeat(reportJobId, jobLeaseDuration);
                jobService.markStepSkipped(reportJobId, step.key());
            }
            log.info("[pipeline:skip] {}", step.name());
            return;
        }
        progress.advanceStep(step.name());
        progressService.save(progress);
        if (reportJobId != null) {
            jobService.heartbeat(reportJobId, jobLeaseDuration);
            jobService.markStepRunning(reportJobId, step.key());
        }
        try {
            step.task().run();
            if (!step.isComplete()) {
                throw new RuntimeException("단계 완료 산출물을 확인하지 못했습니다: " + step.name());
            }
            if (reportJobId != null) {
                jobService.heartbeat(reportJobId, jobLeaseDuration);
                jobService.markStepCompleted(reportJobId, step.key());
            }
        } catch (PipelineStopRequestedException e) {
            if (reportJobId != null) {
                jobService.markStepStopped(reportJobId, step.key(), e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            if (reportJobId != null) {
                jobService.markStepFailed(reportJobId, step.key(), e.getMessage());
            }
            throw e;
        }
    }

    private Integer parsePrice(String priceText) {
        try {
            if (priceText == null) return null;
            String digits = priceText.replaceAll("[^0-9]", "");
            return digits.isBlank() ? null : Integer.parseInt(digits);
        } catch (Exception ignored) {
            return null;
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
                String tail = output.length() > 2000
                        ? output.substring(output.length() - 2000)
                        : output.toString();
                throw new RuntimeException("Python 스크립트 실패: " + scriptName
                        + " exit=" + exitCode + "\n" + tail);
            }
            log.debug("Python 완료: {} output={}", scriptName, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Python 스크립트 실행 중 인터럽트: " + scriptName, e);
        } catch (Exception e) {
            throw new RuntimeException("Python 스크립트 실행 실패: " + scriptName + " - " + e.getMessage(), e);
        }
    }

    private record PipelineStep(String key, String name, BooleanSupplier completionCheck, Runnable task) {
        boolean isComplete() {
            return completionCheck.getAsBoolean();
        }
    }
}
