# Tadiwa Print Buddy

**A personal order, cash flow, and customer debt tracker built for my dorm-room printing business.**

I built Tadiwa Print Buddy because managing orders, customer balances, and payments with notebooks and spreadsheets became chaotic. I needed something fast, offline, and reliable that worked entirely on my phone.

No cloud.  
No subscriptions.  
No internet dependency.  
Just my printer, my customers, and complete control over my business.

---

# Why I Built It

I run a small printing hustle and needed a system that could:

- Track customer debt accurately
- Record orders quickly at the counter
- Monitor daily earnings and true net profit
- Handle partial payments and settlements
- Keep customer history organized
- Work fully offline

Instead of adapting generic apps to my workflow, I built my own tailored system.

---

# Core Features

## Order Management
- **Quick order entry**: Validated system preventing zero-value or empty orders.
- **Payment support**: Full lifecycle for CASH, UPI, and CREDIT payments.
- **Order Status Tracking**: Distinguish between ACTIVE and CANCELLED orders with full financial reversal (stock restoration and debt reduction).
- **Atomic Operations**: Single-transaction database writes ensuring no partial data corruption.

## Earnings & Analytics
- **Financial Dashboard**: Real-time revenue tracking based on actual cash collected.
- **Net Profit Analysis**: Automatically subtracts expenses and stock costs from earnings.
- **Revenue Visualization**: Trend charts and payment method breakdowns.
- **Beauty Account**: Specialized digital ledger for UPI wallet tracking with auto-reconciliation.

## Customer Ledger System
- **Smart customer normalization**: Handles variations in name casing and spacing.
- **Debt & Change Tracking**: Clear visibility into who owes money and where change is due.
- **Deterministic Transaction Sorting**: View history sorted by "Newest First" or "Oldest First" with ID-based tie-breaking.
- **Authoritative Balances**: Derived from chronological settlement history for 100% accuracy.

## Inventory & Expenses
- **Stock Management**: Track physical units (paper, ink) with low-stock alerts.
- **Expense Logging**: Categorized business costs for accurate profit calculation.

---

# Daily Workflow

1. Customer places an order.
2. Enter service type, quantity, and price.
3. Validation ensures quantity/price > 0 and stock is available.
4. Payment is recorded as CASH, UPI, or CREDIT.
5. Order saved instantly across all ledgers (Orders, Settlements, Stock).
6. Returning customers retain complete history with persistent expansion states in the ledger.

---

# Technical Highlights

## Architecture
- **MVVM (Model-View-ViewModel)**: Clean separation of UI and business logic.
- **Room Database (v27)**: Robust local storage with explicit migration paths.
- **Repository Pattern**: Authoritative business rule enforcement.
- **ViewBinding**: Type-safe UI interaction.
- **Coroutines & Flow**: High-performance asynchronous data streams.
- **WorkManager**: Background tasks for daily database backups.

## Data Integrity & Security
- **Atomic Transactions**: `@Transaction` boundaries for all critical financial writes.
- **Duplicate Guard**: ViewModel-level submission guards preventing rapid-tap duplicates.
- **Safe Reversals**: Full reversal logic for cancelled orders (reverts stock and balances).
- **Biometric Security**: Integrated SecurityManager for PIN/Biometric app locking.

## Reliability
- **Fully Offline-First**: No server dependency.
- **Automated Backups**: 24-hour periodic database exports.
- **Unit Tested**: Comprehensive test suite for order validation, sorting, and database integrity.

---

# Screenshots

<h2 align="center">App Screenshots</h2>

<p align="center">
  <img src="https://github.com/user-attachments/assets/5f462579-0bde-4e51-a5f3-12f5673ff237" width="220" hspace="10"/>
  <img src="https://github.com/user-attachments/assets/e7fd1c59-89a7-43f6-aa0d-894026e87bf3" width="220" hspace="10"/>
  <img src="https://github.com/user-attachments/assets/38134c5e-70ae-4c52-9146-bd0d7f85bc5a" width="220" hspace="10"/>
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/64a031db-a0ff-4e68-9afa-212843c29a5b" width="220" hspace="10"/>
  <img src="https://github.com/user-attachments/assets/a4922ee6-20b6-4e05-bdb4-fefdbc76ecfa" width="220" hspace="10"/>
</p>

---

# Setup & Development

## Requirements
- Android 7.0 (API 24+) or higher
- Android Studio Ladybug or newer
- Kotlin 2.0+

## Development
- **Build**: `./gradlew assembleDebug`
- **Test**: `./gradlew test`
- **Lint**: `./gradlew lint`

---

# About This Project

This is not a tutorial clone or a demo project. I built this app to solve real operational problems in my own business while studying and running a print hustle from my dorm room.

The goal was simple: **Build software that is genuinely useful in daily life.**

---

**Developer:** Tadiwanashe E Tembo  
**Brand:** Querycubix  
**Project:** Tadiwa Print Buddy

---

*Last Updated: July 2026*
