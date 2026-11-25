package com.smartirrigation.backend.dto;

public record IrrigationDecision(String stressType, String diagnosis, int dosageMl, int valveSec) {
}
