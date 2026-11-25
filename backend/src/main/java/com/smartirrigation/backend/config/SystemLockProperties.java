package com.smartirrigation.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "system.lock")
public record SystemLockProperties(@DefaultValue("30s") Duration timeout) {
}
