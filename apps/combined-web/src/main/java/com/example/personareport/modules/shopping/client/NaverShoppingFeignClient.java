package com.example.personareport.modules.shopping.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "naver-shopping", url = "https://openapi.naver.com")
public interface NaverShoppingFeignClient {

    @GetMapping("/v1/search/shop.json")
    String search(
            @RequestHeader("X-Naver-Client-Id") String clientId,
            @RequestHeader("X-Naver-Client-Secret") String clientSecret,
            @RequestParam("query") String query,
            @RequestParam("sort") String sort,
            @RequestParam("display") int display,
            @RequestParam("start") int start
    );
}
