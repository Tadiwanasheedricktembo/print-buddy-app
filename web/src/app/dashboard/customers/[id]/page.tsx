"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { fetchWithAuth } from "@/lib/api";
import { SettlementHistory } from "@/lib/types";
import { format } from "date-fns";
import { ArrowLeft, Receipt, CreditCard, AlertCircle } from "lucide-react";
import Link from "next/link";
import { formatCurrency } from "@/lib/format";

export default function CustomerLedgerPage() {
  const params = useParams();
  const id = params.id as string;
  const [ledger, setLedger] = useState<SettlementHistory[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function loadLedger() {
      try {
        const data = await fetchWithAuth(`/business/ledger/${id}`);
        setLedger(data);
      } catch (err) {
        console.error("Failed to load ledger", err);
      } finally {
        setLoading(false);
      }
    }
    loadLedger();
  }, [id]);

  if (loading) return <div className="p-8 text-white/50 animate-pulse font-mono">LOADING LEDGER...</div>;

  const currentBalance = ledger[0]?.newBalance || "0";
  const customerName = ledger[0]?.customerName || "Customer";

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-4">
        <Link href="/dashboard/customers" className="p-2 hover:bg-white/5 rounded-lg text-white/40">
          <ArrowLeft size={24} />
        </Link>
        <div>
          <h1 className="text-2xl font-bold">{customerName}</h1>
          <p className="text-white/40">Authoritative Settlement History</p>
        </div>
      </div>

      {/* Balance Card */}
      <div className="bg-secondary p-8 rounded-2xl border border-white/5 flex items-center justify-between">
        <div>
          <p className="text-white/40 text-sm mb-1 uppercase font-bold tracking-widest">Current Balance</p>
          <p className={`text-4xl font-bold ${parseFloat(currentBalance) > 0 ? "text-red-400" : "text-primary"}`}>
            {formatCurrency(currentBalance)}
          </p>
        </div>
        <div className={`p-4 rounded-2xl ${parseFloat(currentBalance) > 0 ? "bg-red-500/10 text-red-500" : "bg-primary/10 text-primary"}`}>
          {parseFloat(currentBalance) > 0 ? <AlertCircle size={48} /> : <CreditCard size={48} />}
        </div>
      </div>

      <div className="bg-secondary rounded-2xl border border-white/5 overflow-hidden">
        <div className="p-6 border-b border-white/5 font-bold uppercase text-xs tracking-widest text-white/40">Activity Timeline</div>
        <div className="divide-y divide-white/5">
          {ledger.map((item) => (
            <div key={item.syncId} className="p-6 hover:bg-white/[0.02] transition-colors flex gap-6">
               <div className={`p-3 rounded-xl h-fit ${
                 item.ledgerEntryType === "ORDER_POST" ? "bg-red-500/10 text-red-500" : "bg-primary/10 text-primary"
               }`}>
                 {item.ledgerEntryType === "ORDER_POST" ? <Receipt size={20} /> : <CreditCard size={20} />}
               </div>
               <div className="flex-1">
                 <div className="flex justify-between items-start mb-1">
                   <h3 className="font-bold">{item.ledgerEntryType.replace("_", " ")}</h3>
                   <span className={`font-mono font-bold ${parseFloat(item.transactionAmount) > 0 ? "text-red-400" : "text-primary"}`}>
                     {parseFloat(item.transactionAmount) > 0 ? "+" : ""}
                     {formatCurrency(item.transactionAmount)}
                   </span>
                 </div>
                 <p className="text-sm text-white/60 mb-2">{item.note}</p>
                 <div className="flex justify-between items-center text-[10px] uppercase font-bold tracking-widest text-white/20">
                   <span>{format(new Date(item.timestamp), "dd MMM yyyy, HH:mm")}</span>
                   <span>New Balance: {formatCurrency(item.newBalance)}</span>
                 </div>
               </div>
            </div>
          ))}
          {ledger.length === 0 && <div className="p-12 text-center text-white/20 italic">No activity recorded</div>}
        </div>
      </div>
    </div>
  );
}
