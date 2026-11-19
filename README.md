Tadiwa Print Buddy

An Offline-First Print Business Operating System

Tadiwa Print Buddy is an offline-first business management system designed around the everyday operations of a small print shop. It brings together order management, quotations, payment collection, customer balances, stock tracking, financial reporting, and operational notes in one application.

This repository contains three main components:

Android Application: The primary business operations interface.
Backend API: A FastAPI service providing authentication and synchronization.
Web Dashboard: A Next.js application for browser-based access and synchronization management.

The project's core principle is simple: business operations should remain reliable even without an internet connection.

Table of Contents
Project Overview
System Architecture
Core Features
Project Screenshots
Current Project Status
Project Goals
Repository Structure
Development Setup
Documentation
Project Overview

Tadiwa Print Buddy is designed to simplify and digitize the daily operations of a small printing business.

The application focuses on:

Offline-first business operations.
Accurate financial and transaction records.
Customer debt and balance management.
Order processing and payment collection.
Independent print-count tracking.
Stock and expense management.
Business analytics and reporting.
Local backup and restoration.
Future cloud synchronization.

The system prioritizes local data integrity, financial accuracy, and operational reliability while providing a foundation for cloud-connected functionality.

System Architecture

The project consists of three interconnected components.

1. Android Application

Technology Stack: Kotlin, Jetpack, Room Database, MVVM

The Android application is the primary interface for managing print business operations.

Key capabilities:

Local-first transaction ledger covering customers, orders, settlements, stock, expenses, and notes.
Independent printCount tracking for every transaction, separate from monetary calculations.
Collapsible customer balance summaries:
OWES ME
I OWE CHANGE
Automatic order saving when selecting Cash, UPI, or Credit payment methods.
Integrated notes management with sorting and search.
Offline data integrity checks and reconciliation.
Local backup and export using print_database ZIP archives.
Backup restoration designed to preserve business data.

Source directory: app/

2. Backend API

Technology Stack: Python, FastAPI, SQLAlchemy, PostgreSQL

The backend provides authentication, synchronization, and server-side business data management.

Key capabilities:

JWT-based authentication.
Synchronization endpoints.
PostgreSQL-compatible database layer.
SQLAlchemy models.
Alembic database migrations.
User-scoped synchronization isolation.
Server-side idempotency.
API routers for authentication, synchronization, analytics, and business data.

Source directory: backend/

3. Web Dashboard and Synchronization Layer

Technology Stack: Next.js

The web application provides browser-based dashboard functionality and synchronization infrastructure.

Key capabilities:

Next.js dashboard application.
Authenticated API wrappers.
Local storage token handling.
Pull and push synchronization.
Outbox queue management.
Data merge logic.
Synchronization diagnostics.
Dashboard monitoring and smoke tests.

Source directory: web/

Core Features
Business and Order Management
Quick order creation.
Automatic order saving.
Cash, UPI, and Credit payment methods.
Transaction history.
Customer management.
Financial Management
Customer debt tracking.
Outstanding balance summaries.
Settlement history.
Expense tracking.
Financial analytics.
Precise decimal handling.
Print Tracking
Independent printCount tracking.
Print quantities stored separately from transaction amounts.
Print-count reporting and analytics.
Backup and restoration support.
Notes Management
Create and manage operational notes.
Search and sorting functionality.
Navigation menu integration.
Backup and restoration coverage.
Data Reliability
Offline-first database operations.
Data integrity checks.
Transaction reconciliation.
Local ZIP backup and export.
Data restoration support.
Foundation for cloud synchronization.
Project Screenshots

The following screenshots showcase the Android application's interface and its main business management features.

<table align="center">
<tr>
<td align="center" width="33%">
<strong>Main Screen</strong><br/>
<sub>Business overview and quick order access</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/95f915d1-391d-452b-95d2-ce81db103c8b" width="230" alt="Main screen"/>
</td>
<td align="center" width="33%">
<strong>History Screen</strong><br/>
<sub>Transaction and order history</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/3cc9b61e-3e39-4943-af35-0843ce2cd6ba" width="230" alt="History screen"/>
</td>
<td align="center" width="33%">
<strong>Customer Balances</strong><br/>
<sub>Customer debts and outstanding balances</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/02d56dce-46db-40f6-b975-9cb3e5b3d528" width="230" alt="Customer balances"/>
</td>
</tr>
<tr>
<td align="center" width="33%">
<strong>Business Analytics</strong><br/>
<sub>Financial insights and business performance</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/0aef651b-4e49-4183-8226-d1c10c10f06d" width="230" alt="Business analytics"/>
</td>
<td align="center" width="33%">
<strong>Print Calculator</strong><br/>
<sub>Printing calculations and pricing assistance</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/786ccee7-ee45-4a97-a0a3-8e051ee526f2" width="230" alt="Print calculator"/>
</td>
<td align="center" width="33%">
<strong>About Section</strong><br/>
<sub>Application information</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/d5dbab26-d0a9-4633-bc25-e46a44216332" width="230" alt="About section"/>
</td>
</tr>
<tr>
<td align="center" colspan="3">
<strong>Settings Screen</strong><br/>
<sub>Application configuration and preferences</sub><br/><br/>
<img src="https://github.com/user-attachments/assets/501ea8bf-25be-4fab-abcb-beb27afac803" width="230" alt="Settings screen"/>
</td>
</tr>
</table>

Current Project Status
Verified and Working

The Android business logic, local data integrity, and analytics have been verified.

Recently Implemented
Feature	Description
Print Count Tracking	Independent printCount persistence, analytics, and backup support
Customer Balances	Collapsible OWES ME and I OWE CHANGE summary cards
Automatic Order Saving	Automatic saving after Cash, UPI, or Credit selection
Notes Management	Integrated notes, search, sorting, and backup support
Automated Testing	Unit and integration test coverage for core business logic
Automated Tests

The following test suites have been implemented:

OrderValidationTest
NotesFeatureTest
PrintCountIntegrityTest
FinancialIntegrityTest
Project Goals

The long-term objective is to build a complete and dependable management system for a real-world print business.

The main goals are:

Operational Efficiency: Keep daily business operations fast, automated, and reliable offline.
Financial Accuracy: Preserve accurate financial records using precise decimal handling.
Customer Management: Support customer debt, order processing, and settlement history.
Business Intelligence: Provide useful print-count metrics and financial analytics.
Cloud Integration: Introduce synchronization and web access without compromising local-first reliability.
Practical Usability: Maintain a system that is genuinely useful in everyday business operations.
Repository Structure
text
Tadiwa-Print-Buddy/
│
├── app/                         # Android application
│
├── backend/                     # FastAPI backend
│   ├── app/                     # API application
│   ├── migrations/              # Database migrations
│   └── tests/                   # Backend tests
│
├── web/                         # Next.js dashboard
│
├── docs/                        # Project documentation
│
├── CURRENT_PROJECT_STATE.md     # Development resume point
├── LOGIC_ISSUES_LOG.md          # Business logic issues and design logs
└── README.md

Development Setup
Android Development

Run the following commands from Windows PowerShell.

Compile application Kotlin:

powershell
.\gradlew.bat :app:compileDebugKotlin


Compile unit-test Kotlin:

powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin


Run an individual unit test:

powershell
.\gradlew.bat :app:testDebugUnitTest --tests "fully.qualified.TestClass.test method name"


Run the complete unit-test suite:

powershell
.\gradlew.bat :app:testDebugUnitTest

Backend Development

Navigate to the backend/ directory.

Install dependencies:

bash
pip install -r requirements.txt


Start the API server:

bash
uvicorn app.main:app --host 127.0.0.1 --port 8000


Run backend tests:

bash
python -m pytest -q

Web Dashboard Development

Navigate to the web/ directory.

Install dependencies:

bash
npm install


Start development server:

bash
npm run dev


Build production application:

bash
npm run build

Documentation

Additional project documentation is available in the repository:

CURRENT_PROJECT_STATE.md — Current project status and development resume point.
LOGIC_ISSUES_LOG.md — Known business logic issues, design decisions, and development notes.
Project Purpose

Tadiwa Print Buddy is built around one central principle:

A small print business deserves a reliable management system that does not depend on a constant internet connection.

By combining offline-first Android operations, accurate financial records, customer management, business analytics, and a controlled transition toward cloud synchronization, the project aims to make everyday print business operations more organized, transparent, and efficient.

Project: Tadiwa Print Buddy

Architecture: Android + FastAPI + Next.js

Development Focus: Offline-first business management and reliable cloud synchronization.

Last Updated: September 2026
