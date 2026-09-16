"use client";

import { useState } from "react";
import { createCustomerSyncEvent, pushSync, runSyncDiagnostics, useSync } from "@/lib/sync";

export default function SyncPage() {
  const [status, setStatus] = useState("Idle");
  const [loading, setLoading] = useState(false);
  const [lastEvent, setLastEvent] = useState<string | null>(null);
  const [diagnosticResult, setDiagnosticResult] = useState<string | null>(null);
  const { syncNow } = useSync();

  const handlePushDemoCustomer = async () => {
    setLoading(true);
    setStatus("Creating demo customer sync event...");

    try {
      const event = createCustomerSyncEvent(
        {
          displayName: `Demo Customer ${Date.now()}`,
          normalizedName: "demo customer",
          phoneNumber: "+263000000000",
        },
        "CREATE",
      );

      setLastEvent(JSON.stringify(event, null, 2));
      const result = await pushSync([event]);

      if (result.results?.[0]?.status) {
        setStatus(`Push result: ${result.results[0].status}`);
      } else {
        setStatus("Push completed");
      }

      await syncNow();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown upload error";
      setStatus(`Push failed: ${message}`);
    } finally {
      setLoading(false);
    }
  };

  const handleRunDiagnostics = async () => {
    setLoading(true);
    setStatus("Running sync diagnostics...");

    try {
      const result = await runSyncDiagnostics();
      setDiagnosticResult(JSON.stringify(result, null, 2));
      setStatus(
        result.pull.ok && result.push.ok
          ? "Diagnostics passed"
          : "Diagnostics reported issues",
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown diagnostics error";
      setDiagnosticResult(message);
      setStatus(`Diagnostics failed: ${message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold mb-2">Sync Test</h1>
        <p className="text-white/40">
          Sends a generated customer sync event to the backend and then refreshes from the sync pull API.
        </p>
      </div>

      <div className="rounded-2xl border border-white/10 bg-secondary p-6">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div>
            <p className="text-sm text-white/40 uppercase tracking-[0.2em]">Status</p>
            <p className="mt-2 text-lg font-semibold text-primary">{status}</p>
          </div>

          <div className="flex gap-3 flex-wrap">
            <button
              type="button"
              onClick={handlePushDemoCustomer}
              disabled={loading}
              className="rounded-lg bg-primary px-4 py-2 font-bold text-black transition hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {loading ? "Pushing..." : "Push demo customer"}
            </button>
            <button
              type="button"
              onClick={handleRunDiagnostics}
              disabled={loading}
              className="rounded-lg border border-white/10 bg-white/5 px-4 py-2 font-bold text-white transition hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {loading ? "Running..." : "Run diagnostics"}
            </button>
          </div>
        </div>
      </div>

      {diagnosticResult && (
        <div className="rounded-2xl border border-white/10 bg-secondary p-6">
          <p className="mb-3 text-sm uppercase tracking-[0.2em] text-white/40">Diagnostics output</p>
          <pre className="overflow-x-auto whitespace-pre-wrap text-xs text-white/80">{diagnosticResult}</pre>
        </div>
      )}

      {lastEvent && (
        <div className="rounded-2xl border border-white/10 bg-secondary p-6">
          <p className="mb-3 text-sm uppercase tracking-[0.2em] text-white/40">Last event payload</p>
          <pre className="overflow-x-auto whitespace-pre-wrap text-xs text-white/80">{lastEvent}</pre>
        </div>
      )}
    </div>
  );
}
