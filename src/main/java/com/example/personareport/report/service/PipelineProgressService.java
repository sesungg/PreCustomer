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
}
