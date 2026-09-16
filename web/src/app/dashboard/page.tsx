"use client";

import { useEffect, useState } from "react";
import { fetchWithAuth } from "@/lib/api";
import { useSync } from "@/lib/sync";
import { AnalyticsSummary, RevenuePoint, ServiceBreakdown } from "@/lib/types";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from "recharts";
import { Banknote, ShoppingBag, Users, AlertCircle, TrendingUp } from "lucide-react";
import { formatCurrency, formatNumber } from "@/lib/format";

export default function DashboardPage() {
  const [summary, setSummary] = useState<AnalyticsSummary | null>(null);
  const [revenueData, setRevenueData] = useState<RevenuePoint[]>([]);
  const [serviceData, setServiceData] = useState<ServiceBreakdown[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncMessage, setSyncMessage] = useState("Waiting for sync");
  const { syncNow, isSyncing, error } = useSync();

  useEffect(() => {
    async function loadData() {
      try {
        const [sum, rev, serv] = await Promise.all([
          fetchWithAuth("/analytics/summary"),
          fetchWithAuth("/analytics/revenue-chart"),
          fetchWithAuth("/analytics/service-breakdown"),
        ]);
        setSummary(sum);
        setRevenueData(rev);
        setServiceData(serv);
      } catch (err) {
        console.error("Failed to load dashboard data", err);
      } finally {
        setLoading(false);
      }
    }
    loadData();
    const interval = setInterval(loadData, 30000); // 30s Poll for "quasi-real-time"
    return () => clearInterval(interval);
  }, []);

  if (loading) return <div className="p-8 text-white/50 animate-pulse font-mono">LOADING METRICS...</div>;

  const handleSyncNow = async () => {
    setSyncMessage("Running sync pull…");
    try {
      await syncNow();
      setSyncMessage("Sync pull completed");
    } catch {
      setSyncMessage("Sync pull failed");
    }
  };

  const stats = [
    {
        name: "Total Revenue",
        value: summary ? formatCurrency(summary.total_revenue) : "₹0.00",
        icon: Banknote,
        color: "text-primary"
    },
    {
        name: "Total Orders",
        value: summary?.order_count.toString() || "0",
        icon: ShoppingBag,
        color: "text-blue-400"
    },
    {
        name: "Outstanding Debt",
        value: summary ? formatCurrency(summary.outstanding_debt) : "₹0.00",
        icon: AlertCircle,
        color: "text-red-400"
    },
    {
        name: "Total Customers",
        value: summary?.customer_count.toString() || "0",
        icon: Users,
        color: "text-purple-400"
    },
  ];

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold mb-2">Business Overview</h1>
          <p className="text-white/40">Real-time performance metrics</p>
        </div>
        <button
          type="button"
          onClick={handleSyncNow}
          disabled={isSyncing}
          className="rounded-lg border border-primary/40 bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition hover:bg-primary/20 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isSyncing ? "Syncing…" : "Run sync now"}
        </button>
      </div>

      <div className="rounded-lg border border-white/10 bg-secondary/70 px-4 py-3 text-sm text-white/70">
        <span className={error ? "text-red-400" : "text-primary"}>
          {error ? `Sync issue: ${error}` : syncMessage}
        </span>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {stats.map((stat) => {
          const Icon = stat.icon;
          return (
            <div key={stat.name} className="bg-secondary p-6 rounded-2xl border border-white/5">
              <div className="flex items-center justify-between mb-4">
                <div className={`p-2 bg-white/5 rounded-lg ${stat.color}`}>
                  <Icon size={24} />
                </div>
                <TrendingUp size={16} className="text-primary opacity-50" />
              </div>
              <p className="text-white/40 text-sm mb-1">{stat.name}</p>
              <p className="text-2xl font-bold">{stat.value}</p>
            </div>
          );
        })}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* Revenue Trend */}
        <div className="lg:col-span-2 bg-secondary p-8 rounded-2xl border border-white/5">
          <h2 className="text-lg font-bold mb-8">Revenue Trend (30 Days)</h2>
          <div className="h-80 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={revenueData.map(d => ({ ...d, amount: parseFloat(d.amount) }))}>
                <CartesianGrid strokeDasharray="3 3" stroke="#ffffff10" vertical={false} />
                <XAxis dataKey="date" stroke="#ffffff40" fontSize={12} tickMargin={10} />
                <YAxis stroke="#ffffff40" fontSize={12} tickFormatter={(val) => `₹${val}`} />
                <Tooltip
                  contentStyle={{ backgroundColor: "#1A1A1A", borderColor: "#ffffff10" }}
                  itemStyle={{ color: "#00C853" }}
                  formatter={(val: number) => formatCurrency(val)}
                />
                <Line
                    type="monotone"
                    dataKey="amount"
                    stroke="#00C853"
                    strokeWidth={3}
                    dot={{ r: 4, fill: "#00C853", strokeWidth: 0 }}
                    activeDot={{ r: 6 }}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Service Breakdown */}
        <div className="bg-secondary p-8 rounded-2xl border border-white/5">
          <h2 className="text-lg font-bold mb-8">Popular Services</h2>
          <div className="space-y-6">
            {serviceData.map((service) => {
              const totalRevenue = parseFloat(summary?.total_revenue || "0");
              const serviceAmount = parseFloat(service.total_amount);
              const percentage = totalRevenue > 0 ? (serviceAmount / totalRevenue) * 100 : 0;

              return (
                <div key={service.service_name}>
                  <div className="flex justify-between text-sm mb-2">
                    <span className="text-white/60">{service.service_name}</span>
                    <span className="font-bold text-primary">{formatCurrency(service.total_amount)}</span>
                  </div>
                  <div className="w-full bg-white/5 h-2 rounded-full overflow-hidden">
                    <div
                      className="bg-primary h-full rounded-full"
                      style={{ width: `${percentage}%` }}
                    />
                  </div>
                </div>
              );
            })}
            {serviceData.length === 0 && <p className="text-white/20 italic">No data yet</p>}
          </div>
        </div>
      </div>
    </div>
  );
}
