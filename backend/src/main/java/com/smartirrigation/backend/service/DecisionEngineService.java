package com.smartirrigation.backend.service;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartirrigation.backend.config.DecisionProperties;
import com.smartirrigation.backend.dto.IrrigationDecision;

@Service
public class DecisionEngineService {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngineService.class);

    private static final Map<String, Integer> DOSAGE_ML = Map.of(
            "healthy", 0,
            "water_stress", 100,
            "nitrogen_deficiency", 50,
            "potassium_deficiency", 40,
            "magnesium_deficiency", 30);

    private final double flowRateMlPerSec;

    public DecisionEngineService(DecisionProperties props) {
        if (props.flowRateMlPerSec() <= 0) {
            throw new IllegalStateException("decision.flow-rate-ml-per-sec must be > 0");
        }
        this.flowRateMlPerSec = props.flowRateMlPerSec();
    }

    public IrrigationDecision decide(String stressType) {
        String label = stressType == null || stressType.isBlank()
                ? "unknown"
                : stressType.trim().toLowerCase(Locale.ROOT);

        Integer dosageMl = DOSAGE_ML.get(label);
        if (dosageMl == null) {
            log.warn("Unknown stress type '{}', no irrigation/nutrient action taken", label);
            dosageMl = 0;
        }

        int valveSec = (int) Math.round(dosageMl / flowRateMlPerSec);
        return new IrrigationDecision(label, dosageMl, valveSec);
    }
}
