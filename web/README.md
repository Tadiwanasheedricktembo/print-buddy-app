# Tadiwa Print Buddy — Web Dashboard

A responsive management dashboard built with Next.js, TypeScript, and Tailwind CSS.

## Features
- **Business Overview**: Real-time revenue trends and service performance metrics.
- **Order History**: Searchable and detailed order tracking.
- **Customer Ledgers**: Authoritative settlement history and balance management.
- **Security**: JWT-based authentication shared with the Android application.

## Tech Stack
- **Framework**: [Next.js 14 (App Router)](https://nextjs.org/)
- **Styling**: [Tailwind CSS](https://tailwindcss.com/)
- **Icons**: [Lucide React](https://lucide.dev/)
- **Charts**: [Recharts](https://recharts.org/)
- **Language**: TypeScript

## Getting Started

### Prerequisites
- Node.js 18.17 or later

### Installation
```bash
cd web
npm install
```

### Running Locally
```bash
npm run dev
```

The dashboard will be available at [http://localhost:3000](http://localhost:3000).

### Backend Connection
The dashboard connects to the FastAPI backend. Ensure the backend is running at `http://localhost:8000`. The `next.config.mjs` is configured to proxy `/api/v1` requests to the local backend.
