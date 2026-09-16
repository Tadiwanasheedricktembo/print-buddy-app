"use client";

import { useEffect, useState } from "react";
import { fetchWithAuth } from "@/lib/api";
import { Customer } from "@/lib/types";
import { Search, User } from "lucide-react";
import Link from "next/link";

export default function CustomersPage() {
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");

  useEffect(() => {
    async function loadCustomers() {
      try {
        const data = await fetchWithAuth("/business/customers");
        setCustomers(data.items);
      } catch (err) {
        console.error("Failed to load customers", err);
      } finally {
        setLoading(false);
      }
    }
    loadCustomers();
  }, []);

  const filtered = customers.filter(c =>
    c.displayName.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="space-y-8">
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-2xl font-bold mb-2">Customers</h1>
          <p className="text-white/40">Manage client identities and accounts</p>
        </div>

        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-white/20" size={18} />
          <input
            type="text"
            placeholder="Search customers..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="bg-secondary border border-white/5 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-primary w-64"
          />
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {loading ? (
          [...Array(6)].map((_, i) => (
            <div key={i} className="bg-secondary p-6 rounded-2xl border border-white/5 animate-pulse h-32" />
          ))
        ) : filtered.map((customer) => (
          <Link
            key={customer.syncId}
            href={`/dashboard/customers/${customer.syncId}`}
            className="bg-secondary p-6 rounded-2xl border border-white/5 hover:border-primary/30 transition-all group"
          >
            <div className="flex items-center gap-4 mb-4">
              <div className="p-3 bg-white/5 rounded-full group-hover:bg-primary/10 transition-colors">
                <User size={24} className="text-white/40 group-hover:text-primary transition-colors" />
              </div>
              <div>
                <h3 className="font-bold text-lg">{customer.displayName}</h3>
                <p className="text-xs text-white/40 font-mono">{customer.phoneNumber || "No phone"}</p>
              </div>
            </div>
            <div className="flex justify-between items-center pt-4 border-t border-white/5">
              <span className="text-[10px] uppercase font-bold text-white/20 tracking-wider">Authoritative Ledger</span>
              <span className="text-primary text-xs font-bold">View History →</span>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
