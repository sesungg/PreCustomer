package com.example.personareport.report.service;

import com.example.personareport.report.domain.PipelineProgress;
import com.example.personareport.report.repository.PipelineProgressRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PipelineProgressService {

    private final PipelineProgressRepository progressRepository;

    @Transactional(readOnly = true)
    public Optional<PipelineProgress> findById(Long orderId) {
        return progressRepository.findById(orderId);
    }

    @Transactional
    public PipelineProgress save(PipelineProgress progress) {
        return progressRepository.save(progress);
    }
}
