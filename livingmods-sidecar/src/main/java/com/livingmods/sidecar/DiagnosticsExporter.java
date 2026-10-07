package com.livingmods.sidecar;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.SimulationEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight metrics snapshot for logs and SAVE_RESPONSE payloads. */
public final class DiagnosticsExporter {
    private final SimulationEngine engine;
    private final CanonicalWorldState state;
    private long messagesIn;
    private long messagesOut;
    private long bytesIn;
    private long bytesOut;
    private long lastStepNanos;

    public DiagnosticsExporter(SimulationEngine engine, CanonicalWorldState state) {
        this.engine = engine;
        this.state = state;
    }

    public void recordInbound(int bytes) {
        messagesIn++;
        bytesIn += bytes;
    }

    public void recordOutbound(int bytes) {
        messagesOut++;
        bytesOut += bytes;
    }

    public void recordStepNanos(long nanos) {
        lastStepNanos = nanos;
    }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("stepNanos", String.valueOf(lastStepNanos));
        m.put("queueDepth", "0");
        m.put("citizens", String.valueOf(state.citizens().size()));
        m.put("wars", String.valueOf(state.wars().size()));
        m.put("ipcIn", String.valueOf(messagesIn));
        m.put("ipcOut", String.valueOf(messagesOut));
        m.put("ipcBytesIn", String.valueOf(bytesIn));
        m.put("ipcBytesOut", String.valueOf(bytesOut));
        m.put("simTicks", String.valueOf(state.time().absoluteTicks()));
        m.put("workers", String.valueOf(engine.workerCount()));
        return m;
    }
}
