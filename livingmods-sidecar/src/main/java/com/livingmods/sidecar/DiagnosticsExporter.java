package com.livingmods.sidecar;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.SimulationEngine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight metrics snapshot for logs, dashboard, and SAVE_RESPONSE payloads. */
public final class DiagnosticsExporter {
    private final SimulationEngine engine;
    private final CanonicalWorldState state;
    private final AtomicLong messagesIn = new AtomicLong();
    private final AtomicLong messagesOut = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final AtomicLong lastStepNanos = new AtomicLong();
    private final AtomicInteger queuedIpc = new AtomicInteger();
    private final AtomicInteger pendingRequests = new AtomicInteger();
    private final AtomicInteger subscriptions = new AtomicInteger();

    public DiagnosticsExporter(SimulationEngine engine, CanonicalWorldState state) {
        this.engine = engine;
        this.state = state;
    }

    public void recordInbound(int bytes) {
        messagesIn.incrementAndGet();
        bytesIn.addAndGet(bytes);
    }

    public void recordOutbound(int bytes) {
        messagesOut.incrementAndGet();
        bytesOut.addAndGet(bytes);
    }

    public void recordStepNanos(long nanos) {
        lastStepNanos.set(nanos);
    }

    public void setQueuedIpc(int depth) {
        queuedIpc.set(Math.max(0, depth));
    }

    public void setPendingRequests(int pending) {
        pendingRequests.set(Math.max(0, pending));
    }

    public void setSubscriptions(int count) {
        subscriptions.set(Math.max(0, count));
    }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("stepNanos", String.valueOf(lastStepNanos.get()));
        m.put("stepDurationMs", String.format(java.util.Locale.ROOT, "%.2f", lastStepNanos.get() / 1_000_000.0));
        m.put("queueDepth", String.valueOf(queuedIpc.get()));
        m.put("queuedIpc", String.valueOf(queuedIpc.get()));
        m.put("pendingRequests", String.valueOf(pendingRequests.get()));
        m.put("subscriptions", String.valueOf(subscriptions.get()));
        m.put("citizens", String.valueOf(state.citizens().size()));
        m.put("settlements", String.valueOf(state.settlements().size()));
        m.put("shipments", String.valueOf(state.shipments().size()));
        m.put("wars", String.valueOf(state.wars().size()));
        m.put("epidemics", String.valueOf(state.epidemics().size()));
        m.put("ipcIn", String.valueOf(messagesIn.get()));
        m.put("ipcOut", String.valueOf(messagesOut.get()));
        m.put("ipcBytesIn", String.valueOf(bytesIn.get()));
        m.put("ipcBytesOut", String.valueOf(bytesOut.get()));
        m.put("simTicks", String.valueOf(state.time().absoluteTicks()));
        m.put("revision", String.valueOf(state.saveRevision()));
        m.put("workers", String.valueOf(engine.workerCount()));
        m.put("pid", String.valueOf(ProcessHandle.current().pid()));
        return m;
    }
}
