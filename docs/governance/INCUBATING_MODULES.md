# Incubating Module Promotion Plan

**Last updated:** 2026-05-07

## Modules Currently Incubating

| Module | Why Incubating | GA Blockers | Target |
|--------|----------------|-------------|--------|
| Screenshot | Non-OTel-native, payload size concerns | E2E UAT cell, OTel semconv proposal, payload size cap validation | 0.4.0-alpha |
| Wireframe | Non-OTel-native, control plane dependency | Journey replay viewer in control plane, size regression tests | 0.4.0-alpha |
| Debug Widget | Dev/demo only, not OTel-native | Stays incubating permanently (dev tool, not production feature) | N/A |
| Database | OTel-native but limited validation | Real-world Room query validation, slow-query threshold tuning | 0.3.1-alpha |
| File I/O | OTel-native but limited validation | Real-world file operation profiling, performance impact measurement | 0.3.1-alpha |
| System Events | OTel-native but battery impact unknown | Battery drain measurement on 3+ device models | 0.3.1-alpha |
| Timber | OTel-native, niche dependency | Validate graceful no-op when Timber not on classpath | 0.3.1-alpha |
| Amplify DataStore | OTel-native, niche dependency | Innovapptive beta validation, Amplify version matrix | Customer-driven |
| Screen Orientation | OTel-native, minor feature | Bundle with next GA release | 0.3.1-alpha |
| Compose Click | OTel-native, Compose ecosystem evolving | Compose BOM 2025.01+ compatibility matrix | 0.4.0-alpha |
| Compose Navigation | OTel-native, Compose ecosystem evolving | NavHost integration test, Compose BOM compatibility | 0.4.0-alpha |

## Screenshot & Wireframe Promotion Path

### Screenshot → Beta

1. Rate limiter validated under sustained tap-burst (100 taps/s)
2. Payload size cap enforced (default 50KB JPEG)
3. Text redaction verified against PII test corpus
4. UAT matrix cell added (screenshot capture + Dash0 ingestion)
5. Performance profiled: < 50ms capture time, no visible jank

### Screenshot → GA

1. OTel semantic convention proposal submitted (or `ui.screenshot` documented as vendor extension)
2. iOS parity (PixelCopy equivalent on iOS)
3. 2+ customer validation cycles without API changes
4. Control plane viewer renders screenshots inline

### Wireframe → Beta

1. View hierarchy JSON schema stabilized (v2)
2. Size regression test: p99 < 5KB on complex layouts
3. UAT matrix cell added
4. Sensitive-view exclusion list works with custom views

### Wireframe → GA

1. Control plane journey replay viewer renders wireframes
2. iOS parity (UIKit view hierarchy equivalent)
3. Compose view hierarchy support
4. 2+ customer validation cycles
