package com.example.personareport.report.pipeline;

import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "deepseek", url = "${app.deepseek.base-url:https://api.deepseek.com}")
public interface DeepSeekFeignClient {

    @PostMapping(value = "/chat/completions", consumes = "application/json")
    DeepSeekResponse chatCompletion(
            @RequestHeader("Authorization") String auth,
            @RequestBody DeepSeekRequest request);

    record DeepSeekRequest(
            String model,
            List<Message> messages,
            Map<String, String> responseFormat,
            double temperature,
            int maxTokens,
            boolean stream
    ) {}

    record Message(String role, String content) {}

    record DeepSeekResponse(List<Choice> choices) {}

    record Choice(ResponseMessage message, String finishReason) {}

    record ResponseMessage(String content) {}
}
