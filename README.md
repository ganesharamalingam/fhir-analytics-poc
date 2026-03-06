# FHIR Analytics POC — Care Manager Dashboard

A proof-of-concept care manager dashboard built as a single-page HTML application using FHIR-aligned clinical data structures.

## Overview

The dashboard provides a care manager view into a member panel, showing risk stratification and clinical detail at a glance. It is designed for care managers who need to quickly assess and act on patient clinical summaries.

## Features

- **Member list panel** — left sidebar showing all panel members with risk level badges (HIGH / MEDIUM / LOW), demographics, and primary diagnoses
- **Clinical detail panel** — right pane showing the selected member's full clinical summary including conditions, vitals, medications, recent encounters, and care gaps
- **Risk stratification** — color-coded risk indicators aligned to care management workflows
- **Hardcoded demo data** — three representative members covering different risk profiles

## Members

| Name | Age/Sex | Risk | Conditions |
|---|---|---|---|
| Carlos Ramirez | 57M | HIGH | Type 2 Diabetes, Hypertension |
| Linda Chen | 63F | MEDIUM | Hypertension |
| James Okafor | 45M | LOW | None (preventive) |

## Usage

Open `index.html` directly in any modern browser. No build step, server, or dependencies required.

## Technology

- Plain HTML5, CSS3, and vanilla JavaScript
- No external runtime dependencies
- FHIR-aligned data model (conditions, medications, encounters represented per FHIR R4 resource patterns)
