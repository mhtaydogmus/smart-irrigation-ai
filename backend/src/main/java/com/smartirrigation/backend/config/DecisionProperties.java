package com.smartirrigation.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "decision")
public record DecisionProperties(@DefaultValue("10") double flowRateMlPerSec) {
}
