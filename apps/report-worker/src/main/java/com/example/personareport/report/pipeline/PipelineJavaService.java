package com.example.personareport.report.pipeline;

import com.example.personareport.report.pipeline.dto.PipelineRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 리포트 생성 파이프라인 오케스트레이터. DB 트랜잭션 없이 조회→API→저장 순서로 실행.
 * Python generate_product_target_profile_image.py, select_personas_for_detail_page_generic.py,
 * generate_reactions_for_detail_page_image.py, generate_final_detail_page_report_image.py
 * 각각의 로직을 Java로 포팅.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineJavaService {

    private final ObjectMapper objectMapper;
    private final DeepSeekService deepSeekService;
    private final PipelineQueryService queryService;
    private final PipelineSaveService saveService;

    @Value("${app.deepseek.target-profile-model:${app.deepseek.model:deepseek-v4-flash}}")
    private String targetProfileModel;

    @Value("${app.deepseek.reaction-model:${app.deepseek.model:deepseek-v4-flash}}")
    private String reactionModel;

    @Value("${app.deepseek.final-report-model:${app.deepseek.model:deepseek-v4-flash}}")
    private String finalReportModel;

    @Value("${app.pipeline.reaction-concurrency:3}")
    private int reactionConcurrency;

    // Python과 동일한 trait→DB column 매핑
    private static final Map<String, String> TRAIT_DB_COLUMNS = Map.ofEntries(
            Map.entry("digitalAffinity", "digital_affinity_score"),
            Map.entry("priceSensitivity", "price_sensitivity_score"),
            Map.entry("trustSensitivity", "trust_sensitivity_score"),
            Map.entry("convenienceNeed", "convenience_need_score"),
            Map.entry("qualitySensitivity", "quality_sensitivity_score"),
            Map.entry("noveltyAcceptance", "novelty_acceptance_score"),
            Map.entry("localAffinity", "local_affinity_score"),
            Map.entry("familyDecision", "family_decision_score"),
            Map.entry("healthSafetySensitivity", "health_safety_sensitivity_score"),
            Map.entry("reviewDependency", "review_dependency_score")
    );
    private static final List<String> GROUP_ORDER = List.of(
            "CORE_TARGET", "ADJACENT_TARGET", "TRUST_PRICE_SKEPTIC", "LOW_FIT_CONTROL", "STRATIFIED_RANDOM");

    // =========================================================================
    // 1. generateTargetProfile
    // =========================================================================

    /**
     * 상품 타겟 프로필 생성.
     * 파이프라인 최초 스텝. 이미지 분석 결과를 payload에 포함해 DeepSeek 호출.
     */
    public void generateTargetProfile(Long orderId, String profileVersion) {
        if (profileVersion == null) profileVersion = "product_target_profile_v1";
        var p = PipelineRequest.builder().profileVersion(profileVersion)
                .modelVersion(targetProfileModel).temperature(0.1).maxTokens(7000).build();

        // 1) order 조회
        var order = queryService.findOrderById(orderId);

        // 2) snapshot 조회 (있으면)
        var snapshot = queryService.findLatestSnapshot(orderId, null);

        // 3) image analyses 조회 (없어도 계속 진행)
        var imageAnalyses = queryService.findImageAnalyses(orderId);
        log.info("[generateTargetProfile] orderId={}, hasSnapshot={}, imageAnalyses={}",
                orderId, !snapshot.isEmpty(), imageAnalyses.size());

        // 4) payload 구성 (Python build_payload() 동일)
        String payloadJson = buildTargetProfilePayload(order, snapshot, imageAnalyses);
        log.debug("[generateTargetProfile] payload length={}", payloadJson.length());

        // 5) DeepSeek 호출 (DB 트랜잭션 없음)
        Map<String, Object> raw = deepSeekService.callTargetProfile(
                payloadJson, p.getModelVersion(), p.getTemperature(), p.getMaxTokens(), p.getThinkingMode());

        if (raw.isEmpty()) {
            throw new RuntimeException("상품 타겟 프로필 생성 실패: DeepSeek 응답 파싱 오류");
        }

        // 6) 정규화 (Python normalize_profile() 동일)
        Map<String, Object> profile = normalizeProfile(raw, order, snapshot);

        // 7) 저장 (짧은 write 트랜잭션)
        Long snapshotId = snapshot.isEmpty() ? null : longVal(snapshot.get("id"));
        saveService.upsertTargetProfile(orderId, snapshotId, profileVersion,
                p.getModelName(), p.getModelVersion(), profile);
        log.info("[generateTargetProfile] 완료 orderId={}, version={}", orderId, profileVersion);
    }

    // =========================================================================
    // 2. selectPersonas
    // =========================================================================

    /**
     * 페르소나 선택. Python select_personas_for_detail_page_generic.py 동일 로직.
     *
     * <p>MV(mv_persona_normalized_score) 사용 시 개선 사항:
     * <ul>
     *   <li>computePercentile(): MV에 백분위가 사전 계산되어 있으므로 Java 메모리 연산 불필요</li>
     *   <li>computeRawScore(): rough_score가 MV에서 이미 계산되어 오므로, keyword/demo 보정만 적용</li>
     *   <li>_percentile_score: MV의 rough_score 순위를 기반으로 스케일링</li>
     * </ul>
     *
     * <p>MV가 없으면 fallback 실행 후 기존 Java 연산 방식을 유지한다.
     */
    public List<Map<String, Object>> selectPersonas(Long orderId, int selectedCount,
                                                     int candidateLimit, boolean resetSelected) {
        var p = PipelineRequest.builder()
                .selectedCount(selectedCount > 0 ? selectedCount : 30)
                .candidateLimit(candidateLimit > 0 ? candidateLimit : 150000)
                .build();

        // 1) product_target_profile 조회 (latest)
        var targetProfileOpt = queryService.findLatestTargetProfile(orderId);
        if (targetProfileOpt.isEmpty()) {
            throw new RuntimeException(
                    "product_target_profile이 없습니다. 먼저 generateTargetProfile()을 실행하세요. orderId=" + orderId);
        }
        var productProfile = targetProfileOpt.get();
        Map<String, Object> selectionWeights = parseJsonMap(productProfile.getSelectionWeights());
        Map<String, Object> demographicPriors = parseJsonMap(productProfile.getDemographicPriors());
        Map<String, Object> samplingStrategy = parseJsonMap(productProfile.getSamplingStrategy());
        List<String> coreKeywords = parseJsonList(productProfile.getCoreKeywords());
        List<String> exclusionKeywords = parseJsonList(productProfile.getExclusionKeywords());

        // 2) score model version
        String scoreModelVersion = queryService.findLatestScoreModelVersion();
        boolean useMv = queryService.isMvAvailable();
        log.info("[selectPersonas] orderId={}, selectedCount={}, candidateLimit={}, scoreModel={}, useMv={}",
                orderId, p.getSelectedCount(), p.getCandidateLimit(), scoreModelVersion, useMv);

        // 3) 후보 조회
        //    - MV 사용 시: 백분위 기반 rough_score + 페르소나 텍스트 역정규화 포함
        //    - fallback 시: 원점수 기반 rough_score + 조인 포함
        List<Map<String, Object>> candidates = queryService.findSelectionCandidates(
                p.getCandidateLimit(), selectionWeights, scoreModelVersion);
        if (candidates.size() < p.getSelectedCount()) {
            throw new RuntimeException(
                    "후보가 부족합니다. candidates=" + candidates.size() + ", selectCount=" + p.getSelectedCount());
        }
        log.info("[selectPersonas] candidates={}", candidates.size());

        // 4) 각 후보에 raw_score 계산
        if (useMv) {
            // MV 경로: rough_score는 백분위 기반으로 이미 계산됨.
            // keyword/demo 보정만 Java에서 추가 적용한다.
            for (var row : candidates) {
                double kwScore = keywordScore(row, coreKeywords, exclusionKeywords);
                double demoMult = demographicMultiplier(row, demographicPriors);
                row.put("_keyword_score", kwScore);
                row.put("_demo_multiplier", demoMult);
                // rough_score(0~1 범위)에 keyword 보정(0.01 스케일)과 demo 배수 적용
                double roughScore = numVal(row.get("rough_score"));
                double adjustedScore = roughScore * demoMult + kwScore * 0.01;
                row.put("_raw_score", adjustedScore);
            }
        } else {
            // Fallback 경로: 기존 원점수 기반 연산
            for (var row : candidates) {
                row.put("_keyword_score", keywordScore(row, coreKeywords, exclusionKeywords));
                row.put("_demo_multiplier", demographicMultiplier(row, demographicPriors));
                double rs = computeRawScore(row, selectionWeights, demographicPriors,
                        coreKeywords, exclusionKeywords);
                row.put("_raw_score", rs);
            }
        }

        // 5) percentile 변환
        //    - MV 경로: 이미 MV에서 rough_score 순위를 반영하므로, _raw_score 기반으로 재계산한다.
        //    - Fallback 경로: 기존 방식으로 Java에서 전체 후보 정렬 후 계산
        computePercentile(candidates);

        // 6) 최종 점수 = percentile
        for (var row : candidates) {
            row.put("_final_score", row.get("_percentile_score"));
        }

        // 7) 그룹별 선택 (Python select_personas() 동일)
        var selected = selectPersonasByGroup(candidates, p.getSelectedCount(), samplingStrategy);

        // 8) 순위 부여
        for (int i = 0; i < selected.size(); i++) {
            selected.get(i).put("_selection_rank", i + 1);
        }

        // 9) selection_reason 생성
        Long productProfileId = productProfile.getId();
        String productCategory = str(productProfile.getProductCategory());
        for (var row : selected) {
            row.put("_selection_reason", buildSelectionReason(row, productProfileId, productCategory));
        }

        // 10) 저장
        if (resetSelected) {
            saveService.resetSelectedPersonas(orderId);
        }
        Long targetProfileId = productProfileId;
        String selectorVersion = "generic_detail_page:" + scoreModelVersion + ":" + str(productProfile.getProfileVersion());
        saveService.saveSelectedPersonas(orderId, targetProfileId, selected, selectorVersion);

        log.info("[selectPersonas] 완료 selected={}, useMv={}", selected.size(), useMv);
        return toResultList(selected);
    }

    // =========================================================================
    // 3. generateReactions
    // =========================================================================

    /**
     * 선별된 페르소나별 반응 생성. Python generate_reactions_for_detail_page_image.py 동일 로직.
     * batch 처리 지원. response_version으로 반응 격리.
     */
    public void generateReactions(Long orderId, String responseVersion,
                                   int batchSize, boolean skipExisting) {
        generateReactions(orderId, responseVersion, batchSize, skipExisting, () -> false);
    }

    public void generateReactions(Long orderId, String responseVersion,
                                   int batchSize, boolean skipExisting,
                                   BooleanSupplier stopRequested) {
        if (responseVersion == null) responseVersion = "detail_page_reaction_v1";
        if (batchSize <= 0) batchSize = 3;
        var p = PipelineRequest.builder()
                .responseVersion(responseVersion)
                .batchSize(batchSize)
                .skipExisting(skipExisting)
                .modelVersion(reactionModel)
                .temperature(0.4)
                .maxTokens(9000)
                .build();

        int concurrency = Math.max(1, reactionConcurrency);
        log.info("[generateReactions] orderId={}, responseVersion={}, batchSize={}, concurrency={}, skipExisting={}",
                orderId, p.getResponseVersion(), p.getBatchSize(), concurrency, p.isSkipExisting());

        // 1) order 조회
        var order = queryService.findOrderById(orderId);

        // 2) snapshot 조회
        var snapshot = queryService.findLatestSnapshot(orderId, null);

        // 3) product_target_profile 조회
        var targetProfileOpt = queryService.findLatestTargetProfile(orderId);
        if (targetProfileOpt.isEmpty()) {
            throw new RuntimeException("product_target_profile이 없습니다. orderId=" + orderId);
        }
        var productProfile = targetProfileOpt.get();

        // 4) image analyses 조회
        var imageAnalyses = queryService.findImageAnalyses(orderId);
        log.info("[generateReactions] imageAnalyses={}", imageAnalyses.size());

        // 5) 쇼핑 비교 근거 조회
        var shoppingEvidence = queryService.findLatestShoppingEvidence(orderId);
        var shoppingProducts = queryService.findShoppingComparisonProducts(orderId, 12);

        // 6) product payload 구성
        String productPayloadJson = buildProductPayload(
                order, snapshot, productProfile, imageAnalyses, shoppingEvidence, shoppingProducts);

        // 7) selected personas 조회
        List<Map<String, Object>> selectedPersonas = queryService.findSelectedPersonasWithDetails(
                orderId, p.getResponseVersion(), p.isSkipExisting(), null);
        if (selectedPersonas.isEmpty()) {
            log.info("[generateReactions] 생성할 selected persona가 없습니다. orderId={}", orderId);
            return;
        }
        log.info("[generateReactions] selectedPersonas={}", selectedPersonas.size());

        // 8) batch 처리
        Long productTargetProfileId = productProfile.getId();
        List<ReactionBatch> batches = new ArrayList<>();

        for (int i = 0; i < selectedPersonas.size(); i += batchSize) {
            int end = Math.min(i + batchSize, selectedPersonas.size());
            batches.add(new ReactionBatch(batches.size() + 1, List.copyOf(selectedPersonas.subList(i, end))));
        }

        List<ReactionBatchResult> batchResults = runReactionBatches(
                batches, productPayloadJson, orderId, productTargetProfileId, p, concurrency, stopRequested);
        int successCount = batchResults.stream().mapToInt(ReactionBatchResult::successCount).sum();
        int failCount = batchResults.stream().mapToInt(ReactionBatchResult::failCount).sum();

        log.info("[generateReactions] 완료 success={}, failed={}, orderId={}", successCount, failCount, orderId);
        if (successCount == 0 && !selectedPersonas.isEmpty()) {
            throw new RuntimeException("모든 페르소나 반응 생성 실패 (0/" + selectedPersonas.size() + ")");
        }
    }

    private List<ReactionBatchResult> runReactionBatches(
            List<ReactionBatch> batches,
            String productPayloadJson,
            Long orderId,
            Long productTargetProfileId,
            PipelineRequest request,
            int concurrency,
            BooleanSupplier stopRequested) {
        if (batches.isEmpty()) return List.of();
        throwIfStopRequested(stopRequested);
        if (concurrency <= 1 || batches.size() == 1) {
            List<ReactionBatchResult> results = new ArrayList<>();
            for (ReactionBatch batch : batches) {
                throwIfStopRequested(stopRequested);
                results.add(processReactionBatch(batch, productPayloadJson, orderId, productTargetProfileId, request));
                throwIfStopRequested(stopRequested);
            }
            return results;
        }

        int poolSize = Math.min(concurrency, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        ExecutorCompletionService<ReactionBatchResult> completion = new ExecutorCompletionService<>(executor);
        List<Future<ReactionBatchResult>> futures = new ArrayList<>();
        try {
            int nextBatchIndex = 0;
            int submitted = 0;
            int completed = 0;
            while (submitted < poolSize && nextBatchIndex < batches.size()) {
                futures.add(submitReactionBatch(completion, batches.get(nextBatchIndex++),
                        productPayloadJson, orderId, productTargetProfileId, request));
                submitted++;
            }

            List<ReactionBatchResult> results = new ArrayList<>();
            while (completed < submitted) {
                try {
                    results.add(completion.take().get());
                    completed++;
                    throwIfStopRequested(stopRequested);
                    while (submitted - completed < poolSize && nextBatchIndex < batches.size()) {
                        throwIfStopRequested(stopRequested);
                        futures.add(submitReactionBatch(completion, batches.get(nextBatchIndex++),
                                productPayloadJson, orderId, productTargetProfileId, request));
                        submitted++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelFutures(futures);
                    throw new RuntimeException("페르소나 반응 병렬 처리 중 인터럽트", e);
                } catch (ExecutionException e) {
                    cancelFutures(futures);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw cause instanceof RuntimeException re
                            ? re
                            : new RuntimeException("페르소나 반응 병렬 처리 실패", cause);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Future<ReactionBatchResult> submitReactionBatch(
            ExecutorCompletionService<ReactionBatchResult> completion,
            ReactionBatch batch,
            String productPayloadJson,
            Long orderId,
            Long productTargetProfileId,
            PipelineRequest request) {
        return completion.submit(() ->
                processReactionBatch(batch, productPayloadJson, orderId, productTargetProfileId, request));
    }

    private void throwIfStopRequested(BooleanSupplier stopRequested) {
        if (stopRequested != null && stopRequested.getAsBoolean()) {
            throw new PipelineStopRequestedException("사용자 요청으로 리포트 생성이 중지되었습니다.");
        }
    }

    private void cancelFutures(List<Future<ReactionBatchResult>> futures) {
        for (Future<ReactionBatchResult> future : futures) {
            future.cancel(true);
        }
    }

    private ReactionBatchResult processReactionBatch(
            ReactionBatch batch,
            String productPayloadJson,
            Long orderId,
            Long productTargetProfileId,
            PipelineRequest request) {
        List<Map<String, Object>> results = callReactionBatch(productPayloadJson, batch, request);
        if (!results.isEmpty()) {
            saveService.upsertReactions(orderId, productTargetProfileId,
                    request.getResponseVersion(), request.getModelName(), request.getModelVersion(), results);
            log.info("[OK] batch={}, saved={}", batch.batchNo(), results.size());
            return new ReactionBatchResult(batch.batchNo(), results.size(), 0);
        }

        if (batch.personas().size() <= 1) {
            return new ReactionBatchResult(batch.batchNo(), 0, batch.personas().size());
        }

        int successCount = 0;
        int failCount = batch.personas().size();
        for (var persona : batch.personas()) {
            try {
                var singleBatch = new ReactionBatch(batch.batchNo(), List.of(persona));
                var singleResults = callReactionBatch(productPayloadJson, singleBatch, request);
                if (!singleResults.isEmpty()) {
                    saveService.upsertReactions(orderId, productTargetProfileId,
                            request.getResponseVersion(), request.getModelName(), request.getModelVersion(), singleResults);
                    successCount++;
                    failCount--;
                    log.info("[SPLIT_OK] personaProfileId={}", persona.get("persona_profile_id"));
                }
            } catch (Exception e) {
                if (deepSeekService.isTransientFailure(e)) {
                    throw new RuntimeException(
                            "DeepSeek 일시 장애로 반응 생성을 중단합니다. 저장된 반응은 유지되며 재개 시 남은 페르소나부터 이어서 생성합니다. "
                                    + e.getMessage(), e);
                }
                log.warn("[SPLIT_FAIL] persona={}, error={}", persona.get("persona_profile_id"), e.getMessage());
            }
        }
        return new ReactionBatchResult(batch.batchNo(), successCount, failCount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callReactionBatch(
            String productPayloadJson,
            ReactionBatch batch,
            PipelineRequest request) {
        try {
            log.info("[LLM] batch={}, size={}", batch.batchNo(), batch.personas().size());
            String personasJson = objectMapper.writeValueAsString(batch.personas().stream()
                    .map(this::toPersonaPayload)
                    .toList());

            Map<String, Object> rawResult = deepSeekService.callReactions(
                    productPayloadJson, personasJson,
                    request.getModelVersion(), request.getTemperature(), request.getMaxTokens(), request.getThinkingMode());

            if (rawResult.isEmpty()) {
                log.warn("[FAIL] batch={}: DeepSeek 응답 파싱 실패", batch.batchNo());
                return List.of();
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) rawResult.get("results");
            if (results == null || results.isEmpty()) {
                log.warn("[FAIL] batch={}: results 배열 없음", batch.batchNo());
                return List.of();
            }

            results = normalizeResults(results, batch.personas());
            if (results.isEmpty()) {
                log.warn("[FAIL] batch={}: 모든 결과 검증 실패", batch.batchNo());
            }
            return results;
        } catch (Exception e) {
            log.warn("[FAIL] batch={}, error={}", batch.batchNo(), e.getMessage());
            if (deepSeekService.isTransientFailure(e)) {
                throw new RuntimeException(
                        "DeepSeek 일시 장애로 반응 생성을 중단합니다. 저장된 반응은 유지되며 재개 시 남은 페르소나부터 이어서 생성합니다. "
                                + e.getMessage(), e);
            }
            return List.of();
        }
    }

    private record ReactionBatch(int batchNo, List<Map<String, Object>> personas) {}

    private record ReactionBatchResult(int batchNo, int successCount, int failCount) {}

    // =========================================================================
    // 4. generateFinalReport
    // =========================================================================

    /**
     * 최종 리포트 생성. Python generate_final_detail_page_report_image.py 동일 로직.
     * response_version 기준 반응만 집계, report_version으로 upsert.
     */
    public void generateFinalReport(Long orderId, String responseVersion, String reportVersion) {
        if (responseVersion == null) responseVersion = "detail_page_reaction_v1";
        if (reportVersion == null) reportVersion = "detail_page_final_report_v1";
        var p = PipelineRequest.builder()
                .responseVersion(responseVersion)
                .reportVersion(reportVersion)
                .modelVersion(finalReportModel)
                .temperature(0.25)
                .maxTokens(14000)
                .build();

        // 1) order 조회
        var order = queryService.findOrderById(orderId);

        // 2) snapshot 조회
        var snapshot = queryService.findLatestSnapshot(orderId, null);

        // 3) product_target_profile 조회
        var targetProfileOpt = queryService.findLatestTargetProfile(orderId);
        if (targetProfileOpt.isEmpty()) {
            throw new RuntimeException("product_target_profile이 없습니다. orderId=" + orderId);
        }
        var productProfile = targetProfileOpt.get();

        // 4) response_version 기준 반응 조회
        List<Map<String, Object>> responses = queryService.findReactionsWithDetails(
                orderId, p.getResponseVersion());
        if (responses.isEmpty()) {
            throw new RuntimeException("취합할 페르소나 반응이 없습니다. orderId=" + orderId
                    + ", responseVersion=" + p.getResponseVersion());
        }
        int responseCount = responses.size();
        log.info("[generateFinalReport] orderId={}, responseCount={}, responseVersion={}",
                orderId, responseCount, p.getResponseVersion());

        // 5) 출처별 근거 조회
        var imageAnalyses = queryService.findImageAnalyses(orderId);
        var shoppingEvidence = queryService.findLatestShoppingEvidence(orderId);
        var shoppingProducts = queryService.findShoppingComparisonProducts(orderId, 12);

        // 6) 집계 데이터 구성 (Python build_aggregate() 동일)
        Map<String, Object> aggregate = buildAggregate(
                order, snapshot, productProfile, responses, imageAnalyses, shoppingEvidence, shoppingProducts);

        // 7) DeepSeek 호출 (DB 트랜잭션 없음)
        String aggregateJson;
        try {
            aggregateJson = objectMapper.writeValueAsString(aggregate);
        } catch (Exception e) {
            throw new RuntimeException("집계 데이터 직렬화 실패", e);
        }
        Map<String, Object> rawReport = deepSeekService.callFinalReport(
                aggregateJson, p.getModelVersion(), p.getTemperature(), p.getMaxTokens(), p.getThinkingMode());

        if (rawReport.isEmpty()) {
            throw new RuntimeException("최종 리포트 생성 실패: DeepSeek 응답 파싱 오류");
        }

        // 8) 정규화
        Map<String, Object> report = normalizeReport(rawReport, aggregate);

        // 9) 저장 (짧은 write 트랜잭션)
        Long snapshotId = snapshot.isEmpty() ? null : longVal(snapshot.get("id"));
        Long productProfileId = productProfile.getId();
        saveService.upsertFinalReport(orderId, productProfileId, snapshotId,
                p.getReportVersion(), p.getResponseVersion(),
                p.getModelName(), p.getModelVersion(), report, aggregate, rawReport);
        log.info("[generateFinalReport] 완료 orderId={}, responseCount={}", orderId, responseCount);
    }

    // =========================================================================
    // ── Target Profile Payload ─────────────────────────────────────
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String buildTargetProfilePayload(Map<String, Object> order,
                                              Map<String, Object> snapshot,
                                              List<Map<String, Object>> imageAnalyses) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();

            Map<String, Object> orderMap = new LinkedHashMap<>();
            orderMap.put("id", order.get("id"));
            orderMap.put("projectName", str(order.get("project_name")));
            orderMap.put("oneLineDescription", str(order.get("one_line_description")));
            orderMap.put("detailDescription", compact(str(order.get("detail_description")), 4000));
            orderMap.put("targetCustomer", compact(str(order.get("target_customer")), 3000));
            orderMap.put("mainQuestion", compact(str(order.get("main_question")), 3000));
            orderMap.put("priceText", str(order.get("price_text")));
            orderMap.put("shippingPolicyText", str(order.get("shipping_policy_text")));
            orderMap.put("pageUrl", str(order.get("page_url")));
            orderMap.put("targetType", str(order.get("target_type")));
            orderMap.put("reportPerspective", str(order.get("report_perspective")));
            payload.put("order", orderMap);

            if (!snapshot.isEmpty()) {
                Map<String, Object> snapMap = new LinkedHashMap<>();
                snapMap.put("id", snapshot.get("id"));
                snapMap.put("sourceSite", str(snapshot.get("source_site")));
                snapMap.put("pageTitle", str(snapshot.get("page_title")));
                snapMap.put("productTitle", str(snapshot.get("product_title")));
                snapMap.put("priceText", str(snapshot.get("price_text")));
                snapMap.put("extractedTextSummary", compact(str(snapshot.get("extracted_text_summary")), 12000));
                snapMap.put("visibleText", compact(str(snapshot.get("visible_text")), 10000));
                snapMap.put("importantImages", parseJsonList(str(snapshot.get("important_images"))));
                snapMap.put("rawMetaJson", parseJsonMap(str(snapshot.get("raw_meta_json"))));
                payload.put("snapshot", snapMap);
            } else {
                payload.put("snapshot", null);
            }

            payload.put("imageAnalyses", imageAnalyses);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Target profile payload 구성 실패", e);
        }
    }

    // =========================================================================
    // ── Target Profile Normalize ───────────────────────────────────
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeProfile(Map<String, Object> profile,
                                                  Map<String, Object> order,
                                                  Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>(profile);

        // productName fallback
        String pn = str(result.get("productName"));
        if (pn.isEmpty()) {
            pn = !snapshot.isEmpty() ? str(snapshot.get("product_title")) : str(order.get("project_name"));
            result.put("productName", pn);
        }
        if (str(result.get("targetSummary")).isEmpty()) {
            result.put("targetSummary", str(order.get("project_name")));
        }
        if (str(result.get("productCategory")).isEmpty()) result.put("productCategory", "UNKNOWN");
        if (str(result.get("productType")).isEmpty()) result.put("productType", "UNKNOWN");

        // 리스트 필드 보강
        for (String key : List.of("coreKeywords", "exclusionKeywords", "purchaseDrivers", "purchaseBarriers",
                "audienceHypotheses", "comparisonAudiences", "messageAngles", "reportFocusPoints")) {
            Object v = result.get(key);
            if (v == null) result.put(key, List.of());
        }

        // selectionWeights 정규화
        Map<String, Object> weights = (Map<String, Object>) result.getOrDefault("selectionWeights", Map.of());
        Map<String, Object> fixedWeights = new LinkedHashMap<>();
        for (String trait : TRAIT_DB_COLUMNS.keySet()) {
            Object w = weights.get(trait);
            double val = 1.0;
            if (w instanceof Number n) val = clamp(n.doubleValue(), 0.0, 2.0, 1.0);
            fixedWeights.put(trait, val);
        }
        result.put("selectionWeights", fixedWeights);

        // demographicPriors 정규화
        Map<String, Object> priors = (Map<String, Object>) result.getOrDefault("demographicPriors", Map.of());
        Map<String, Object> ageSrc = (Map<String, Object>) priors.getOrDefault("ageGroups", Map.of());
        Map<String, Object> genderSrc = (Map<String, Object>) priors.getOrDefault("genders", Map.of());
        List<String> ageGroups = List.of("10대 이하", "20대", "30대", "40대", "50대", "60대", "70대 이상");
        List<String> genders = List.of("MALE", "FEMALE", "UNKNOWN");
        Map<String, Object> fixedAges = new LinkedHashMap<>();
        for (String a : ageGroups) {
            fixedAges.put(a, clamp(numVal(ageSrc.get(a)), 0.5, 1.5, 1.0));
        }
        Map<String, Object> fixedGenders = new LinkedHashMap<>();
        for (String g : genders) {
            fixedGenders.put(g, clamp(numVal(genderSrc.get(g)), 0.5, 1.5, 1.0));
        }
        result.put("demographicPriors", Map.of("ageGroups", fixedAges, "genders", fixedGenders));

        Map<String, Object> strategySrc = (Map<String, Object>) result.getOrDefault("samplingStrategy", Map.of());
        Map<String, Double> defaultRatios = Map.of(
                "CORE_TARGET", 0.35,
                "ADJACENT_TARGET", 0.20,
                "TRUST_PRICE_SKEPTIC", 0.20,
                "LOW_FIT_CONTROL", 0.15,
                "STRATIFIED_RANDOM", 0.10
        );
        Map<String, Object> fixedStrategy = new LinkedHashMap<>();
        double ratioTotal = 0.0;
        for (String group : GROUP_ORDER) {
            Map<String, Object> item = strategyItem(strategySrc.get(group));
            double ratio = clamp(numVal(item.get("ratio")), 0.0, 1.0, defaultRatios.get(group));
            ratioTotal += ratio;
            fixedStrategy.put(group, new LinkedHashMap<>(Map.of(
                    "ratio", ratio,
                    "description", str(item.get("description")).isEmpty() ? group : str(item.get("description"))
            )));
        }
        if (ratioTotal <= 0.0) {
            fixedStrategy.clear();
            for (String group : GROUP_ORDER) {
                fixedStrategy.put(group, Map.of("ratio", defaultRatios.get(group), "description", group));
            }
        } else {
            for (String group : GROUP_ORDER) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) fixedStrategy.get(group);
                item.put("ratio", Math.round(numVal(item.get("ratio")) / ratioTotal * 10000.0) / 10000.0);
            }
        }
        result.put("samplingStrategy", fixedStrategy);

        // confidence 정규화
        result.put("confidence", clamp(numVal(result.get("confidence")), 0.0, 1.0, 0.7));

        return result;
    }

    // =========================================================================
    // ── Raw Score / Demographic Multiplier / Keyword Score ─────────
    // =========================================================================

    private double computeRawScore(Map<String, Object> row,
                                    Map<String, Object> selectionWeights,
                                    Map<String, Object> demographicPriors,
                                    List<String> coreKeywords,
                                    List<String> exclusionKeywords) {
        double weighted = 0, wsum = 0;
        for (var entry : TRAIT_DB_COLUMNS.entrySet()) {
            String trait = entry.getKey();
            String col = entry.getValue();
            double w = numVal(selectionWeights.get(trait));
            double val = numVal(row.get(col));
            weighted += val * w;
            wsum += w;
        }
        double base = wsum > 0.0001 ? weighted / wsum : 0;
        return base * demographicMultiplier(row, demographicPriors)
                + keywordScore(row, coreKeywords, exclusionKeywords)
                + numVal(row.get("prediction_confidence")) * 2.0;
    }

    private double demographicMultiplier(Map<String, Object> row,
                                          Map<String, Object> demographicPriors) {
        @SuppressWarnings("unchecked")
        Map<String, Object> age = (Map<String, Object>) demographicPriors.getOrDefault("ageGroups", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> gender = (Map<String, Object>) demographicPriors.getOrDefault("genders", Map.of());
        double ageM = numVal(age.get(str(row.get("age_group"))));
        double genderM = numVal(gender.get(str(row.get("gender"))));
        return Math.max(0.75, Math.min(1.25, ageM * 0.6 + genderM * 0.4));
    }

    @SuppressWarnings("unchecked")
    private double keywordScore(Map<String, Object> row,
                                 List<String> coreKeywords,
                                 List<String> exclusionKeywords) {
        if ((coreKeywords == null || coreKeywords.isEmpty())
                && (exclusionKeywords == null || exclusionKeywords.isEmpty())) {
            return 0;
        }
        String source = String.join(" ",
                str(row.get("occupation")),
                str(row.get("education_level")),
                str(row.get("family_type")),
                str(row.get("housing_type")),
                str(row.get("professional_persona")),
                str(row.get("sports_persona")),
                str(row.get("culinary_persona")),
                str(row.get("hobbies_and_interests")),
                str(row.get("skills_and_expertise")),
                str(row.get("travel_persona"))
        ).toLowerCase();
        double hit = 0;
        if (coreKeywords != null) {
            for (String kw : coreKeywords) {
                if (!kw.isEmpty() && source.contains(kw.toLowerCase())) hit += 1.8;
            }
        }
        double bad = 0;
        if (exclusionKeywords != null) {
            for (String kw : exclusionKeywords) {
                if (!kw.isEmpty() && source.contains(kw.toLowerCase())) bad += 2.0;
            }
        }
        return Math.max(-8.0, Math.min(12.0, hit - bad));
    }

    // =========================================================================
    // ── Percentile ─────────────────────────────────────────────────
    // =========================================================================

    private void computePercentile(List<Map<String, Object>> rows) {
        var sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(r -> numVal(r.get("_raw_score"))));
        int n = sorted.size();
        if (n <= 1) {
            for (var r : rows) r.put("_percentile_score", 50.0);
            return;
        }
        for (int i = 0; i < n; i++) {
            double pct = (double) i / (n - 1) * 100;
            sorted.get(i).put("_percentile_score", pct);
        }
    }

    // =========================================================================
    // ── Group Selection ────────────────────────────────────────────
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectPersonasByGroup(
            List<Map<String, Object>> scoredRows, int selectCount,
            Map<String, Object> samplingStrategy) {

        var desc = scoredRows.stream()
                .sorted((a, b) -> Double.compare(numVal(b.get("_raw_score")), numVal(a.get("_raw_score"))))
                .toList();
        var asc = scoredRows.stream()
                .sorted(Comparator.comparingDouble(r -> numVal(r.get("_raw_score"))))
                .toList();
        int n = scoredRows.size();

        var topPool = desc.subList(0, Math.min(n, Math.max(selectCount * 20, (int) (n * 0.20))));
        var adjacentPool = scoredRows.stream()
                .filter(r -> {
                    double pct = numVal(r.get("_percentile_score"));
                    return pct >= 45 && pct <= 85;
                }).toList();
        var skepticSourcePool = scoredRows.stream()
                .filter(r -> {
                    double pct = numVal(r.get("_percentile_score"));
                    return pct >= 35 && pct <= 85;
                })
                .toList();
        if (skepticSourcePool.size() < 100) {
            skepticSourcePool = scoredRows.stream()
                    .filter(r -> {
                        double pct = numVal(r.get("_percentile_score"));
                        return pct >= 25 && pct <= 90;
                    })
                    .toList();
        }
        var skepticPool = skepticSourcePool.stream()
                .sorted((a, b) -> {
                    double sa = numVal(a.get("price_sensitivity_score")) * 0.25
                            + numVal(a.get("trust_sensitivity_score")) * 0.30
                            + numVal(a.get("review_dependency_score")) * 0.25
                            + numVal(a.get("quality_sensitivity_score")) * 0.20
                            + (100 - Math.abs(numVal(a.get("_percentile_score")) - 60)) * 0.15;
                    double sb = numVal(b.get("price_sensitivity_score")) * 0.25
                            + numVal(b.get("trust_sensitivity_score")) * 0.30
                            + numVal(b.get("review_dependency_score")) * 0.25
                            + numVal(b.get("quality_sensitivity_score")) * 0.20
                            + (100 - Math.abs(numVal(b.get("_percentile_score")) - 60)) * 0.15;
                    return Double.compare(sb, sa);
                }).toList();
        var lowFitPool = asc.stream()
                .filter(r -> {
                    double pct = numVal(r.get("_percentile_score"));
                    return pct >= 5 && pct <= 40;
                }).toList();
        var randomPool = new ArrayList<>(scoredRows);
        Collections.shuffle(randomPool, new Random(orderSeed(scoredRows)));

        // Quota 계산
        double defaultCore = 0.35, defaultAdjacent = 0.20, defaultSkeptic = 0.20,
                defaultLowFit = 0.15, defaultRandom = 0.10;
        Map<String, Double> ratios = Map.of(
                "CORE_TARGET", ratioFromStrategy(samplingStrategy, "CORE_TARGET", defaultCore),
                "ADJACENT_TARGET", ratioFromStrategy(samplingStrategy, "ADJACENT_TARGET", defaultAdjacent),
                "TRUST_PRICE_SKEPTIC", ratioFromStrategy(samplingStrategy, "TRUST_PRICE_SKEPTIC", defaultSkeptic),
                "LOW_FIT_CONTROL", ratioFromStrategy(samplingStrategy, "LOW_FIT_CONTROL", defaultLowFit),
                "STRATIFIED_RANDOM", ratioFromStrategy(samplingStrategy, "STRATIFIED_RANDOM", defaultRandom)
        );
        Map<String, Integer> quotas = new LinkedHashMap<>();
        int allocated = 0;
        for (String g : GROUP_ORDER) {
            int q = (int) Math.round(selectCount * ratios.get(g));
            quotas.put(g, q);
            allocated += q;
        }
        quotas.put("CORE_TARGET", quotas.get("CORE_TARGET") + (selectCount - allocated));

        var selected = new ArrayList<Map<String, Object>>();

        // CORE_TARGET
        pickFromPool(topPool, selected, quotas.get("CORE_TARGET"), "CORE_TARGET", selectCount,
                (r, sel) -> numVal(r.get("_percentile_score")) + diversityBonus(r, sel) * 2);
        // ADJACENT_TARGET
        pickFromPool(adjacentPool, selected, quotas.get("ADJACENT_TARGET"), "ADJACENT_TARGET", selectCount,
                (r, sel) -> (100 - Math.abs(numVal(r.get("_percentile_score")) - 65)) + diversityBonus(r, sel) * 3);
        // TRUST_PRICE_SKEPTIC
        pickFromPool(skepticPool, selected, quotas.get("TRUST_PRICE_SKEPTIC"), "TRUST_PRICE_SKEPTIC", selectCount,
                (r, sel) -> numVal(r.get("price_sensitivity_score")) * 0.22
                        + numVal(r.get("trust_sensitivity_score")) * 0.28
                        + numVal(r.get("review_dependency_score")) * 0.25
                        + numVal(r.get("quality_sensitivity_score")) * 0.15
                        + (100 - Math.abs(numVal(r.get("_percentile_score")) - 60)) * 0.20
                        + diversityBonus(r, sel) * 2.0);
        // LOW_FIT_CONTROL
        pickFromPool(lowFitPool, selected, quotas.get("LOW_FIT_CONTROL"), "LOW_FIT_CONTROL", selectCount,
                (r, sel) -> (40 - numVal(r.get("_percentile_score"))) + diversityBonus(r, sel) * 4);
        // STRATIFIED_RANDOM
        Random rnd = new Random(orderSeed(scoredRows));
        pickFromPool(randomPool, selected, quotas.get("STRATIFIED_RANDOM"), "STRATIFIED_RANDOM", selectCount,
                (r, sel) -> rnd.nextDouble() * 100 + diversityBonus(r, sel) * 3);

        // 부족분 채움
        if (selected.size() < selectCount) {
            var usedIds = selected.stream().map(r -> longVal(r.get("persona_profile_id")))
                    .collect(Collectors.toSet());
            for (var row : desc) {
                if (selected.size() >= selectCount) break;
                Long pid = longVal(row.get("persona_profile_id"));
                if (!usedIds.contains(pid)) {
                    var copy = new LinkedHashMap<>(row);
                    copy.put("_selection_group", "CORE_TARGET");
                    copy.put("_diversity_score", diversityBonus(copy, selected));
                    selected.add(copy);
                    usedIds.add(pid);
                }
            }
        }

        // 최종 정렬: 그룹별 + final_score 내림차순
        var groupOrder = new HashMap<String, Integer>();
        for (int i = 0; i < GROUP_ORDER.size(); i++) groupOrder.put(GROUP_ORDER.get(i), i);
        selected.sort((a, b) -> {
            int cmp = Integer.compare(
                    groupOrder.getOrDefault(str(a.get("_selection_group")), 99),
                    groupOrder.getOrDefault(str(b.get("_selection_group")), 99));
            if (cmp != 0) return cmp;
            return Double.compare(numVal(b.get("_final_score")), numVal(a.get("_final_score")));
        });

        return selected.subList(0, Math.min(selectCount, selected.size()));
    }

    private void pickFromPool(List<Map<String, Object>> pool,
                               List<Map<String, Object>> selected,
                               int count, String group,
                               int selectCount,
                               ScoreFunction scoreFn) {
        var usedIds = selected.stream().map(r -> longVal(r.get("persona_profile_id")))
                .collect(Collectors.toSet());
        boolean relaxed = false;
        while (true) {
            int current = (int) selected.stream().filter(r -> group.equals(r.get("_selection_group"))).count();
            if (current >= count) break;

            Map<String, Object> best = null;
            double bestScore = -1e18;
            for (var row : pool) {
                Long pid = longVal(row.get("persona_profile_id"));
                if (usedIds.contains(pid)) continue;
                if (!capacityOk(row, selected, selectCount, relaxed)) continue;
                double score = scoreFn.apply(row, selected);
                if (score > bestScore) {
                    bestScore = score;
                    best = row;
                }
            }
            if (best == null) {
                if (!relaxed) { relaxed = true; continue; }
                else break;
            }
            var copy = new LinkedHashMap<>(best);
            copy.put("_selection_group", group);
            copy.put("_diversity_score", diversityBonus(copy, selected));
            selected.add(copy);
            usedIds.add(longVal(copy.get("persona_profile_id")));
        }
    }

    private boolean capacityOk(Map<String, Object> row, List<Map<String, Object>> selected,
                               int selectCount, boolean relaxed) {
        int n = Math.max(1, selectCount);
        int maxOcc = relaxed ? (int) (n * 0.12) : (int) (n * 0.06);
        int maxAge = relaxed ? (int) (n * 0.45) : (int) (n * 0.30);
        int maxGender = relaxed ? (int) (n * 0.75) : (int) (n * 0.62);
        int maxRegion = relaxed ? (int) (n * 0.70) : (int) (n * 0.50);
        int maxInactive = relaxed ? (int) (n * 0.20) : (int) (n * 0.12);
        maxOcc = Math.max(2, maxOcc);
        maxAge = Math.max(3, maxAge);
        maxGender = Math.max(3, maxGender);
        maxRegion = Math.max(3, maxRegion);
        maxInactive = Math.max(3, maxInactive);

        String occ = str(row.get("occupation"));
        String age = str(row.get("age_group"));
        String gender = str(row.get("gender"));
        String region = str(row.get("region"));

        long occCnt = selected.stream().filter(r -> occ.equals(str(r.get("occupation")))).count();
        long ageCnt = selected.stream().filter(r -> age.equals(str(r.get("age_group")))).count();
        long genderCnt = selected.stream().filter(r -> gender.equals(str(r.get("gender")))).count();
        long regionCnt = selected.stream().filter(r -> region.equals(str(r.get("region")))).count();
        long inactiveCnt = selected.stream().filter(r -> isInactiveOccupation(r.get("occupation"))).count();

        if (occCnt >= maxOcc) return false;
        if (ageCnt >= maxAge) return false;
        if (genderCnt >= maxGender) return false;
        if (regionCnt >= maxRegion) return false;
        if (isInactiveOccupation(row.get("occupation")) && inactiveCnt >= maxInactive) return false;
        return true;
    }

    private boolean isInactiveOccupation(Object occupation) {
        String occ = str(occupation);
        return occ.equals("무직") || occ.contains("구직중") || occ.startsWith("전직 ");
    }

    private double diversityBonus(Map<String, Object> row, List<Map<String, Object>> selected) {
        if (selected.isEmpty()) return 5.0;
        double score = 0;
        for (String dim : List.of("occupation", "age_group", "gender", "region")) {
            String val = str(row.get(dim));
            long cnt = selected.stream().filter(r -> val.equals(str(r.get(dim)))).count();
            score += 1.1 / (1.0 + cnt);
        }
        return score;
    }

    @FunctionalInterface
    private interface ScoreFunction {
        double apply(Map<String, Object> row, List<Map<String, Object>> selected);
    }

    // =========================================================================
    // ── Reaction Result Normalization ──────────────────────────────
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeResults(
            List<Map<String, Object>> results, List<Map<String, Object>> sourcePersonas) {
        Map<Long, Map<String, Object>> sourceByPid = new HashMap<>();
        for (var s : sourcePersonas) {
            sourceByPid.put(longVal(s.get("persona_profile_id")), s);
        }
        var normalized = new ArrayList<Map<String, Object>>();
        var seen = new java.util.HashSet<Long>();

        for (var r : results) {
            Long pid = longVal(r.get("personaProfileId"));
            if (pid == null) continue;
            var src = sourceByPid.get(pid);
            if (src == null) continue;
            if (seen.contains(pid)) continue;
            seen.add(pid);

            var n = new LinkedHashMap<String, Object>();
            n.put("personaProfileId", pid);
            n.put("reactionSelectedPersonaId",
                    longVal(r.get("reactionSelectedPersonaId")) != null
                            ? r.get("reactionSelectedPersonaId") : src.get("selected_persona_id"));
            n.put("selectionGroup", str(r.get("selectionGroup")).isEmpty()
                    ? str(src.get("selection_group")) : str(r.get("selectionGroup")));
            n.put("selectionRank", src.get("selection_rank"));
            n.put("purchaseIntentScore", clampInt(r.get("purchaseIntentScore"), 50));
            n.put("targetFitScore", clampInt(r.get("targetFitScore"), 50));
            n.put("priceAcceptanceScore", clampInt(r.get("priceAcceptanceScore"), 50));
            n.put("trustScore", clampInt(r.get("trustScore"), 50));
            n.put("detailPageClarityScore", clampInt(r.get("detailPageClarityScore"), 50));
            n.put("sentiment", validateSentiment(str(r.get("sentiment"))));
            n.put("decisionStatus", validateDecision(str(r.get("decisionStatus"))));
            n.put("firstImpression", str(r.get("firstImpression")));
            n.put("likelyReaction", str(r.get("likelyReaction")));
            n.put("priceReaction", str(r.get("priceReaction")));
            n.put("trustReviewReaction", str(r.get("trustReviewReaction")));
            n.put("detailPageFeedback", str(r.get("detailPageFeedback")));
            n.put("segmentLabel", str(r.get("segmentLabel")));
            n.put("representativeQuote", str(r.get("representativeQuote")));
            n.put("positivePoints", safeList(r.get("positivePoints")));
            n.put("concerns", safeList(r.get("concerns")));
            n.put("missingInformation", safeList(r.get("missingInformation")));
            n.put("purchaseBarriers", safeList(r.get("purchaseBarriers")));
            n.put("persuasionMessages", safeList(r.get("persuasionMessages")));
            n.put("recommendedDetailPageFixes", safeList(r.get("recommendedDetailPageFixes")));
            n.put("raw", r);
            normalized.add(n);
        }
        return normalized;
    }

    // =========================================================================
    // ── Aggregate Building ─────────────────────────────────────────
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAggregate(Map<String, Object> order,
                                                Map<String, Object> snapshot,
                                                Object productProfile,
                                                List<Map<String, Object>> responses,
                                                List<Map<String, Object>> imageAnalyses,
                                                Map<String, Object> shoppingEvidence,
                                                List<Map<String, Object>> shoppingProducts) {
        int total = responses.size();

        // score distributions
        var purchaseDist = scoreDist(responses, "purchase_intent_score");
        var fitDist = scoreDist(responses, "target_fit_score");
        var priceDist = scoreDist(responses, "price_acceptance_score");
        var trustDist = scoreDist(responses, "trust_score");
        var clarityDist = scoreDist(responses, "detail_page_clarity_score");

        // decisions
        var decisions = new HashMap<String, Long>();
        for (var r : responses) {
            String d = str(r.get("decision_status"));
            decisions.merge(d, 1L, Long::sum);
        }

        // 그룹별
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (var r : responses) {
            String g = str(r.get("selection_group"));
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
        }

        // overall
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("responseCount", total);
        overall.put("avgScores", Map.of(
                "purchaseIntent", avg(responses, "purchase_intent_score"),
                "targetFit", avg(responses, "target_fit_score"),
                "priceAcceptance", avg(responses, "price_acceptance_score"),
                "trust", avg(responses, "trust_score"),
                "detailPageClarity", avg(responses, "detail_page_clarity_score")
        ));
        overall.put("scoreDistributions", Map.of(
                "purchaseIntent", purchaseDist,
                "targetFit", fitDist,
                "priceAcceptance", priceDist,
                "trust", trustDist,
                "detailPageClarity", clarityDist
        ));
        overall.put("decisionStatusCounts", decisions);
        overall.put("decisionRatios", Map.of(
                "BUY", pct(decisions.getOrDefault("BUY", 0L), total),
                "CONSIDER", pct(decisions.getOrDefault("CONSIDER", 0L), total),
                "HESITATE", pct(decisions.getOrDefault("HESITATE", 0L), total),
                "NOT_BUY", pct(decisions.getOrDefault("NOT_BUY", 0L), total)
        ));
        overall.put("sentimentCounts", countBy(responses, "sentiment"));
        overall.put("selectionGroupCounts", countBy(responses, "selection_group"));
        overall.put("ageGroupCounts", countBy(responses, "age_group"));
        overall.put("genderCounts", countBy(responses, "gender"));
        overall.put("topOccupations", topK(responses, "occupation", 20));

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("order", Map.of(
                "id", order.get("id"),
                "projectName", str(order.get("project_name")),
                "oneLineDescription", str(order.get("one_line_description")),
                "detailDescription", compact(str(order.get("detail_description")), 2200),
                "targetCustomer", compact(str(order.get("target_customer")), 1800),
                "mainQuestion", compact(str(order.get("main_question")), 1800),
                "priceText", str(order.get("price_text")),
                "shippingPolicyText", str(order.get("shipping_policy_text")),
                "targetType", str(order.get("target_type")),
                "reportPerspective", str(order.get("report_perspective"))
        ));
        aggregate.put("productTargetProfile", extractProfileData(productProfile));
        aggregate.put("overall", overall);
        aggregate.put("groups", summarizeGroups(groups));
        aggregate.put("globalTopItems", Map.of(
                "positivePoints", topFlattenedItems(responses, "positive_points", 25),
                "concerns", topFlattenedItems(responses, "concerns", 25),
                "missingInformation", topFlattenedItems(responses, "missing_information", 25),
                "purchaseBarriers", topFlattenedItems(responses, "purchase_barriers", 25),
                "recommendedDetailPageFixes", topFlattenedItems(responses, "recommended_detail_page_fixes", 25),
                "persuasionMessages", topFlattenedItems(responses, "persuasion_messages", 25)
        ));
        aggregate.put("sampleResponses", sampleResponses(responses, 8));
        aggregate.put("sourceEvidence", buildSourceEvidence(
                order, snapshot, imageAnalyses, shoppingEvidence, shoppingProducts));

        if (!snapshot.isEmpty()) {
            aggregate.put("snapshot", Map.of(
                    "id", snapshot.get("id"),
                    "sourceSite", str(snapshot.get("source_site")),
                    "snapshotStatus", str(snapshot.get("snapshot_status")),
                    "productTitle", str(snapshot.get("product_title")),
                    "priceText", str(snapshot.get("price_text")),
                    "extractedTextSummary", compact(str(snapshot.get("extracted_text_summary")), 5000),
                    "visibleText", compact(str(snapshot.get("visible_text")), 3000),
                    "importantImages", parseJsonList(str(snapshot.get("important_images"))),
                    "rawMetaJson", parseJsonMap(str(snapshot.get("raw_meta_json")))
            ));
        }

        return aggregate;
    }

    private Map<String, Object> scoreDist(List<Map<String, Object>> rows, String key) {
        var values = rows.stream()
                .mapToDouble(r -> numVal(r.get(key)))
                .filter(v -> !Double.isNaN(v))
                .boxed().toList();
        if (values.isEmpty()) {
            return Map.of("avg", null, "median", null, "min", null, "max", null,
                    "low_0_39", 0, "mid_40_69", 0, "high_70_100", 0);
        }
        var sorted = values.stream().sorted().toList();
        double median = sorted.size() % 2 == 0
                ? (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0
                : sorted.get(sorted.size() / 2);
        return Map.of(
                "avg", Math.round(values.stream().mapToDouble(v -> v).average().orElse(0) * 100.0) / 100.0,
                "median", Math.round(median * 100.0) / 100.0,
                "min", Math.round(sorted.get(0) * 100.0) / 100.0,
                "max", Math.round(sorted.get(sorted.size() - 1) * 100.0) / 100.0,
                "low_0_39", values.stream().filter(v -> v <= 39).count(),
                "mid_40_69", values.stream().filter(v -> v >= 40 && v <= 69).count(),
                "high_70_100", values.stream().filter(v -> v >= 70).count()
        );
    }

    private Map<String, Object> summarizeGroups(Map<String, List<Map<String, Object>>> groups) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            result.put(entry.getKey(), summarizeGroup(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> summarizeGroup(String group, List<Map<String, Object>> rows) {
        int cnt = rows.size();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("selectionGroup", group);
        s.put("count", cnt);
        s.put("avgScores", Map.of(
                "purchaseIntent", avg(rows, "purchase_intent_score"),
                "targetFit", avg(rows, "target_fit_score"),
                "priceAcceptance", avg(rows, "price_acceptance_score"),
                "trust", avg(rows, "trust_score"),
                "detailPageClarity", avg(rows, "detail_page_clarity_score")
        ));
        s.put("decisionStatusCounts", countBy(rows, "decision_status"));
        s.put("sentimentCounts", countBy(rows, "sentiment"));
        s.put("ageGroupCounts", countBy(rows, "age_group"));
        s.put("genderCounts", countBy(rows, "gender"));
        s.put("topOccupations", topK(rows, "occupation", 12));
        s.put("topPositivePoints", topFlattenedItems(rows, "positive_points", 12));
        s.put("topConcerns", topFlattenedItems(rows, "concerns", 12));
        s.put("topMissingInformation", topFlattenedItems(rows, "missing_information", 12));
        s.put("topPurchaseBarriers", topFlattenedItems(rows, "purchase_barriers", 12));
        s.put("topRecommendedFixes", topFlattenedItems(rows, "recommended_detail_page_fixes", 12));
        // quotes
        var quotes = new ArrayList<Map<String, Object>>();
        for (var r : rows.subList(0, Math.min(8, rows.size()))) {
            String q = str(r.get("representative_quote"));
            if (!q.isEmpty()) {
                quotes.add(Map.of(
                        "rank", r.get("selection_rank"),
                        "personaProfileId", r.get("persona_profile_id"),
                        "purchaseIntentScore", r.get("purchase_intent_score"),
                        "decisionStatus", r.get("decision_status"),
                        "quote", q
                ));
            }
        }
        s.put("quotes", quotes);
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sampleResponses(List<Map<String, Object>> rows, int perGroup) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (var r : rows) {
            groups.computeIfAbsent(str(r.get("selection_group")), k -> new ArrayList<>()).add(r);
        }
        var samples = new ArrayList<Map<String, Object>>();
        for (var entry : groups.entrySet()) {
            var grp = entry.getValue();
            grp.sort((a, b) -> Integer.compare(
                    intVal(b.get("purchase_intent_score")), intVal(a.get("purchase_intent_score"))));
            var picked = new ArrayList<Map<String, Object>>();
            if (!grp.isEmpty()) picked.add(grp.get(0));
            if (grp.size() >= 2) picked.add(grp.get(grp.size() - 1));
            if (grp.size() >= 3) picked.add(grp.get(grp.size() / 2));
            for (var r : grp) {
                if (picked.size() >= perGroup) break;
                if (!picked.contains(r)) picked.add(r);
            }
            for (var r : picked.subList(0, Math.min(perGroup, picked.size()))) {
                var sm = new LinkedHashMap<String, Object>();
                sm.put("selectionGroup", r.get("selection_group"));
                sm.put("selectionRank", r.get("selection_rank"));
                sm.put("personaProfileId", r.get("persona_profile_id"));
                sm.put("ageGroup", r.get("age_group"));
                sm.put("gender", r.get("gender"));
                sm.put("occupation", r.get("occupation"));
                sm.put("scores", Map.of(
                        "purchaseIntent", r.get("purchase_intent_score"),
                        "targetFit", r.get("target_fit_score"),
                        "priceAcceptance", r.get("price_acceptance_score"),
                        "trust", r.get("trust_score"),
                        "clarity", r.get("detail_page_clarity_score")
                ));
                sm.put("decisionStatus", r.get("decision_status"));
                sm.put("sentiment", r.get("sentiment"));
                sm.put("segmentLabel", r.get("segment_label"));
                sm.put("representativeQuote", r.get("representative_quote"));
                sm.put("positivePoints", safeListFromDb(r.get("positive_points"), 5));
                sm.put("concerns", safeListFromDb(r.get("concerns"), 5));
                sm.put("missingInformation", safeListFromDb(r.get("missing_information"), 5));
                sm.put("purchaseBarriers", safeListFromDb(r.get("purchase_barriers"), 5));
                sm.put("recommendedDetailPageFixes", safeListFromDb(r.get("recommended_detail_page_fixes"), 5));
                sm.put("firstImpression", compact(str(r.get("first_impression")), 400));
                sm.put("likelyReaction", compact(str(r.get("likely_reaction")), 400));
                sm.put("priceReaction", compact(str(r.get("price_reaction")), 400));
                sm.put("trustReviewReaction", compact(str(r.get("trust_review_reaction")), 400));
                sm.put("detailPageFeedback", compact(str(r.get("detail_page_feedback")), 400));
                samples.add(sm);
            }
        }
        return samples;
    }

    // =========================================================================
    // ── Report Normalize ───────────────────────────────────────────
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeReport(Map<String, Object> report, Map<String, Object> aggregate) {
        Map<String, Object> result = new LinkedHashMap<>(report);

        String verdict = str(result.get("finalVerdict")).toUpperCase();
        if (!List.of("STRONG", "PROMISING", "MIXED", "WEAK", "RISKY").contains(verdict)) {
            verdict = "MIXED";
        }
        result.put("finalVerdict", verdict);

        for (String key : List.of("executiveSummary", "targetValidationSummary", "purchaseIntentSummary",
                "priceSummary", "trustSummary", "detailPageSummary", "segmentSummary",
                "improvementSummary", "riskSummary", "reportMarkdown")) {
            result.put(key, str(result.get(key)));
        }

        // reportMarkdown fallback
        if (str(result.get("reportMarkdown")).isEmpty()) {
            result.put("reportMarkdown", buildFallbackMarkdown(result, aggregate));
        }
        result.put("reportMarkdown", sanitizeUngroundedPriceClaims(str(result.get("reportMarkdown"))));
        String groundingChecklistMarkdown = buildGroundingChecklistMarkdown(aggregate);
        if (!groundingChecklistMarkdown.isBlank()) {
            String reportMarkdown = str(result.get("reportMarkdown"));
            String marker = "<!-- SYSTEM_GROUNDING_CHECKLIST -->";
            if (!reportMarkdown.contains(marker)) {
                String checklistToAppend = reportMarkdown.contains("## 확인된 근거 체크리스트")
                        ? groundingChecklistMarkdown.replaceFirst("## 확인된 근거 체크리스트", "## 시스템 검증 근거 체크리스트")
                        : groundingChecklistMarkdown;
                result.put("reportMarkdown", reportMarkdown + "\n\n" + marker + "\n" + checklistToAppend);
            }
            result.put("groundingChecklistMarkdown", groundingChecklistMarkdown);
        }

        for (String key : List.of("keyMetrics", "priceAnalysis", "trustAnalysis")) {
            Object v = result.get(key);
            if (!(v instanceof Map)) result.put(key, Map.of());
        }
        for (String key : List.of("positiveInsights", "negativeInsights", "segmentAnalysis",
                "detailPageImprovementPriorities", "messageRecommendations")) {
            Object v = result.get(key);
            if (!(v instanceof List)) result.put(key, List.of());
        }

        return result;
    }

    private String buildFallbackMarkdown(Map<String, Object> report, Map<String, Object> aggregate) {
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) aggregate.getOrDefault("overall", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) overall.getOrDefault("avgScores", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> decisions = (Map<String, Object>) overall.getOrDefault("decisionRatios", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) aggregate.getOrDefault("order", Map.of());

        return "# " + str(order.get("projectName")) + " 최종 리포트\n\n"
                + "## 한눈에 보는 결론\n" + str(report.get("executiveSummary")) + "\n\n"
                + "## 핵심 지표\n"
                + "- 응답 수: " + overall.get("responseCount") + "\n"
                + "- 평균 구매 의향: " + scores.get("purchaseIntent") + "\n"
                + "- 평균 타겟 적합도: " + scores.get("targetFit") + "\n"
                + "- 평균 가격 수용도: " + scores.get("priceAcceptance") + "\n"
                + "- 평균 신뢰도: " + scores.get("trust") + "\n"
                + "- BUY 비율: " + decisions.get("BUY") + "%\n"
                + "- CONSIDER 비율: " + decisions.get("CONSIDER") + "%\n"
                + "- HESITATE 비율: " + decisions.get("HESITATE") + "%\n"
                + "- NOT_BUY 비율: " + decisions.get("NOT_BUY") + "%\n\n"
                + "## 개선 방향\n" + str(report.get("improvementSummary"));
    }

    private String sanitizeUngroundedPriceClaims(String markdown) {
        if (markdown.isBlank()) return markdown;
        String sanitized = markdown;
        sanitized = sanitized.replaceAll("정가\\(예:\\s*[^)]+\\)", "정가(캡처 기준 미확인)");
        sanitized = sanitized.replaceAll("정가\\s*[0-9,]+원에서\\s*([0-9]+%\\s*할인된\\s*)?([0-9,]+원)", "정가 미확인, 할인가 $2");
        sanitized = sanitized.replaceAll("정가\\s*[0-9,]+원\\s*대비", "정가 미확인 대비");
        return sanitized;
    }

    private String buildGroundingChecklistMarkdown(Map<String, Object> aggregate) {
        Map<String, Object> order = safeMap(aggregate, "order");
        Map<String, Object> sourceEvidence = safeMap(aggregate, "sourceEvidence");
        Map<String, Object> userInput = safeMap(sourceEvidence, "userInput");
        Map<String, Object> url = safeMap(sourceEvidence, "url");
        Map<String, Object> image = safeMap(sourceEvidence, "image");
        Map<String, Object> shopping = safeMap(sourceEvidence, "shopping");
        List<Object> imageItems = safeList(image.get("items"));

        String shippingPolicyText = str(userInput.get("shippingPolicyText"));
        String imagePriceEvidence = joinEvidence(imageItems, "visiblePrices", 8, 120);
        String orderPriceText = firstNonBlank(str(order.get("priceText")), str(url.get("priceText")));
        String detailDescription = firstNonBlank(str(userInput.get("detailDescription")), str(order.get("detailDescription")));
        String userPriceEvidence = extractUserPriceEvidence(detailDescription, 220);
        String capturedDisplayPrice = normalizeEvidenceField(extractLabeledEvidence(detailDescription, "가격"));
        String capturedDiscount = normalizeEvidenceField(extractLabeledEvidence(detailDescription, "할인"));
        String capturedOptionPrices = normalizeEvidenceField(extractLabeledEvidence(detailDescription, "옵션가"));
        String optionOrRelatedPriceEvidence = !"미확인".equals(capturedOptionPrices)
                ? capturedOptionPrices
                : "";
        optionOrRelatedPriceEvidence = firstNonBlank(optionOrRelatedPriceEvidence, imagePriceEvidence);
        String reviewEvidence = buildReviewEvidence(url, imageItems);
        String shoppingNote = "";
        if (Boolean.TRUE.equals(shopping.get("used"))) {
            shoppingNote = "네이버 쇼핑 비교 데이터는 보조 가격 비교용입니다. 배송비/리뷰/평점 판단에는 사용자 입력과 캡처 이미지 근거를 우선합니다.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 확인된 근거 체크리스트\n\n");
        sb.append("- 배송비 정책(사용자 입력): ")
                .append(shippingPolicyText.isBlank() ? "미입력 - 배송비는 미확인으로만 다뤄야 함" : shippingPolicyText)
                .append("\n");
        sb.append("- 가격/할인 근거: ")
                .append(joinEvidenceParts(
                        orderPriceText.isBlank() ? "" : "사용자 입력 가격=" + orderPriceText,
                        userPriceEvidence.isBlank() ? "" : "사용자 입력 요약=" + userPriceEvidence,
                        imagePriceEvidence.isBlank() ? "" : "캡처 가격 문구(관련/추천 상품 가격 혼재 가능)=" + imagePriceEvidence,
                        "캡처 기준 미확인"))
                .append("\n");
        sb.append("- 표시가(캡처): ")
                .append(capturedDisplayPrice.isBlank() ? "미확인" : capturedDisplayPrice)
                .append("\n");
        if (!capturedDiscount.isBlank()) {
            sb.append("- 할인(캡처): ").append(capturedDiscount).append("\n");
        }
        if (!optionOrRelatedPriceEvidence.isBlank()) {
            sb.append("- 옵션/구성 또는 관련 가격 문구(캡처): ")
                    .append(optionOrRelatedPriceEvidence)
                    .append(" - 표시가와 별도로 옵션/구성/추천상품 가격이 혼재할 수 있으므로 실구매가 확정 근거로 단정하지 않음\n");
        }
        sb.append("- 리뷰/평점: ")
                .append(firstNonBlank(reviewEvidence, "캡처 기준 미확인"))
                .append("\n");
        if (!shoppingNote.isBlank()) {
            sb.append("- 외부 가격 비교 한계: ").append(shoppingNote).append("\n");
        }

        List<String> detailEvidence = buildImageDetailEvidence(imageItems);
        if (!detailEvidence.isEmpty()) {
            sb.append("\n### 상세페이지 이미지 근거\n");
            for (String line : detailEvidence) {
                sb.append("- ").append(line).append("\n");
            }
        }
        List<String> structuredEvidence = buildStructuredImageEvidence(imageItems);
        if (!structuredEvidence.isEmpty()) {
            sb.append("\n### 상세페이지 핵심 정보 목록\n");
            for (String line : structuredEvidence) {
                sb.append("- ").append(line).append("\n");
            }
        }
        sb.append("\n### 근거 해석 원칙\n");
        sb.append("- 위 항목은 캡처 이미지와 사용자 입력에서 확인된 사실이며, 개선 제안과 구분해야 합니다.\n");
        sb.append("- 캡처나 입력값에 없는 할인율, 리뷰 수, 평점, 구성품, 원산지, 용량, 소재, 기능, 주의사항은 사실처럼 단정하지 않습니다.");
        return sb.toString();
    }

    private String buildReviewEvidence(Map<String, Object> url, List<Object> imageItems) {
        List<String> evidence = new ArrayList<>();
        String reviewCount = str(url.get("reviewCount"));
        if (!reviewCount.isBlank() && !"null".equalsIgnoreCase(reviewCount)) {
            evidence.add("URL 추출 리뷰 수 " + reviewCount);
        }
        String ratingScore = str(url.get("ratingScore"));
        if (!ratingScore.isBlank() && !"null".equalsIgnoreCase(ratingScore)) {
            evidence.add("URL 추출 평점 " + ratingScore);
        }
        for (Object item : imageItems) {
            Map<String, Object> row = safeMap(item);
            String visibleText = str(row.get("visibleText"));
            String summary = str(row.get("imageSummary"));
            String combined = firstNonBlank(visibleText, summary);
            if (containsAny(combined, List.of("리뷰", "후기", "상품평", "평점", "별점", "만족도", "긍정"))) {
                evidence.add(compact(combined, 160));
            }
            if (evidence.size() >= 5) break;
        }
        return evidence.stream().filter(s -> !s.isBlank()).distinct().collect(Collectors.joining(" / "));
    }

    private List<String> buildImageDetailEvidence(List<Object> imageItems) {
        List<String> evidence = new ArrayList<>();
        int index = 1;
        for (Object item : imageItems) {
            Map<String, Object> row = safeMap(item);
            List<String> parts = new ArrayList<>();
            addPart(parts, "요약", row.get("imageSummary"), 180);
            addPart(parts, "보이는 문구", row.get("visibleText"), 180);
            String specSnippets = extractKeywordSnippets(
                    firstNonBlank(str(row.get("visibleSpecSnippets")), String.join(" / ", List.of(
                            evidenceText(row.get("visibleText")),
                            evidenceText(row.get("visibleClaims")),
                            evidenceText(row.get("visibleUsageInstructions")),
                            evidenceText(row.get("safetyOrComplianceNotes"))))),
                    List.of("재질", "소재", "성분", "용량", "중량", "사이즈", "크기", "규격",
                            "원산지", "제조국", "Made in", "제조원", "수입", "제품명", "색상",
                            "구성품", "구성", "주의", "Notice", "사용 시", "사용방법", "보관", "반품", "교환"),
                    18,
                    120);
            if (!specSnippets.isBlank()) {
                parts.add("상세 스펙/주의: " + specSnippets);
            }
            addPart(parts, "가격", row.get("visiblePrices"), 160);
            addPart(parts, "핵심 주장", row.get("visibleClaims"), 180);
            addPart(parts, "인증/신뢰", row.get("visibleCertifications"), 160);
            addPart(parts, "사용/주의", row.get("visibleUsageInstructions"), 160);
            addPart(parts, "구매 동인", row.get("visualPurchaseDrivers"), 160);
            addPart(parts, "구매 저항", row.get("visualPurchaseBarriers"), 160);
            addPart(parts, "안전/준수", row.get("safetyOrComplianceNotes"), 160);
            if (!parts.isEmpty()) {
                evidence.add("이미지 " + index + ": " + String.join(" / ", parts));
            }
            index++;
        }
        return evidence;
    }

    private List<String> buildStructuredImageEvidence(List<Object> imageItems) {
        List<String> evidence = new ArrayList<>();
        List<String> functions = collectImageEvidence(
                imageItems,
                List.of("visibleClaims", "visibleText"),
                List.of("PD 3.0", "초고속 충전", "PD 25W", "고속 충전", "GaN", "C타입", "호환"),
                12,
                220);
        List<String> specs = collectImageEvidence(
                imageItems,
                List.of("visibleText", "visibleClaims"),
                List.of("구성품", "재질", "소재", "사이즈", "크기", "제조국", "Made in", "KC 인증", "모델명"),
                12,
                220);
        List<String> cautions = collectImageEvidence(
                imageItems,
                List.of("visibleUsageInstructions", "safetyOrComplianceNotes", "visibleText", "informationGaps"),
                List.of("주의", "NOTICE", "본 제품", "케이블", "충전 속도", "표시 안", "상이", "보관", "반품", "교환"),
                12,
                260);

        if (!functions.isEmpty()) {
            evidence.add("기능/핵심 주장: " + String.join(" / ", functions));
        }
        if (!specs.isEmpty()) {
            evidence.add("구성/스펙/원산지/소재: " + String.join(" / ", specs));
        }
        if (!cautions.isEmpty()) {
            evidence.add("사용/주의사항: " + String.join(" / ", cautions));
        }
        return evidence;
    }

    private List<String> collectImageEvidence(List<Object> imageItems, List<String> fields,
                                              List<String> keywords, int limit, int window) {
        List<String> snippets = new ArrayList<>();
        for (Object item : imageItems) {
            Map<String, Object> row = safeMap(item);
            for (String field : fields) {
                String text = evidenceText(row.get(field));
                if (text.isBlank()) {
                    continue;
                }
                String extracted = extractKeywordSnippets(text, keywords, 2, window);
                if (!extracted.isBlank()) {
                    snippets.addAll(Arrays.stream(extracted.split("\\s*/\\s*"))
                            .map(String::trim)
                            .filter(value -> !value.isBlank())
                            .toList());
                }
                if (snippets.size() >= limit) {
                    return snippets.stream().distinct().limit(limit).toList();
                }
            }
        }
        return snippets.stream().distinct().limit(limit).toList();
    }

    private void addPart(List<String> parts, String label, Object value, int maxLen) {
        String text = evidenceText(value);
        if (!text.isBlank()) {
            parts.add(label + ": " + compact(text, maxLen));
        }
    }

    private String joinEvidence(List<Object> items, String key, int limit, int maxLen) {
        List<String> values = new ArrayList<>();
        for (Object item : items) {
            String text = evidenceText(safeMap(item).get(key));
            if (!text.isBlank()) {
                values.add(compact(text, maxLen));
            }
            if (values.size() >= limit) break;
        }
        return values.stream().distinct().collect(Collectors.joining(" / "));
    }

    private String joinEvidenceParts(String... values) {
        if (values == null || values.length == 0) return "";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < values.length - 1; i++) {
            String value = str(values[i]);
            if (!value.isBlank()) parts.add(value);
        }
        if (!parts.isEmpty()) return parts.stream().distinct().collect(Collectors.joining(" / "));
        return str(values[values.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private String evidenceText(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return list.stream().map(this::evidenceText).filter(s -> !s.isBlank())
                    .collect(Collectors.joining(", "));
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().map(this::evidenceText).filter(s -> !s.isBlank())
                    .collect(Collectors.joining(", "));
        }
        String text = str(value);
        if (text.isBlank() || "[]".equals(text) || "{}".equals(text) || "null".equalsIgnoreCase(text)) {
            return "";
        }
        if ((text.startsWith("[") && text.endsWith("]")) || (text.startsWith("{") && text.endsWith("}"))) {
            try {
                Object parsed = objectMapper.readValue(text, Object.class);
                if (parsed instanceof List<?> || parsed instanceof Map<?, ?>) {
                    return evidenceText(parsed);
                }
            } catch (Exception ignored) {}
        }
        return text;
    }

    private String extractKeywordSnippets(String text, List<String> keywords, int limit, int window) {
        String source = str(text);
        if (source.isBlank()) return "";
        List<String> snippets = new ArrayList<>();
        for (String keyword : keywords) {
            int idx = source.indexOf(keyword);
            if (idx < 0) continue;
            int start = Math.max(0, idx - Math.max(0, window / 3));
            int end = Math.min(source.length(), idx + keyword.length() + window);
            String snippet = source.substring(start, end)
                    .replaceAll("\\s+", " ")
                    .replaceAll("^\\s*[,/|:：-]+\\s*", "")
                    .replaceAll("\\s*[,/|:：-]+\\s*$", "")
                    .trim();
            if (!snippet.isBlank()) {
                snippets.add(snippet);
            }
            if (snippets.size() >= limit) break;
        }
        return snippets.stream()
                .distinct()
                .limit(limit)
                .collect(Collectors.joining(" / "));
    }

    private String extractUserPriceEvidence(String text, int maxLen) {
        String source = str(text).replaceAll("\\s+", " ").trim();
        if (source.isBlank()) return "";
        for (String sentence : source.split("(?<=[.!?。다])\\s+")) {
            String normalized = sentence.trim();
            if (normalized.isBlank()) continue;
            if ((normalized.contains("가격") || normalized.contains("할인") || normalized.contains("할인가") || normalized.contains("%"))
                    && normalized.contains("원")) {
                return compact(normalized, maxLen);
            }
        }
        return compact(extractKeywordSnippets(source, List.of("가격", "할인", "할인가", "%", "원"), 1, maxLen), maxLen);
    }

    private String extractLabeledEvidence(String text, String label) {
        String source = str(text).replaceAll("\\s+", " ").trim();
        if (source.isBlank() || label == null || label.isBlank()) return "";
        Pattern pattern = Pattern.compile("(?:^|[/\\n])\\s*" + Pattern.quote(label) + "\\s*=\\s*([^/\\n]+)");
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return "";
        return compact(matcher.group(1), 260);
    }

    private String normalizeEvidenceField(String value) {
        String normalized = str(value)
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*/\\s*$", "")
                .trim();
        if (normalized.isBlank()
                || "null".equalsIgnoreCase(normalized)
                || "없음".equals(normalized)
                || "미입력".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private boolean containsAny(String text, List<String> terms) {
        String source = str(text);
        if (source.isBlank()) return false;
        return terms.stream().anyMatch(source::contains);
    }

    // =========================================================================
    // ── Reaction / Product Payload Builder ─────────────────────────
    // =========================================================================

    private String buildProductPayload(Map<String, Object> order,
                                        Map<String, Object> snapshot,
                                        Object productProfile,
                                        List<Map<String, Object>> imageAnalyses,
                                        Map<String, Object> shoppingEvidence,
                                        List<Map<String, Object>> shoppingProducts) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();

            var orderMap = new LinkedHashMap<String, Object>();
            orderMap.put("id", order.get("id"));
            orderMap.put("projectName", str(order.get("project_name")));
            orderMap.put("oneLineDescription", str(order.get("one_line_description")));
            orderMap.put("detailDescription", compact(str(order.get("detail_description")), 2500));
            orderMap.put("targetCustomer", compact(str(order.get("target_customer")), 2000));
            orderMap.put("mainQuestion", compact(str(order.get("main_question")), 2000));
            orderMap.put("priceText", str(order.get("price_text")));
            orderMap.put("shippingPolicyText", str(order.get("shipping_policy_text")));
            orderMap.put("targetType", str(order.get("target_type")));
            orderMap.put("reportPerspective", str(order.get("report_perspective")));
            payload.put("order", orderMap);

            if (!snapshot.isEmpty()) {
                var snapshotMap = new LinkedHashMap<String, Object>();
                snapshotMap.put("id", snapshot.get("id"));
                snapshotMap.put("sourceSite", str(snapshot.get("source_site")));
                snapshotMap.put("snapshotStatus", str(snapshot.get("snapshot_status")));
                snapshotMap.put("productTitle", str(snapshot.get("product_title")));
                snapshotMap.put("priceText", str(snapshot.get("price_text")));
                snapshotMap.put("extractedTextSummary", compact(str(snapshot.get("extracted_text_summary")), 9000));
                snapshotMap.put("visibleText", compact(str(snapshot.get("visible_text")), 6000));
                snapshotMap.put("importantImages", parseJsonList(str(snapshot.get("important_images"))));
                snapshotMap.put("rawMetaJson", parseJsonMap(str(snapshot.get("raw_meta_json"))));
                payload.put("snapshot", snapshotMap);
            } else {
                payload.put("snapshot", null);
            }

            payload.put("imageAnalyses", imageAnalyses != null ? imageAnalyses : List.of());
            payload.put("sourceEvidence", buildSourceEvidence(
                    order, snapshot, imageAnalyses, shoppingEvidence, shoppingProducts));

            // productTargetProfile
            payload.put("productTargetProfile", extractProfileData(productProfile));

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Product payload 구성 실패", e);
        }
    }

    private Map<String, Object> buildSourceEvidence(Map<String, Object> order,
                                                     Map<String, Object> snapshot,
                                                     List<Map<String, Object>> imageAnalyses,
                                                     Map<String, Object> shoppingEvidence,
                                                     List<Map<String, Object>> shoppingProducts) {
        Map<String, Object> evidence = new LinkedHashMap<>();

        Map<String, Object> rawMeta = !snapshot.isEmpty()
                ? parseJsonMap(str(snapshot.get("raw_meta_json")))
                : Map.of();
        String snapshotStatus = str(snapshot.get("snapshot_status")).toUpperCase();
        boolean captured = "CAPTURED".equals(snapshotStatus) || "SUCCESS".equals(snapshotStatus);
        boolean screenshotPrimary = "SCREENSHOT_PRIMARY".equals(snapshotStatus);

        Map<String, Object> userInput = new LinkedHashMap<>();
        String shippingPolicyText = str(order.get("shipping_policy_text"));
        userInput.put("priceText", str(order.get("price_text")));
        userInput.put("detailDescription", compact(str(order.get("detail_description")), 1200));
        userInput.put("shippingPolicyText", shippingPolicyText);
        userInput.put("shippingPolicySource", shippingPolicyText.isBlank() ? "미입력" : "사용자 직접 입력");
        userInput.put("shippingPolicyRule", shippingPolicyText.isBlank()
                ? "배송비 정책 입력값이 없으면 배송비를 미확인으로 표시하고 네이버 쇼핑 결과만으로 보완하지 않습니다."
                : "배송비 정책은 사용자가 직접 입력한 값이므로 배송비 판단의 최우선 근거입니다. 조건부/멤버십 무료배송은 조건을 그대로 남기고 일반 무료배송처럼 단정하지 않습니다.");
        evidence.put("userInput", userInput);

        Map<String, Object> url = new LinkedHashMap<>();
        url.put("crawlStatus", snapshot.isEmpty() ? "MISSING" : snapshotStatus);
        url.put("crawlSucceeded", captured);
        url.put("screenshotPrimary", screenshotPrimary);
        url.put("failureReason", str(rawMeta.get("crawlError")).isBlank()
                ? str(rawMeta.get("fallbackReason")) : str(rawMeta.get("crawlError")));
        url.put("priceText", str(snapshot.get("price_text")));
        url.put("reviewCount", rawMeta.get("reviewCount"));
        url.put("ratingScore", rawMeta.get("ratingScore") != null
                ? rawMeta.get("ratingScore") : rawMeta.get("satisfactionScore"));
        url.put("deliveryType", rawMeta.get("deliveryType"));
        url.put("shippingAmount", rawMeta.get("product_shipping_amount"));
        url.put("note", captured
                ? "URL 직접 추출 데이터입니다. 가격, 후기, 평점은 객관 근거로 사용하되 배송비는 사용자 직접 입력값이 있으면 그 값을 우선합니다."
                : screenshotPrimary
                ? "업로드된 상세페이지 전체 캡처 이미지를 1차 근거로 사용합니다. URL 데이터가 아니라 이미지 OCR/시각 분석 데이터로 판단하십시오."
                : "URL 직접 추출이 실패했거나 확인되지 않았습니다. 후기/평점 부재를 사실로 단정하지 마십시오.");
        evidence.put("url", url);

        Map<String, Object> priceRules = new LinkedHashMap<>();
        priceRules.put("priceTrustSeparation", "원산지/수입육 신뢰도는 trustScore에서 다루고 priceAcceptanceScore를 직접 낮추는 근거로 쓰지 않습니다.");
        priceRules.put("shippingRule", "가격 경쟁력은 배송비 포함 실구매가를 우선합니다. sourceEvidence.userInput.shippingPolicyText가 있으면 이 값을 배송비 판단의 최우선 근거로 사용하고, 조건부 무료배송/멤버십 무료배송은 조건을 명시한 채 불확실성을 유지합니다.");
        priceRules.put("claimGroundingRule", "캡처 이미지, 사용자 입력, URL, 네이버 쇼핑, LLM 추론을 구분합니다. 근거에 없는 할인율, 리뷰 수, 평점, 원산지, 구성품, 효능, 인증, 배송 조건은 단정하지 않습니다.");
        priceRules.put("reviewRule", "reviewCount/ratingScore가 없더라도 URL 크롤링 실패라면 '후기 부재'라고 쓰지 말고 '후기 확인 실패/미확인'으로 표현합니다.");
        priceRules.put("freshFoodReturnRule", "냉동/신선식품의 단순 변심 반품 제한은 일반적인 카테고리 조건으로 다루고 과도한 리스크로 강조하지 않습니다.");
        evidence.put("evaluationRules", priceRules);

        Map<String, Object> shopping = new LinkedHashMap<>();
        Map<String, Object> priceAnalysis = parseJsonMap(str(shoppingEvidence.get("analysis_price_analysis_json")));
        Map<String, Object> reportContext = parseJsonMap(str(shoppingEvidence.get("analysis_report_context_json")));
        shopping.put("used", !shoppingEvidence.isEmpty());
        shopping.put("candidateCount", shoppingEvidence.get("candidate_count"));
        shopping.put("priceAnalysis", priceAnalysis);
        shopping.put("reportContext", reportContext);
        shopping.put("comparisonProducts", summarizeShoppingProducts(shoppingProducts, 12));
        shopping.put("shippingDataAvailable", false);
        shopping.put("shippingDataNote", "현재 네이버 쇼핑 저장 스키마에는 배송비, 무료배송 여부, 조건부 무료배송 기준, 리뷰 수, 평점이 없습니다.");
        shopping.put("usageRule", "네이버 쇼핑은 보조 가격 근거입니다. 원산지/부위/중량/냉장·냉동/배송비 조건이 다르면 가격 점수에 강하게 반영하지 않습니다.");
        evidence.put("shopping", shopping);

        Map<String, Object> image = new LinkedHashMap<>();
        image.put("count", imageAnalyses != null ? imageAnalyses.size() : 0);
        image.put("visibleOnlyRule", "이미지 근거는 OCR/시각 분석으로 실제 보이는 정보만 사용합니다. URL 상세 본문, 리뷰, 평점, 배송비와 섞지 않습니다.");
        image.put("items", summarizeImageAnalyses(imageAnalyses, 20));
        if (imageAnalyses != null && imageAnalyses.size() > 20) {
            image.put("truncationNote", "이미지 분석이 20개를 초과해 요약 목록은 20개까지만 포함합니다. 전체 imageAnalyses 배열의 원문도 함께 참고해야 합니다.");
        }
        evidence.put("image", image);

        List<String> sourcePriority = new ArrayList<>();
        sourcePriority.add("USER_INPUT_SHIPPING_POLICY_FOR_SHIPPING");
        if (screenshotPrimary) {
            sourcePriority.add("IMAGE_OCR_OR_VISION_DATA");
            sourcePriority.add("URL_EXTRACTED_OBJECTIVE_DATA");
        } else {
            sourcePriority.add("URL_EXTRACTED_OBJECTIVE_DATA");
            sourcePriority.add("IMAGE_OCR_OR_VISION_DATA");
        }
        sourcePriority.add("NAVER_SHOPPING_COMPARISON_DATA");
        sourcePriority.add("LLM_INFERENCE");
        evidence.put("sourcePriority", sourcePriority);
        return evidence;
    }

    private List<Map<String, Object>> summarizeShoppingProducts(List<Map<String, Object>> products, int limit) {
        if (products == null || products.isEmpty()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> product : products) {
            if (rows.size() >= limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", compact(firstNonBlank(str(product.get("title_clean")), str(product.get("title_raw"))), 180));
            row.put("mallName", str(product.get("mall_name")));
            row.put("price", product.get("lprice"));
            row.put("category", List.of(str(product.get("category1")), str(product.get("category2")),
                    str(product.get("category3")), str(product.get("category4"))).stream()
                    .filter(s -> !s.isBlank()).collect(Collectors.joining(">")));
            row.put("candidateScore", product.get("candidate_score"));
            row.put("roles", str(product.get("roles")));
            row.put("shippingFee", null);
            row.put("reviewCount", null);
            row.put("ratingScore", null);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> summarizeImageAnalyses(List<Map<String, Object>> imageAnalyses, int limit) {
        if (imageAnalyses == null || imageAnalyses.isEmpty()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> image : imageAnalyses) {
            if (rows.size() >= limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("imagePath", str(image.get("imagePath")));
            row.put("imageSummary", compact(str(image.get("imageSummary")), 280));
            row.put("visibleText", compact(str(image.get("visibleText")), 280));
            row.put("visibleClaims", image.get("visibleClaims"));
            row.put("visiblePrices", image.get("visiblePrices"));
            row.put("visibleCertifications", image.get("visibleCertifications"));
            row.put("visibleUsageInstructions", image.get("visibleUsageInstructions"));
            row.put("visibleSpecSnippets", extractKeywordSnippets(
                    String.join(" / ", List.of(
                            str(image.get("visibleText")),
                            evidenceText(image.get("visibleClaims")),
                            evidenceText(image.get("visibleUsageInstructions")),
                            evidenceText(image.get("safetyOrComplianceNotes")))),
                    List.of("재질", "소재", "성분", "용량", "중량", "사이즈", "크기", "규격",
                            "원산지", "제조국", "Made in", "제조원", "수입", "제품명", "색상",
                            "구성품", "구성", "주의", "Notice", "사용 시", "사용방법", "보관", "반품", "교환"),
                    18,
                    120));
            row.put("visualTrustElements", image.get("visualTrustElements"));
            row.put("visualPurchaseDrivers", image.get("visualPurchaseDrivers"));
            row.put("visualPurchaseBarriers", image.get("visualPurchaseBarriers"));
            row.put("safetyOrComplianceNotes", image.get("safetyOrComplianceNotes"));
            row.put("informationGaps", image.get("informationGaps"));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> extractProfileData(Object productProfile) {
        if (productProfile instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) productProfile;
            return m;
        }
        // entity case via reflection
        try {
            var cls = productProfile.getClass();
            var m = new LinkedHashMap<String, Object>();
            for (String key : List.of("id", "productCategory", "productType", "productName", "targetSummary")) {
                try {
                    var f = cls.getDeclaredField(key);
                    f.setAccessible(true);
                    m.put(key, f.get(productProfile));
                } catch (Exception ignored) {}
            }
            putJsonListField(cls, productProfile, m, "coreKeywords");
            putJsonListField(cls, productProfile, m, "exclusionKeywords");
            putJsonListField(cls, productProfile, m, "purchaseDrivers");
            putJsonListField(cls, productProfile, m, "purchaseBarriers");
            putJsonListField(cls, productProfile, m, "audienceHypotheses");
            putJsonListField(cls, productProfile, m, "comparisonAudiences");
            putJsonMapField(cls, productProfile, m, "selectionWeights");
            putJsonMapField(cls, productProfile, m, "demographicPriors");
            putJsonMapField(cls, productProfile, m, "samplingStrategy");
            putJsonListField(cls, productProfile, m, "messageAngles");
            putJsonListField(cls, productProfile, m, "reportFocusPoints");
            putJsonMapField(cls, productProfile, m, "rawProfile");
            try {
                var f = cls.getDeclaredField("confidence");
                f.setAccessible(true);
                m.put("confidence", f.get(productProfile));
            } catch (Exception ignored) {}
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> toPersonaPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reactionSelectedPersonaId", row.get("selected_persona_id"));
        payload.put("personaProfileId", row.get("persona_profile_id"));
        payload.put("selectionRank", row.get("selection_rank"));
        payload.put("selectionGroup", row.get("selection_group"));
        payload.put("selectorFinalScore", numVal(row.get("final_score")));
        payload.put("selectorReason", str(row.get("selection_reason")));

        Map<String, Object> demographics = new LinkedHashMap<>();
        demographics.put("age", row.get("age"));
        demographics.put("ageGroup", str(row.get("age_group")));
        demographics.put("gender", str(row.get("gender")));
        demographics.put("region", str(row.get("region")));
        demographics.put("province", str(row.get("province")));
        demographics.put("district", str(row.get("district")));
        payload.put("demographics", demographics);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("occupation", str(row.get("occupation")));
        profile.put("educationLevel", str(row.get("education_level")));
        profile.put("familyType", str(row.get("family_type")));
        profile.put("housingType", str(row.get("housing_type")));
        payload.put("profile", profile);
        payload.put("sourcePersona", Map.ofEntries(
                Map.entry("professionalPersona", compact(str(row.get("professional_persona")), 900)),
                Map.entry("sportsPersona", compact(str(row.get("sports_persona")), 900)),
                Map.entry("artsPersona", compact(str(row.get("arts_persona")), 600)),
                Map.entry("travelPersona", compact(str(row.get("travel_persona")), 600)),
                Map.entry("culinaryPersona", compact(str(row.get("culinary_persona")), 600)),
                Map.entry("familyPersona", compact(str(row.get("family_persona")), 600)),
                Map.entry("hobbiesAndInterests", compact(str(row.get("hobbies_and_interests")), 900)),
                Map.entry("skillsAndExpertise", compact(str(row.get("skills_and_expertise")), 900)),
                Map.entry("personalValues", compact(str(row.get("personal_values")), 600)),
                Map.entry("lifestyle", compact(str(row.get("lifestyle")), 600)),
                Map.entry("shoppingPersona", compact(str(row.get("shopping_persona")), 900)),
                Map.entry("mediaConsumption", compact(str(row.get("media_consumption")), 600)),
                Map.entry("persona", compact(str(row.get("persona")), 900)),
                Map.entry("culturalBackground", compact(str(row.get("cultural_background")), 600)),
                Map.entry("careerGoalsAndAmbitions", compact(str(row.get("career_goals_and_ambitions")), 600))
        ));
        Map<String, Object> traitScores = new LinkedHashMap<>();
        traitScores.put("digitalAffinityScore", row.get("digital_affinity_score"));
        traitScores.put("priceSensitivityScore", row.get("price_sensitivity_score"));
        traitScores.put("trustSensitivityScore", row.get("trust_sensitivity_score"));
        traitScores.put("convenienceNeedScore", row.get("convenience_need_score"));
        traitScores.put("qualitySensitivityScore", row.get("quality_sensitivity_score"));
        traitScores.put("noveltyAcceptanceScore", row.get("novelty_acceptance_score"));
        traitScores.put("localAffinityScore", row.get("local_affinity_score"));
        traitScores.put("familyDecisionScore", row.get("family_decision_score"));
        traitScores.put("healthSafetySensitivityScore", row.get("health_safety_sensitivity_score"));
        traitScores.put("reviewDependencyScore", row.get("review_dependency_score"));
        payload.put("traitScores", traitScores);
        return payload;
    }

    // =========================================================================
    // ── Helpers ────────────────────────────────────────────────────
    // =========================================================================

    private String buildSelectionReason(Map<String, Object> row, Long productProfileId, String productCategory) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "engine", "generic_detail_page_selector_v1",
                    "productProfileId", productProfileId,
                    "productCategory", productCategory,
                    "rawScore", numVal(row.get("_raw_score")),
                    "percentileScore", numVal(row.get("_percentile_score")),
                    "keywordScore", numVal(row.get("_keyword_score")),
                    "demographicMultiplier", numVal(row.get("_demo_multiplier")),
                    "traits", Map.of(
                            "digital", row.get("digital_affinity_score"),
                            "price", row.get("price_sensitivity_score"),
                            "trust", row.get("trust_sensitivity_score"),
                            "convenience", row.get("convenience_need_score"),
                            "quality", row.get("quality_sensitivity_score"),
                            "novelty", row.get("novelty_acceptance_score"),
                            "local", row.get("local_affinity_score"),
                            "family", row.get("family_decision_score"),
                            "health", row.get("health_safety_sensitivity_score"),
                            "review", row.get("review_dependency_score")
                    )
            ));
        } catch (Exception e) {
            return "{\"engine\":\"generic_detail_page_selector_v1\"}";
        }
    }

    private List<Map<String, Object>> toResultList(List<Map<String, Object>> selected) {
        return selected.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("persona_profile_id"));
            m.put("age_group", row.get("age_group"));
            m.put("gender", row.get("gender"));
            m.put("region", row.get("region"));
            m.put("occupation", row.get("occupation"));
            m.put("persona_summary", "");
            m.put("interests", "");
            m.put("selected_persona_id", row.get("selected_persona_id"));
            m.put("selection_group", row.get("_selection_group"));
            m.put("fit_score", numVal(row.get("_raw_score")));
            return m;
        }).toList();
    }

    private void putJsonListField(Class<?> cls, Object source, Map<String, Object> target, String fieldName) {
        try {
            var f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            target.put(fieldName, parseJsonArray(str(f.get(source))));
        } catch (Exception ignored) {}
    }

    private void putJsonMapField(Class<?> cls, Object source, Map<String, Object> target, String fieldName) {
        try {
            var f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            target.put(fieldName, parseJsonMap(str(f.get(source))));
        } catch (Exception ignored) {}
    }

    // ── math helpers ──────────────────────────────────────────────

    private long orderSeed(List<Map<String, Object>> rows) {
        return 42L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> strategyItem(Object item) {
        if (item instanceof Map) return (Map<String, Object>) item;
        return Map.of();
    }

    private double ratioFromStrategy(Map<String, Object> strategy, String group, double defaultVal) {
        if (strategy == null) return defaultVal;
        Object g = strategy.get(group);
        if (g instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) g;
            Object r = m.get("ratio");
            if (r instanceof Number n) return n.doubleValue();
        }
        return defaultVal;
    }

    private String str(Object o) { return o != null ? o.toString().trim() : ""; }
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!str(value).isBlank()) return str(value);
        }
        return "";
    }
    private Long longVal(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) try { return Long.parseLong(s); } catch (Exception e) { return null; }
        return null;
    }
    private Integer intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s.replaceAll("[^0-9\\-]", "")); } catch (Exception e) { return 0; }
        return 0;
    }
    private double numVal(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s.replaceAll("[^0-9.\\-]", "")); } catch (Exception e) { return 0; }
        return 0;
    }
    private double clamp(double v, double lo, double hi, double def) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return def;
        return Math.max(lo, Math.min(hi, v));
    }
    private int clampInt(Object o, int def) {
        int v = intVal(o);
        if (v == 0 && (o == null || "0".equals(str(o)))) return def;
        return Math.max(0, Math.min(100, v));
    }

    private String validateSentiment(String s) {
        if (List.of("POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED").contains(s)) return s;
        return "NEUTRAL";
    }
    private String validateDecision(String s) {
        if (List.of("BUY", "CONSIDER", "HESITATE", "NOT_BUY", "UNDECIDED").contains(s)) return s;
        return "UNDECIDED";
    }

    private String compact(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        String t = text.replaceAll("\\r\\n|\\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, (int) (maxLen * 0.72)) + "\n\n...[중간 생략]...\n\n"
                + t.substring(t.length() - (int) (maxLen * 0.28));
    }

    private double pct(long part, int total) {
        return total > 0 ? Math.round(part * 10000.0 / total) / 100.0 : 0;
    }

    private Object avg(List<Map<String, Object>> rows, String key) {
        var vals = rows.stream().mapToDouble(r -> numVal(r.get(key))).filter(v -> !Double.isNaN(v)).toArray();
        if (vals.length == 0) return null;
        double sum = 0;
        for (double v : vals) sum += v;
        return Math.round(sum / vals.length * 100.0) / 100.0;
    }

    private Map<String, Long> countBy(List<Map<String, Object>> rows, String key) {
        return rows.stream().collect(Collectors.groupingBy(
                r -> str(r.get(key)), Collectors.counting()));
    }

    private Map<String, Long> topK(List<Map<String, Object>> rows, String key, int k) {
        return rows.stream()
                .collect(Collectors.groupingBy(r -> str(r.get(key)), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(k)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private List<Map<String, Object>> topFlattenedItems(List<Map<String, Object>> rows, String key, int limit) {
        return rows.stream()
                .flatMap(r -> flattenJsonItems(str(r.get(key))).stream())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .<Map<String, Object>>map(e -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("text", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> flattenJsonItems(String json) {
        if (json == null || json.isBlank() || json.equals("[]") || json.equals("{}")) return List.of();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List) {
                return ((List<Object>) parsed).stream()
                        .map(item -> {
                            if (item instanceof Map m) {
                                return str(m.get("text") != null ? m.get("text")
                                        : m.get("label") != null ? m.get("label")
                                        : m.get("reason") != null ? m.get("reason") : m.toString());
                            }
                            return str(item);
                        })
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> safeList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return Map.of();
    }

    private Map<String, Object> safeMap(Map<String, Object> source, String key) {
        if (source == null) return Map.of();
        return safeMap(source.get(key));
    }

    @SuppressWarnings("unchecked")
    private List<String> safeListFromDb(Object o, int limit) {
        if (o instanceof String s) {
            try {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof List) {
                    return ((List<Object>) parsed).stream()
                            .map(this::str).filter(x -> !x.isEmpty()).limit(limit).toList();
                }
            } catch (Exception ignored) {}
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List) {
                return ((List<Object>) parsed).stream().map(this::str).toList();
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List) return (List<Object>) parsed;
        } catch (Exception ignored) {}
        return List.of();
    }
}
