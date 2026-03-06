# FHIR Clinical Analytics Accelerator — POC
**Infinite Computer Solutions · Elevance EDA RFP**

---

## What this is
A working prototype of the FHIR Clinical Analytics Accelerator referenced in the Elevance EDA RFP response (slides 10, 15, 21, 30).

Given a FHIR R4 Bundle it runs:
1. **FHIR Extraction** — parses Patient, Condition, Observation, MedicationRequest, Encounter, Procedure
2. **CDM Transform** — extracts ICD-10, LOINC, RxNorm codes
3. **HEDIS Engine** — evaluates 6 NCQA measures (HbA1c, BP Control, CBP, Colorectal, Mammography, Statin PDC)
4. **Risk Stratification** — weighted composite score → HIGH / MEDIUM / LOW

---

## Files
```
index.html               ← Open this in any browser. Zero dependencies.
sample-fhir-bundle.json  ← Test patient: Carlos Ramirez, diabetic, hypertensive, HIGH risk
BundleAnalysisController.java ← Drop into the Spring Boot project for REST API mode
```

---

## Option A — Browser only (instant, no setup)
1. Open `index.html` in Chrome / Edge / Firefox
2. Click **Load Sample** → **Run Analysis**
3. Done

---

## Option B — Spring Boot REST API (full backend)
1. Copy `BundleAnalysisController.java` into `fhir-pipeline/src/main/java/com/payer/fhir/pipeline/`
2. From the `fhir-pipeline/` directory:
```bash
mvn spring-boot:run
```
3. Test with:
```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H "Content-Type: application/json" \
  -d @sample-fhir-bundle.json
```

---

## Sample patient triggers
The included sample bundle is designed to trigger:
- **HIGH risk** (score ~0.75)
- **4 open HEDIS gaps**: HbA1c uncontrolled, BP uncontrolled, BP (hypertension), colorectal screening missing
- **Statin gap**: Atorvastatin stopped
- **2 ED visits + 1 inpatient admission** in past 12 months
- **4 labs out of range**: HbA1c 9.2%, BP 152/94, eGFR 38, LDL 168

© 2026 Infinite Computer Solutions — Confidential
