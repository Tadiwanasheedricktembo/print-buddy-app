# My Print Shop - Tadiwa Print Buddy

**My personal order and cash flow tracker for my printing business.**

I built Tadiwa Print Buddy to solve my own problems: tracking customer debt, managing orders, and seeing my cash flow in real-time—all on my phone. No cloud, no subscriptions, no complexity. Just me, my printer, and my customers.

## Features

### 1. My Order Book
- **Quick Entry:** Log orders in seconds (Photo prints, A4, Business cards, Copies, etc.)
- **Payment Options:** CASH, UPI, or CREDIT ("Owes Me") right at the counter
- **Order Timeline:** See all my orders, sorted by date
- **Photo Backup:** Attach reference photos to remember what I printed

### 2. My Earnings Dashboard
- **What's My Status?**
    - Total money I've earned
    - How much I made today
    - How many orders I've done
    - Cash in my pocket (CASH payments only)
    - Money my customers owe me
- **Revenue by Service:** See which services make me the most money
- **Quick Check:** Know my business health at a glance

### 3. My Customer Ledger
- **One Customer, One Record:** No duplicates. Desmond is Desmond, whether I type "Desmond", "desmond", or " DESMOND "
- **Who Owes Me / Who I Owe:**
    - **Red (OWES ME):** How much they owe me
    - **Green (I OWE CHANGE):** How much I owe them back
- **Transaction History:** Every order, payment, and note tied to each customer with running balance
- **Smart Payment Logic:** When Desmond pays ₹50, the app automatically settles his oldest orders first
- **Quick Export:** Export my debtor list as CSV if I need it

### 4. My Printer Notes
- **Settings Vault:** Photos of my printer config for quick reference
- **Quick Reminders:** Images of samples, settings, or recurring job notes

## How I Use It (Every Day)

1. **Customer comes in** → I create order with service & price
2. **They pay or go on credit** → I pick CASH, UPI, or "Owes Me"
3. **I print their job** → Order saved, my balance updates
4. **Same customer returns later** → I see their balance, past orders, everything
5. **They pay their dues** → I record it, their balance updates automatically
6. **End of month** → I export my debtor list, see how much people owe me, check my earnings

**No internet. No servers. No middleman. Just my business, my phone, my control.**

### Built With
- **Language:** Kotlin
- **Database:** Room (SQLite with ACID compliance)
- **Architecture:** Repository pattern with atomic transactions
- **UI:** Material Design, ViewBinding
- **Async:** Coroutines for smooth, non-blocking operations
- **Charts:** MPAndroidChart for revenue visualization
- **Resilience:** Comprehensive data integrity checks and audit logging

## Setup & Installation

### Requirements
- Android 7.0 (API 24) or higher
- ~20 MB storage for app + database

### Install
1. Download the APK or build from source
2. Install on your Android phone
3. Grant permission for photo storage (if attaching order photos)
4. Start creating orders immediately—no login required

## Key Features Inside

### Data Integrity (Critical Fixes May 2026)
✅ **No Duplicate Customers:** Names normalized (case-insensitive, whitespace-trimmed)  
✅ **Accurate Balances:** Settlement history is authoritative; projection tables auto-rebuild  
✅ **Atomic Transactions:** Multi-table operations protected with @Transaction boundaries  
✅ **Change Due Handling:** Negative balances correctly shown as "CHANGE DUE" (green), not debt  
✅ **Zero Double-Counting:** Orders and settlements linked; no balance duplication  

### Debug & Monitoring
- Comprehensive logging for balance calculations
- Data integrity checks accessible in debug mode
- Settlement history export for external audits

---

## My Story

I started this printing business to make money doing what I'm good at. But tracking orders and customer debt was a nightmare—spreadsheets, notebooks, lost records. So I built this app for myself. It's simple, it works, and it's 100% mine.

If you're in the printing game like me, this is your app too.

## SCREENSHOTS OF THE APP
<p align="center">
  <img src="https://github.com/user-attachments/assets/5f462579-0bde-4e51-a5f3-12f5673ff237" width="220"/>
  <img src="https://github.com/user-attachments/assets/e7fd1c59-89a7-43f6-aa0d-894026e87bf3" width="220"/>
  <img src="https://github.com/user-attachments/assets/38134c5e-70ae-4c52-9146-bd0d7f85bc5a" width="220"/>
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/64a031db-a0ff-4e68-9afa-212843c29a5b" width="220"/>
  <img src="https://github.com/user-attachments/assets/a4922ee6-20b6-4e05-bdb4-fefdbc76ecfa" width="220"/>
</p>


**By:** Tadiwanashe E Tembo (@Querycubix)  
**For My Print Shop** 🖨️

---
*Last Updated:* May 2026  
*Status:* My daily tool (Stable & Battle-tested)
