"use client";

import { useEffect, useState } from "react";
import { fetchWithAuth } from "@/lib/api";
import { Order } from "@/lib/types";
import { format } from "date-fns";
import { Search, Calendar, AlertCircle } from "lucide-react";
import { formatCurrency } from "@/lib/format";

export default function HistoryPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  useEffect(() => {
    async function loadOrders() {
      try {
        const data = await fetchWithAuth("/business/orders");
        setOrders(data.items);
      } catch (err: any) {
        console.error("Failed to load orders", err);
        setError(err.message || "Failed to load order history");
      } finally {
        setLoading(false);
      }
    }
    loadOrders();
  }, []);

  const filteredOrders = orders.filter(o =>
    o.customerName.toLowerCase().includes(search.toLowerCase()) ||
    o.id.toString().includes(search)
  );

  if (error) {
      return (
          <div className="bg-red-500/10 border border-red-500/20 rounded-2xl p-8 flex flex-col items-center gap-4 text-center">
              <AlertCircle className="text-red-500" size={48} />
              <div>
                  <h2 className="text-lg font-bold text-red-500">Error Loading History</h2>
                  <p className="text-white/60">{error}</p>
              </div>
              <button onClick={() => window.location.reload()} className="bg-white/10 hover:bg-white/20 px-4 py-2 rounded-lg transition-colors">
                  Try Again
              </button>
          </div>
      );
  }

  return (
    <div className="space-y-8">
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-2xl font-bold mb-2">Order History</h1>
          <p className="text-white/40">Manage and track all customer orders</p>
        </div>

        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-white/20" size={18} />
          <input
            type="text"
            placeholder="Search orders..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="bg-secondary border border-white/5 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-primary w-64"
          />
        </div>
      </div>

      <div className="bg-secondary rounded-2xl border border-white/5 overflow-hidden">
        <table className="w-full text-left">
          <thead className="bg-white/5 text-xs font-bold uppercase text-white/40">
            <tr>
              <th className="px-6 py-4">Order ID</th>
              <th className="px-6 py-4">Date</th>
              <th className="px-6 py-4">Customer</th>
              <th className="px-6 py-4">Amount</th>
              <th className="px-6 py-4">Payment</th>
              <th className="px-6 py-4">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/5">
            {loading ? (
              [...Array(5)].map((_, i) => (
                <tr key={i} className="animate-pulse">
                  <td colSpan={6} className="px-6 py-4 h-16 bg-white/[0.02]" />
                </tr>
              ))
            ) : filteredOrders.map((order) => (
              <tr key={order.syncId} className="hover:bg-white/[0.02] transition-colors group">
                <td className="px-6 py-4 font-mono text-white/60">#{order.id}</td>
                <td className="px-6 py-4 text-sm">
                  <div className="flex items-center gap-2 text-white/60">
                    <Calendar size={14} />
                    {format(new Date(order.date), "dd MMM yyyy, HH:mm")}
                  </div>
                </td>
                <td className="px-6 py-4 font-bold">{order.customerName}</td>
                <td className="px-6 py-4 font-bold text-primary">{formatCurrency(order.totalAmount)}</td>
                <td className="px-6 py-4">
                  <span className={`text-[10px] font-bold px-2 py-1 rounded ${
                    order.paymentMethod === "CASH" ? "bg-green-500/10 text-green-500" : "bg-blue-500/10 text-blue-500"
                  }`}>
                    {order.paymentMethod}
                  </span>
                </td>
                <td className="px-6 py-4">
                  <span className={`text-[10px] font-bold px-2 py-1 rounded ${
                    order.orderStatus === "ACTIVE" ? "bg-primary/10 text-primary" : "bg-red-500/10 text-red-500"
                  }`}>
                    {order.orderStatus}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!loading && filteredOrders.length === 0 && (
          <div className="p-12 text-center text-white/20 italic">No orders found</div>
        )}
      </div>
    </div>
  );
}
