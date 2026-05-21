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
- Monitor daily earnings
- Handle partial payments and settlements
- Keep customer history organized
- Work fully offline

Instead of adapting generic apps to my workflow, I built my own.

---

# Core Features

## Order Management
- Quick order entry system
- Support for CASH, UPI, and CREDIT payments
- Order timeline with timestamps
- Photo attachments for print references
- Automatic revenue tracking

## Earnings Dashboard
- Total revenue tracking
- Daily earnings overview
- Order statistics
- Cash-only balance tracking
- Customer debt monitoring
- Revenue visualization charts

## Customer Ledger System
- Smart customer normalization
  - "Desmond", " desmond ", and "DESMOND" become one customer
- Tracks:
  - Customers who owe me
  - Customers I owe change to
- Full settlement and transaction history
- Automatic oldest-debt-first settlement logic
- CSV export for debtor records

## Printer Notes & References
- Store printer configuration screenshots
- Save recurring print samples
- Keep reference images for repeat jobs

---

# Daily Workflow

1. Customer places an order
2. I enter service type and price
3. Payment is recorded as CASH, UPI, or CREDIT
4. Order gets saved instantly
5. Customer balances update automatically
6. Returning customers retain complete history
7. Payments and settlements recalculate balances in real time

---

# Technical Highlights

## Architecture
- Kotlin
- Room Database (SQLite)
- Repository Pattern
- Material Design UI
- ViewBinding
- Coroutines

## Data Integrity Features
- Atomic multi-table transactions
- Case-insensitive customer normalization
- Settlement-history-driven balance calculations
- Automatic projection rebuilding
- Prevention of duplicate balance calculations
- Audit logging and debug monitoring tools

## Reliability
- Fully offline-first
- No server dependency
- No account/login system
- Local database ownership
- Designed for low-resource Android devices

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

# Setup & Installation

## Requirements
- Android 7.0 (API 24+) or higher
- ~20 MB storage

## Installation
1. Download the APK or clone the repository
2. Install the app on Android
3. Grant storage permission for image attachments
4. Start creating orders immediately

No signup or internet connection required.

---

# Current Status

- Stable daily-use build
- Actively used in my real printing business
- Offline-first architecture complete
- Customer ledger and settlement system operational
- Analytics and financial tracking integrated

---

# About This Project

This is not a tutorial clone or a demo project.

I built this app to solve real operational problems in my own business while studying and running a print hustle from my dorm room.

The goal was simple:

> Build software that is genuinely useful in daily life.

---

**Developer:** Tadiwanashe E Tembo  
**Brand:** Querycubix  
**Project:** Tadiwa Print Buddy

---

*Last Updated: May 2026*
