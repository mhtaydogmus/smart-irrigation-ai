package com.smartirrigation.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(
        boolean enabled,
        @DefaultValue Broker broker,
        @DefaultValue Topic topic,
        @DefaultValue("smart-irrigation-backend") String clientId) {

    public record Broker(@DefaultValue("tcp://localhost:1883") String url) {
    }

    public record Topic(@DefaultValue("agri/device/command") String command) {
    }
}
