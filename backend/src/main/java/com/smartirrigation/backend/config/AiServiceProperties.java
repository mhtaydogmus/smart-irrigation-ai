package com.smartirrigation.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ai.service")
public record AiServiceProperties(
        String url,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout) {
}
