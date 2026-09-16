export type SyncStatus = "SYNCED" | "LOCAL_ONLY" | "FAILED";

export interface Customer {
  id: number;
  displayName: string;
  normalizedName: string;
  phoneNumber?: string;
  createdAt: number;
  syncId: string;
}

export interface Order {
  id: number;
  totalAmount: string; // BigDecimal as string from API
  date: number;
  customerName: string;
  paidAmount: string;
  paymentMethod: string;
  customerSyncId: string;
  previousBalance: string;
  transactionAmount: string;
  newBalance: string;
  paymentStatus: "PAID" | "UNPAID" | "PARTIALLY_PAID";
  orderStatus: "ACTIVE" | "CANCELLED";
  receivedAmount?: string;
  syncId: string;
}

export interface SettlementHistory {
  id: number;
  customerName: string;
  customerSyncId: string;
  balanceBefore: string;
  amountPaid: string;
  balanceAfter: string;
  timestamp: number;
  type: string;
  note: string;
  transactionAmount: string;
  newBalance: string;
  originId?: number;
  originSyncId?: string;
  ledgerEntryType: "ORDER_POST" | "PAYMENT" | "CREDIT" | "ADJUSTMENT" | "ORDER_CANCEL";
  reconciliationStatus: string;
  receivedAmount?: string;
  syncId: string;
}

export interface Expense {
  id: number;
  title: string;
  category: string;
  amount: string;
  timestamp: number;
  note?: string;
  paymentMethod: string;
  syncId: string;
}

export interface StockItem {
  id: number;
  name: string;
  currentQuantity: number;
  lowStockThreshold: number;
  unit: string;
  syncId: string;
}

export interface BeautyTransaction {
  id: number;
  amount: string;
  type: "ADD" | "RETURN" | "RESET";
  note?: string;
  timestamp: number;
  previousBalance: string;
  transactionAmount: string;
  newBalance: string;
  syncId: string;
}

export interface AnalyticsSummary {
  total_revenue: string;
  order_count: number;
  outstanding_debt: string;
  customer_count: number;
}

export interface RevenuePoint {
  date: string;
  amount: string;
}

export interface ServiceBreakdown {
  service_name: string;
  total_amount: string;
}
