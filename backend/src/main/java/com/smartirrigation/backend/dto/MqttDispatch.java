package com.smartirrigation.backend.dto;

public record MqttDispatch(Status status, String topic, String payload) {

    public enum Status {
        PUBLISHED,
        SIMULATED,
        SKIPPED,
        FAILED
    }
}
