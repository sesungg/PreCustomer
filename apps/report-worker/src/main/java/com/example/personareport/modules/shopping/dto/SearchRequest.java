package com.example.personareport.modules.shopping.dto;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank(message = "검색어를 입력해 주세요.")
        String query,
        String baseProductName,
        Integer basePrice,
        String baseCategory1,
        String baseCategory2,
        String baseCategory3,
        String baseCategory4,
        boolean useQueryVariants
) {}
