"use client";

import { useEffect, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import { isLoggedIn, removeToken } from "@/lib/auth";
import { useSync } from "@/lib/sync";
import Link from "next/link";
import { LayoutDashboard, History, Users, Wallet, LogOut, Printer, Menu, X } from "lucide-react";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const [loading, setLoading] = useState(true);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { isSyncing, error } = useSync();

  useEffect(() => {
    if (!isLoggedIn()) {
      router.push("/login");
    } else {
      setLoading(false);
    }
  }, [router]);

  const handleLogout = () => {
    removeToken();
    router.push("/login");
  };

  if (loading) return <div className="min-h-screen bg-background flex items-center justify-center text-primary font-mono">LOADING...</div>;

  const navItems = [
    { name: "Overview", href: "/dashboard", icon: LayoutDashboard },
    { name: "Sync Test", href: "/dashboard/sync", icon: Printer },
    { name: "Order History", href: "/dashboard/history", icon: History },
    { name: "Customers", href: "/dashboard/customers", icon: Users },
    { name: "Beauty Account", href: "/dashboard/beauty", icon: Wallet },
  ];

  return (
    <div className="min-h-screen bg-background flex flex-col md:flex-row">
      {/* Mobile Header */}
      <header className="md:hidden bg-secondary border-b border-white/5 p-4 flex items-center justify-between sticky top-0 z-50">
        <div className="flex items-center gap-2">
            <Printer className="text-primary w-6 h-6" />
            <span className="font-bold">PRINT BUDDY</span>
        </div>
        <button onClick={() => setSidebarOpen(!sidebarOpen)} className="p-2 text-white/60">
            {sidebarOpen ? <X /> : <Menu />}
        </button>
      </header>

      {/* Sidebar */}
      <aside className={`
        fixed inset-y-0 left-0 z-40 w-64 bg-secondary border-r border-white/5 flex flex-col transition-transform duration-300 md:relative md:translate-x-0
        ${sidebarOpen ? "translate-x-0" : "-translate-x-full"}
      `}>
        <div className="p-6 border-b border-white/5 hidden md:block">
          <div className="flex items-center gap-2">
            <Printer className="text-primary w-6 h-6" />
            <span className="font-bold text-lg">PRINT BUDDY</span>
          </div>
        </div>

        <nav className="flex-1 p-4 space-y-2 overflow-y-auto">
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                onClick={() => setSidebarOpen(false)}
                className={`flex items-center gap-3 px-4 py-3 rounded-lg transition-colors ${
                  active ? "bg-primary text-black font-bold" : "text-white/60 hover:bg-white/5 hover:text-white"
                }`}
              >
                <Icon size={20} />
                {item.name}
              </Link>
            );
          })}
        </nav>

        <div className="p-4 border-t border-white/5">
          <button
            onClick={handleLogout}
            className="flex items-center gap-3 px-4 py-3 w-full text-left text-red-500 hover:bg-red-500/10 rounded-lg transition-colors"
          >
            <LogOut size={20} />
            Logout
          </button>
        </div>
      </aside>

      {/* Overlay for mobile sidebar */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-30 md:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Main Content */}
      <main className="flex-1 p-4 md:p-8 overflow-x-hidden">
        <div className="max-w-7xl mx-auto">
          <div className="mb-4 flex items-center justify-between gap-3 rounded-lg border border-white/10 bg-secondary/80 px-4 py-3 text-sm text-white/70">
            <span>{isSyncing ? "Syncing…" : "Sync ready"}</span>
            <span className={error ? "text-red-400" : "text-primary"}>
              {error ? `Sync issue: ${error}` : isSyncing ? "In progress" : "Healthy"}
            </span>
          </div>
          {children}
        </div>
      </main>
    </div>
  );
}
