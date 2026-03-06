package com.payer.fhir.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * POC Endpoint — Direct FHIR Bundle Analysis
 *
 * POST /api/v1/analyze
 *
 * Accepts a raw FHIR R4 Bundle JSON, runs HEDIS + Risk logic inline,
 * and returns a full member intelligence report.
 * No database, no batch job — designed for demo / prototype use.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class BundleAnalysisController {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Weights (mirrors RiskStratificationTasklet) ──────────────────────
    private static final double W_CHRONIC  = 0.30;
    private static final double W_ED       = 0.20;
    private static final double W_IP       = 0.20;
    private static final double W_LAB      = 0.15;
    private static final double W_MED_GAP  = 0.15;

    private static final int CAP_CHRONIC = 8;
    private static final int CAP_ED      = 4;
    private static final int CAP_IP      = 3;
    private static final int CAP_LAB     = 6;
    private static final int CAP_MED_GAP = 120;

    // ── LOINC codes (mirrors HedisMeasureTasklet) ─────────────────────────
    private static final String LOINC_HBA1C       = "4548-4";
    private static final String LOINC_SYSTOLIC_BP = "8480-6";
    private static final String LOINC_DIASTOLIC_BP= "8462-4";
    private static final Set<String> CPT_COLONOSCOPY = Set.of("45378","45380","45381","45382","45384","45385");
    private static final Set<String> CPT_MAMMOGRAPHY = Set.of("77067","77066","77065");
    private static final Set<String> CPT_FIT_STOOL   = Set.of("82274","82270");

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody String bundleJson) {
        try {
            JsonNode bundle = mapper.readTree(bundleJson);
            List<JsonNode> entries = extractEntries(bundle);

            // ── Extract resources by type ─────────────────────────────────
            JsonNode patient       = findFirst(entries, "Patient");
            List<JsonNode> conditions     = findAll(entries, "Condition");
            List<JsonNode> observations   = findAll(entries, "Observation");
            List<JsonNode> medications    = findAll(entries, "MedicationRequest");
            List<JsonNode> encounters     = findAll(entries, "Encounter");
            List<JsonNode> procedures     = findAll(entries, "Procedure");

            // ── Member demographics ───────────────────────────────────────
            Map<String, Object> member = extractMember(patient);

            // ── Risk Score ────────────────────────────────────────────────
            Map<String, Object> riskResult = computeRisk(conditions, observations, encounters, medications);

            // ── HEDIS Measures ────────────────────────────────────────────
            List<Map<String, Object>> hedis = evaluateHedis(
                member, conditions, observations, medications, procedures, encounters
            );

            // ── Clinical Summary ──────────────────────────────────────────
            Map<String, Object> summary = buildSummary(conditions, observations, medications, encounters);

            // ── Response ──────────────────────────────────────────────────
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("member",          member);
            response.put("riskScore",        riskResult);
            response.put("hedisMeasures",    hedis);
            response.put("clinicalSummary",  summary);
            response.put("analyzedAt",       LocalDate.now().toString());

            log.info("Analysis complete — member={} risk={}",
                member.get("id"), riskResult.get("riskTier"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Analysis failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEMBER EXTRACTION
    // ─────────────────────────────────────────────────────────────────────

    private Map<String, Object> extractMember(JsonNode patient) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (patient == null) { m.put("id", "UNKNOWN"); return m; }

        m.put("id", patient.path("id").asText("UNKNOWN"));

        // Name
        JsonNode nameNode = patient.path("name");
        if (nameNode.isArray() && nameNode.size() > 0) {
            JsonNode n = nameNode.get(0);
            String family = n.path("family").asText("");
            String given  = n.path("given").isArray() && n.path("given").size() > 0
                ? n.path("given").get(0).asText("") : "";
            m.put("name", (given + " " + family).trim());
        } else {
            m.put("name", "Unknown Member");
        }

        m.put("birthDate", patient.path("birthDate").asText(""));
        m.put("gender",    patient.path("gender").asText("unknown"));

        // Age
        String dob = patient.path("birthDate").asText("");
        if (!dob.isEmpty()) {
            try {
                LocalDate bd = LocalDate.parse(dob, DateTimeFormatter.ISO_LOCAL_DATE);
                m.put("age", LocalDate.now().getYear() - bd.getYear());
            } catch (Exception ignored) {}
        }

        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // RISK STRATIFICATION  (mirrors RiskStratificationTasklet logic)
    // ─────────────────────────────────────────────────────────────────────

    private Map<String, Object> computeRisk(
        List<JsonNode> conditions, List<JsonNode> observations,
        List<JsonNode> encounters,  List<JsonNode> medications
    ) {
        LocalDate cutoff12m = LocalDate.now().minusMonths(12);

        // 1. Chronic condition count (unique ICD-10 categories)
        Set<String> icd10Categories = new HashSet<>();
        for (JsonNode c : conditions) {
            String icd = getIcd10Code(c);
            if (!icd.isEmpty()) icd10Categories.add(icd.substring(0, Math.min(3, icd.length())));
        }
        int chronicCount = Math.min(icd10Categories.size(), CAP_CHRONIC);

        // 2. ED visits (12m)
        long edVisits = encounters.stream()
            .filter(e -> isEncounterClass(e, "EMER") && isWithin12m(e, cutoff12m))
            .count();
        int edCount = (int) Math.min(edVisits, CAP_ED);

        // 3. IP admissions (12m)
        long ipAdmissions = encounters.stream()
            .filter(e -> isEncounterClass(e, "IMP") && isWithin12m(e, cutoff12m))
            .count();
        int ipCount = (int) Math.min(ipAdmissions, CAP_IP);

        // 4. Labs out of range
        long labsOOR = observations.stream()
            .filter(o -> {
                String interp = o.path("interpretation").isArray() && o.path("interpretation").size() > 0
                    ? o.path("interpretation").get(0).path("coding").isArray()
                        ? o.path("interpretation").get(0).path("coding").get(0).path("code").asText("") : ""
                    : "";
                return Set.of("H","HH","L","LL").contains(interp);
            }).count();
        int labCount = (int) Math.min(labsOOR, CAP_LAB);

        // 5. Medication gaps (stopped/cancelled)
        long medGaps = medications.stream()
            .filter(m -> Set.of("stopped","cancelled","on-hold")
                .contains(m.path("status").asText("")))
            .count();
        int medGapDays = (int) Math.min(medGaps * 30, CAP_MED_GAP); // approximate

        // Weighted composite score
        double score =
            (W_CHRONIC  * ((double) chronicCount  / CAP_CHRONIC))  +
            (W_ED       * ((double) edCount        / CAP_ED))       +
            (W_IP       * ((double) ipCount        / CAP_IP))       +
            (W_LAB      * ((double) labCount       / CAP_LAB))      +
            (W_MED_GAP  * ((double) medGapDays     / CAP_MED_GAP));

        double rounded = Math.round(score * 100.0) / 100.0;
        String tier = rounded >= 0.70 ? "HIGH" : rounded >= 0.40 ? "MEDIUM" : "LOW";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", rounded);
        result.put("riskTier",   tier);
        result.put("components", Map.of(
            "chronicConditionBurden", Map.of("count", icd10Categories.size(), "score", fmt(W_CHRONIC * chronicCount / CAP_CHRONIC)),
            "edUtilization12m",       Map.of("count", edCount,  "score", fmt(W_ED  * edCount  / CAP_ED)),
            "ipAdmissions12m",        Map.of("count", ipCount,  "score", fmt(W_IP  * ipCount  / CAP_IP)),
            "labsOutOfRange",         Map.of("count", labCount, "score", fmt(W_LAB * labCount / CAP_LAB)),
            "medicationGaps",         Map.of("gapDays", medGapDays, "score", fmt(W_MED_GAP * medGapDays / CAP_MED_GAP))
        ));
        result.put("chronicConditions", icd10Categories.stream().sorted().toList());

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // HEDIS MEASURES  (mirrors HedisMeasureTasklet logic)
    // ─────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> evaluateHedis(
        Map<String, Object> member,
        List<JsonNode> conditions, List<JsonNode> observations,
        List<JsonNode> medications, List<JsonNode> procedures,
        List<JsonNode> encounters
    ) {
        List<Map<String, Object>> results = new ArrayList<>();
        int age = member.get("age") != null ? (int) member.get("age") : 0;
        String gender = (String) member.getOrDefault("gender", "unknown");

        boolean hasDiabetes     = hasIcd10Prefix(conditions, "E11");
        boolean hasHypertension = hasIcd10Prefix(conditions, "I10");

        // ── CDC HbA1c Control ─────────────────────────────────────────────
        if (hasDiabetes && age >= 18 && age <= 75) {
            double hba1c = getLatestObsValue(observations, LOINC_HBA1C);
            boolean controlled = hba1c > 0 && hba1c < 8.0;
            results.add(hedisResult("CDC_HBA1C", "Diabetes: HbA1c Control (<8%)",
                true, controlled,
                hba1c > 0 ? String.format("Latest HbA1c: %.1f%%", hba1c) : "No HbA1c result found",
                "LOINC 4548-4"));
        }

        // ── CDC BP Control ────────────────────────────────────────────────
        if (hasDiabetes && age >= 18 && age <= 75) {
            double systolic  = getLatestObsValue(observations, LOINC_SYSTOLIC_BP);
            double diastolic = getLatestObsValue(observations, LOINC_DIASTOLIC_BP);
            boolean controlled = systolic > 0 && diastolic > 0
                && systolic < 140 && diastolic < 90;
            String detail = systolic > 0
                ? String.format("BP: %.0f/%.0f mmHg", systolic, diastolic)
                : "No BP reading found";
            results.add(hedisResult("CDC_BP", "Diabetes: BP Control (<140/90)",
                true, controlled, detail, "LOINC 8480-6 · 8462-4"));
        }

        // ── CBP Hypertension Control ──────────────────────────────────────
        if (hasHypertension && age >= 18 && age <= 85) {
            double systolic  = getLatestObsValue(observations, LOINC_SYSTOLIC_BP);
            double diastolic = getLatestObsValue(observations, LOINC_DIASTOLIC_BP);
            boolean controlled = systolic > 0 && systolic < 140 && diastolic < 90;
            String detail = systolic > 0
                ? String.format("BP: %.0f/%.0f mmHg", systolic, diastolic)
                : "No BP reading found";
            results.add(hedisResult("CBP", "Controlling High Blood Pressure",
                true, controlled, detail, "LOINC 8480-6 · 8462-4"));
        }

        // ── COL Colorectal Screening ──────────────────────────────────────
        if (age >= 45 && age <= 75) {
            boolean screened = hasProcedureCode(procedures, CPT_COLONOSCOPY)
                || hasProcedureCode(procedures, CPT_FIT_STOOL);
            results.add(hedisResult("COL", "Colorectal Cancer Screening",
                true, screened,
                screened ? "Screening completed" : "No colonoscopy or FIT stool test found",
                "CPT 45378-85 · FIT 82274"));
        }

        // ── BCS Breast Cancer Screening ───────────────────────────────────
        if (gender.equalsIgnoreCase("female") && age >= 50 && age <= 74) {
            boolean screened = hasProcedureCode(procedures, CPT_MAMMOGRAPHY);
            results.add(hedisResult("BCS", "Breast Cancer Screening (Mammography)",
                true, screened,
                screened ? "Mammography completed" : "No mammography found",
                "CPT 77067 · 77066 · 77065"));
        }

        // ── MED Statin Adherence (PDC) ────────────────────────────────────
        boolean hasStatinRx = medications.stream().anyMatch(m -> {
            String display = m.path("medicationCodeableConcept")
                .path("text").asText("").toLowerCase();
            return display.contains("statin") || display.contains("atorvastatin")
                || display.contains("rosuvastatin") || display.contains("simvastatin")
                || display.contains("pravastatin");
        });
        if (hasStatinRx) {
            long activeStatins = medications.stream()
                .filter(m -> {
                    String txt = m.path("medicationCodeableConcept").path("text").asText("").toLowerCase();
                    boolean isStatin = txt.contains("statin") || txt.contains("atorvastatin")
                        || txt.contains("rosuvastatin");
                    return isStatin && "active".equals(m.path("status").asText(""));
                }).count();
            boolean adherent = activeStatins > 0;
            results.add(hedisResult("MED_PDC", "Statin Medication Adherence (PDC ≥80%)",
                true, adherent,
                adherent ? "Active statin prescription on file"
                         : "Statin gap detected — prescription stopped or cancelled",
                "RxNorm statin concept codes"));
        }

        return results;
    }

    private Map<String, Object> hedisResult(
        String id, String name, boolean inDenominator,
        boolean numeratorMet, String detail, String fhirSignal
    ) {
        String gap = !inDenominator ? "NOT_ELIGIBLE"
            : numeratorMet ? "CLOSED" : "OPEN";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("measureId",       id);
        r.put("measureName",     name);
        r.put("inDenominator",   inDenominator);
        r.put("numeratorMet",    numeratorMet);
        r.put("gapStatus",       gap);
        r.put("detail",          detail);
        r.put("fhirSignal",      fhirSignal);
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLINICAL SUMMARY
    // ─────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildSummary(
        List<JsonNode> conditions, List<JsonNode> observations,
        List<JsonNode> medications, List<JsonNode> encounters
    ) {
        // Active conditions
        List<String> activeConditions = new ArrayList<>();
        for (JsonNode c : conditions) {
            String status = c.path("clinicalStatus").path("coding").isArray()
                ? c.path("clinicalStatus").path("coding").get(0).path("code").asText("") : "";
            if ("active".equals(status) || status.isEmpty()) {
                String display = c.path("code").path("text").asText(
                    c.path("code").path("coding").isArray() && c.path("code").path("coding").size() > 0
                        ? c.path("code").path("coding").get(0).path("display").asText("Unknown Condition")
                        : "Unknown Condition"
                );
                activeConditions.add(display);
            }
        }

        // Active medications
        List<String> activeMeds = new ArrayList<>();
        for (JsonNode m : medications) {
            if ("active".equals(m.path("status").asText(""))) {
                String display = m.path("medicationCodeableConcept").path("text").asText(
                    m.path("medicationCodeableConcept").path("coding").isArray()
                        && m.path("medicationCodeableConcept").path("coding").size() > 0
                        ? m.path("medicationCodeableConcept").path("coding").get(0).path("display").asText("Unknown Med")
                        : "Unknown Med"
                );
                activeMeds.add(display);
            }
        }

        // Key labs
        Map<String, String> keyLabs = new LinkedHashMap<>();
        double hba1c = getLatestObsValue(observations, LOINC_HBA1C);
        if (hba1c > 0) keyLabs.put("HbA1c", String.format("%.1f%%", hba1c));
        double sysBP = getLatestObsValue(observations, LOINC_SYSTOLIC_BP);
        double diaBP = getLatestObsValue(observations, LOINC_DIASTOLIC_BP);
        if (sysBP > 0) keyLabs.put("Blood Pressure", String.format("%.0f/%.0f mmHg", sysBP, diaBP));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeConditions",   activeConditions);
        summary.put("activeMedications",  activeMeds);
        summary.put("keyLabValues",        keyLabs);
        summary.put("totalEncounters",     encounters.size());
        summary.put("conditionCount",      conditions.size());
        summary.put("medicationCount",     medications.size());

        return summary;
    }

    // ─────────────────────────────────────────────────────────────────────
    // FHIR JSON HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private List<JsonNode> extractEntries(JsonNode bundle) {
        List<JsonNode> entries = new ArrayList<>();
        if (bundle.path("entry").isArray()) {
            for (JsonNode e : bundle.path("entry")) {
                JsonNode resource = e.path("resource");
                if (!resource.isMissingNode()) entries.add(resource);
            }
        }
        return entries;
    }

    private JsonNode findFirst(List<JsonNode> entries, String resourceType) {
        return entries.stream()
            .filter(e -> resourceType.equals(e.path("resourceType").asText()))
            .findFirst().orElse(null);
    }

    private List<JsonNode> findAll(List<JsonNode> entries, String resourceType) {
        return entries.stream()
            .filter(e -> resourceType.equals(e.path("resourceType").asText()))
            .toList();
    }

    private String getIcd10Code(JsonNode condition) {
        if (condition.path("code").path("coding").isArray()) {
            for (JsonNode coding : condition.path("code").path("coding")) {
                String system = coding.path("system").asText("");
                if (system.contains("icd") || system.contains("ICD")) {
                    return coding.path("code").asText("");
                }
            }
            // fallback: return first code
            if (condition.path("code").path("coding").size() > 0) {
                return condition.path("code").path("coding").get(0).path("code").asText("");
            }
        }
        return "";
    }

    private boolean hasIcd10Prefix(List<JsonNode> conditions, String prefix) {
        return conditions.stream().anyMatch(c -> getIcd10Code(c).startsWith(prefix));
    }

    private double getLatestObsValue(List<JsonNode> observations, String loincCode) {
        return observations.stream()
            .filter(o -> {
                if (!o.path("code").path("coding").isArray()) return false;
                for (JsonNode coding : o.path("code").path("coding")) {
                    if (loincCode.equals(coding.path("code").asText(""))) return true;
                }
                return false;
            })
            .mapToDouble(o -> {
                JsonNode vq = o.path("valueQuantity");
                if (!vq.isMissingNode()) return vq.path("value").asDouble(0);
                return o.path("valueDecimal").asDouble(0);
            })
            .filter(v -> v > 0)
            .max().orElse(0);
    }

    private boolean hasProcedureCode(List<JsonNode> procedures, Set<String> cptCodes) {
        return procedures.stream().anyMatch(p -> {
            if (!p.path("code").path("coding").isArray()) return false;
            for (JsonNode coding : p.path("code").path("coding")) {
                if (cptCodes.contains(coding.path("code").asText(""))) return true;
            }
            return false;
        });
    }

    private boolean isEncounterClass(JsonNode encounter, String classCode) {
        String code = encounter.path("class").path("code").asText("");
        return classCode.equalsIgnoreCase(code);
    }

    private boolean isWithin12m(JsonNode encounter, LocalDate cutoff) {
        try {
            String dateStr = encounter.path("period").path("start").asText("");
            if (dateStr.isEmpty()) return false;
            LocalDate d = LocalDate.parse(dateStr.substring(0, 10));
            return !d.isBefore(cutoff);
        } catch (Exception e) { return false; }
    }

    private double fmt(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
