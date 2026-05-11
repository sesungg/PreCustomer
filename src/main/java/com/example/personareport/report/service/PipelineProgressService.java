package com.example.personareport.report.service;

import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.repository.PipelineProgressRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 파이프라인 진행상황 조회 및 저장 서비스 */
@Service
@RequiredArgsConstructor
public class PipelineProgressService {

    private final PipelineProgressRepository progressRepository;

    /** orderId로 진행상황을 조회한다. */
    @Transactional(readOnly = true)
    public Optional<PipelineProgress> findById(Long orderId) {
        return progressRepository.findById(orderId);
    }

    /** 진행상황을 저장하거나 업데이트한다. */
    @Transactional
    public PipelineProgress save(PipelineProgress progress) {
        return progressRepository.save(progress);
    }

    /** 진행 중인 파이프라인에 graceful stop을 요청한다. */
    @Transactional
    public boolean requestStop(Long orderId) {
        return progressRepository.findById(orderId)
                .filter(progress -> !progress.isTerminal())
                .map(progress -> {
                    progress.requestStop();
                    progressRepository.save(progress);
                    return true;
                })
                .orElse(false);
    }

    /** 중지 요청 여부를 최신 DB 상태 기준으로 조회한다. */
    @Transactional(readOnly = true)
    public boolean isStopRequested(Long orderId) {
        return progressRepository.findById(orderId)
                .map(PipelineProgress::isStopRequested)
                .orElse(false);
    }
}
