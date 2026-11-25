package com.smartirrigation.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"valve_sec", "dosage_ml", "stress_type"})
public record MqttCommand(
        @JsonProperty("valve_sec") int valveSec,
        @JsonProperty("dosage_ml") int dosageMl,
        @JsonProperty("stress_type") String stressType) {
}
