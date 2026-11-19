🖨️ Tadiwa Print Buddy

An Offline-First Print Business Operating System

Tadiwa Print Buddy is an offline-first print business operating system built around the real workflow of a small print shop: order intake, quotations, payment collection, debt tracking, stock movement, customer history, financial reporting, and operational notes.

This repository contains the Android app, a FastAPI backend, and a web dashboard layer.

The underlying goal is not just to build an app, but to create a practical business operating system that can run without internet, maintain trusted records locally, and synchronize reliably with a cloud layer.

📑 Table of Contents
Project Overview
What the Project Contains
Android Application
Backend API
Web Dashboard and Sync Layer
Project Screenshots
Current Project Status
Project Goals
Repository Structure
Development Setup and Commands
Project Purpose
🚀 Project Overview

Tadiwa Print Buddy is designed to simplify and digitize the everyday operations of a small printing business.

It focuses on:

Offline-first business operations.
Reliable financial and transaction records.
Customer balance and debt management.
Order processing and payment collection.
Independent print-count tracking.
Stock and expense management.
Business analytics and reporting.
Backup, restoration, and future cloud synchronization.

The system prioritizes local data integrity while providing a foundation for future cloud-connected functionality.

🧩 What the Project Contains
📱 Android Application

Technology: Kotlin, Jetpack, Room Database, MVVM

The Android application is the primary business operations interface.

Key features:

Kotlin-based Android application using MVVM and repository architecture.
Local-first transaction ledger for customers, orders, settlements, stock, expenses, and notes.
Total Number of Prints Made Tracking: First-class, independent printCount tracking per transaction, decoupled from monetary calculations.
Collapsible Customer Balances: Independent summary cards (OWES ME and I OWE CHANGE) with sorting, filtering, and customer management.
Automatic Order Saving: Quick Order on the Home Screen automatically saves valid orders upon selecting Cash, UPI, or Credit payment methods.
Notes Feature: Integrated notes management with sorting, search, and backup/restore coverage.
Offline data integrity checks and reconciliation logic.
Local backup and export support using print_database .zip archives.
Data preservation across backup and restore operations.

Source location: app/

⚙️ Backend API

Technology: Python, FastAPI, SQLAlchemy, PostgreSQL

The backend provides the foundation for authenticated cloud synchronization and server-side business data management.

Key features:

FastAPI application with JWT authentication.
Synchronization endpoints.
PostgreSQL-compatible data layer.
SQLAlchemy models and Alembic migration support.
User-scoped synchronization isolation.
Server-side idempotency.
API routers for authentication, synchronization, analytics, and business data.

Source location: backend/

🌐 Web Dashboard and Sync Layer

Technology: Next.js

The web application provides the dashboard and browser-side synchronization layer.

Key features:

Next.js web application.
Dashboard integration.
Authenticated API wrappers.
Local storage token handling.
Pull and push synchronization primitives.
Outbox queue management.
Data merge logic.
Synchronization diagnostics.
Dashboard monitoring and smoke tests.

Source location: web/

📸 Project Screenshots

The following screenshots showcase the Android application's interface and its core business management functionality.

<table align="center">
<tr>
<td align="center" width="33%">
<strong>1. Main Screen</strong><br/>
<sub>Business overview and quick order access</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/95f915d1-391d-452b-95d2-ce81db103c8b" width="230" alt="Tadiwa Print Buddy main screen"/>
</td>
<td align="center" width="33%">
<strong>2. History Screen</strong><br/>
<sub>Transaction and order history</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/3cc9b61e-3e39-4943-af35-0843ce2cd6ba" width="230" alt="Transaction history screen"/>
</td>
<td align="center" width="33%">
<strong>3. Customer Balances</strong><br/>
<sub>Customer debts and outstanding balances</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/02d56dce-46db-40f6-b975-9cb3e5b3d528" width="230" alt="Customer balances screen"/>
</td>
</tr>
<tr>
<td align="center" width="33%">
<strong>4. Business Analytics</strong><br/>
<sub>Business performance and financial insights</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/0aef651b-4e49-4183-8226-d1c10c10f06d" width="230" alt="Business analytics screen"/>
</td>
<td align="center" width="33%">
<strong>5. Print Calculator</strong><br/>
<sub>Printing calculations and pricing assistance</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/786ccee7-ee45-4a97-a0a3-8e051ee526f2" width="230" alt="Print calculator screen"/>
</td>
<td align="center" width="33%">
<strong>6. About Section</strong><br/>
<sub>Application information</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/d5dbab26-d0a9-4633-bc25-e46a44216332" width="230" alt="About section screen"/>
</td>
</tr>
<tr>
<td align="center" colspan="3">
<strong>7. Settings Screen</strong><br/>
<sub>Application configuration and preferences</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/501ea8bf-25be-4fab-abcb-beb27afac803" width="230" alt="Settings screen"/>
</td>
</tr>
</table>

📊 Current Project Status
✅ Verified and Working

The Android business logic, local data integrity, and analytics have been verified.

Newly Implemented Features
Independent Print Count Tracking
Persisted printCount tracking system.
Independent of monetary calculations.
Reflected in analytics and customer summaries.
Included in backup and restoration.
Collapsible Customer Balance Cards
OWES ME summary.
I OWE CHANGE summary.
Sorting, filtering, and customer management.
Automatic Order Saving
Quick Order automatically saves valid orders.
Payment method selection triggers saving.
Supports Cash, UPI, and Credit.
Fully Integrated Notes Feature
Navigation menu access.
Notes management and search.
Sorting functionality.
Backup and restoration integration.
Comprehensive Automated Testing
OrderValidationTest
NotesFeatureTest
PrintCountIntegrityTest
FinancialIntegrityTest
🎯 Project Goals

The long-term goal is to build a complete and dependable business management system for a real-world print operation.

The main objectives are:

Keep daily business operations fast, automated, and reliable offline.
Preserve accurate financial records with precise decimal handling and independent print-count metrics.
Support customer debt, order processing, settlement history, and operational notes without confusion.
Introduce cloud synchronization and web access without compromising the local-first trust model.
Make the application useful as a practical everyday business management tool.
📂 Repository Structure
text
Tadiwa-Print-Buddy/
│
├── app/
│   └── Android application source
│
├── backend/
│   ├── FastAPI application
│   ├── API routers
│   ├── Database models
│   ├── Alembic migrations
│   └── Backend tests
│
├── web/
│   ├── Next.js application
│   ├── Dashboard
│   └── Synchronization layer
│
├── docs/
│   └── Project documentation
│
├── CURRENT_PROJECT_STATE.md
├── LOGIC_ISSUES_LOG.md
└── README.md

Important Project Documentation
CURRENT_PROJECT_STATE.md — Session resume point for developers and AI coding agents.
LOGIC_ISSUES_LOG.md — Known business logic issues, design decisions, and development logs.
🛠️ Development Setup and Commands
📱 Android Development

From Windows PowerShell:

Compile Android application Kotlin:

powershell
.\gradlew.bat :app:compileDebugKotlin


Compile unit-test Kotlin sources:

powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin


Run an individual unit test:

powershell
.\gradlew.bat :app:testDebugUnitTest --tests "fully.qualified.TestClass.test method name"


Run the complete Android unit-test suite:

powershell
.\gradlew.bat :app:testDebugUnitTest

⚙️ Backend Development

Navigate to the backend/ directory.

Set up Python 3.11 environment.

Install dependencies:

bash
pip install -r requirements.txt


Run the FastAPI server:

bash
uvicorn app.main:app --host 127.0.0.1 --port 8000


Run backend tests:

bash
python -m pytest -q

🌐 Web Dashboard Development

Navigate to the web/ directory.

Install dependencies:

bash
npm install


Start the development server:

bash
npm run dev


Build the production application:

bash
npm run build

💡 Project Purpose

Tadiwa Print Buddy is built around a simple idea:

A small print business deserves a reliable business management system without depending on a constant internet connection.

The project combines local-first Android operations, dependable financial records, customer management, business analytics, and a controlled transition toward cloud synchronization.

The ultimate objective is to make everyday print business operations more organized, transparent, and efficient.

Last updated: September 2026

Project: Tadiwa Print Buddy

Focus: Offline-first print business management and controlled cloud synchronization.
