package com.example.personareport.report.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "deepseek", url = "${app.deepseek.base-url:https://api.deepseek.com}")
public interface DeepSeekFeignClient {

    @PostMapping(value = "/chat/completions", consumes = "application/json")
    String chatCompletion(
            @RequestHeader("Authorization") String auth,
            @RequestBody DeepSeekRequest request);

    record DeepSeekRequest(
            String model,
            List<Message> messages,
            @JsonProperty("response_format")
            Map<String, String> responseFormat,
            double temperature,
            @JsonProperty("max_tokens")
            int maxTokens,
            boolean stream,
            Map<String, Object> thinking
    ) {
        public DeepSeekRequest {
            responseFormat = responseFormat != null ? responseFormat : Map.of("type", "json_object");
        }
    }

    record Message(String role, String content) {}

    record DeepSeekResponse(List<Choice> choices) {}

    record Choice(ResponseMessage message, @JsonProperty("finish_reason") String finishReason) {}

    record ResponseMessage(String content) {}
}
