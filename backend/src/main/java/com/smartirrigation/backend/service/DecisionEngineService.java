package com.smartirrigation.backend.service;

import java.util.HashMap;
import java.util.List;
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

    private record Treatment(String diagnosis, int dosageMl, List<String> labels) {
    }

    private static final List<Treatment> TREATMENTS = List.of(
            new Treatment("Healthy", 0, List.of("healthy")),
            new Treatment("K (Potassium Deficiency)", 40, List.of("k", "potassium_deficiency", "potassium")),
            new Treatment("Mg (Magnesium Deficiency)", 30, List.of("mg", "magnesium_deficiency", "magnesium")),
            new Treatment("Fe (Iron Deficiency)", 30, List.of("fe", "iron_deficiency", "iron")));

    private static final Map<String, Treatment> BY_LABEL = indexByLabel();

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

        Treatment treatment = BY_LABEL.get(label);
        if (treatment == null) {
            log.warn("Unknown stress type '{}' (expected Healthy, K, Mg or Fe), no nutrient/irrigation action taken", label);
            return new IrrigationDecision(label, "Unknown (" + label + ")", 0, 0);
        }

        int valveSec = (int) Math.ceil(treatment.dosageMl() / flowRateMlPerSec);
        return new IrrigationDecision(label, treatment.diagnosis(), treatment.dosageMl(), valveSec);
    }

    private static Map<String, Treatment> indexByLabel() {
        Map<String, Treatment> index = new HashMap<>();
        TREATMENTS.forEach(t -> t.labels().forEach(label -> index.put(label, t)));
        return Map.copyOf(index);
    }
}
