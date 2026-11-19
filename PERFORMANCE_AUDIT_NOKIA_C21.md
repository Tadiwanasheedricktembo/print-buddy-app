# Performance Audit & Hardware Profile — Nokia C21 Plus Target

**Target Device:** Nokia C21 Plus (Budget / Entry-Level)  
**Hardware Specs:** Unisoc SC9863A SoC (Octa-core ARM Cortex-A55 up to 1.6 GHz), 2GB / 3GB RAM, eMMC 5.1 Flash Storage, Android Go / standard Android.  
**Audience:** Developers and AI agents working on Tadiwa Print Buddy performance optimization.

---

## 1. Executive Summary & Hardware Context

When running Tadiwa Print Buddy on low-end budget devices like the **Nokia C21 Plus**, performance bottlenecks manifest differently than on high-end developer workstations or emulators. 

Budget devices combine:
1. **Slow eMMC 5.1 random I/O** (making database schema validation and full table scans expensive).
2. **Power-efficient Cortex-A55 CPU cores** (lower single-core performance; heavy computation or synchronous queries cause UI jank).
3. **Limited RAM (2GB–3GB)** (aggressive garbage collection / GC pauses under memory allocation churn).

---

## 2. Key Performance Bottlenecks Identified

### A. Room Database Migration Overhead (46 Migrations)
- **Problem:** The app has accumulated 46 Room database migrations (`MIGRATION_8_9` through `MIGRATION_45_46`).
- **Impact on C21 Plus:** On cold app start, Room verifies schema compatibility across all 46 migration objects. On eMMC storage, this inspection delay contributes significantly to splash screen / cold start latency.

### B. Startup Query Storms (`DashboardViewModel` & `MainViewModel`)
- **Problem:** Upon app launch, both `MainViewModel` (restoring quick order drafts via Gson parsing) and `DashboardViewModel` (observing analytics changes across 7 tables and computing revenue, trends, and service breakdowns) execute heavy queries immediately in their `init` blocks.
- **Impact on C21 Plus:** Firing multiple concurrent database reads while UI views are inflating maxes out all CPU cores simultaneously, causing frame drops.

### C. Unthrottled Analytics Observation
- **Problem:** `observeAnalyticsDataChanges()` triggers a re-fetch whenever any row in 7 core tables changes. During multi-table database transactions (e.g., recording an order), this causes rapid-fire query execution.

### D. Memory Allocation & Garbage Collection Churn
- **Problem:** Frequent creation of temporary `BigDecimal` instances, string formatting, and list transformations during analytics aggregation triggers frequent GC cycles. On Cortex-A55 cores, GC pauses cause perceptible UI stutter during scrolling or tab switching.

---

## 3. Actionable Guidelines for Future Agents

When optimizing or building new features for Tadiwa Print Buddy, adhere to these performance rules:

1. **Lazy-Load Analytics & Dashboards:** Never execute heavy aggregation queries (`getSettledRevenueByMethodBetween`, trends, breakdowns) inside ViewModel `init` blocks. Defer them until the fragment/activity is explicitly resumed or requested.
2. **Debounce Flow Collectors:** When observing database change streams (e.g., `observeAnalyticsDataChanges`), apply `.debounce(300)` to batch rapid writes.
3. **Leverage Room Indices:** Ensure all frequently filtered columns (`deletedAt`, `customerId`, `orderStatus`, `timestamp`) have explicit indices on Room entities.
4. **Offload to `Dispatchers.IO`:** Ensure all repository queries and currency calculations run on background threads without blocking the main Looper.
