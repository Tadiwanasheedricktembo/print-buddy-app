# Tadiwa Print Buddy

Tadiwa Print Buddy is a robust Android application designed to help small to medium-sized printing businesses manage their daily operations, finances, and customer relationships efficiently.

## Features

### 1. Order Management
- **Quick Order Creation:** Easily add services (Passport Photos, A4 Printing, Business Cards, etc.) to a cart and generate orders.
- **Order History:** View a complete history of all past orders with detailed line items.
- **Service Customization:** Pre-defined services with set prices for rapid entry.

### 2. Financial Tracking & Analytics
- **Dashboard:** A high-level overview of business performance featuring:
    - Total Revenue
    - Total Order Count
    - Today's Revenue
- **Visual Analytics:** Integrated Pie Charts (via MPAndroidChart) to visualize revenue distribution across different service categories.
- **Daily Performance:** Track revenue trends over the last 7 days.

### 3. Debt & Credit Management
- **Debtor Tracking:** Keep track of customers with outstanding balances.
- **Payment Allocation:** Smart logic to apply customer payments across multiple unpaid orders.
- **Credit Records:** Manage customer credits and balance adjustments.

### 4. Media & References
- **Order Photos:** Attach and view photos related to specific orders for better record-keeping.
- **Printer References:** Maintain a list of printer settings or references for consistent output quality.

## Tech Stack

- **Language:** Kotlin
- **Architecture:** MVVM (Model-View-ViewModel)
- **Database:** Room Persistence Library for local data storage.
- **UI:** ViewBinding and Material Design Components.
- **Concurrency:** Kotlin Coroutines for asynchronous database operations.
- **Charts:** MPAndroidChart for data visualization.
- **Image Handling:** PhotoView for interactive image viewing.

## Getting Started

### Prerequisites
- Android Studio Iguana or newer.
- Android SDK 24 (Nougat) or higher.

### Installation
1. Clone the repository.
2. Open the project in Android Studio.
3. Sync Project with Gradle Files.
4. Run the app on an emulator or a physical device.

## Project Structure
- `data/`: Contains Room entities, DAOs, and the Repository.
- `ui/`: (If applicable) UI-related components.
- `MainActivity.kt`: The main entry point for creating orders.
- `DashboardActivity.kt`: Business analytics and charts.
- `DebtorsActivity.kt`: Management of unpaid orders and credits.

---
*Developed by Tadiwa*
