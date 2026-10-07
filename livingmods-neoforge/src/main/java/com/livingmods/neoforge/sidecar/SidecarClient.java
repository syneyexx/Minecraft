package com.livingmods.neoforge.sidecar;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.model.WorldIdentityContract;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.protocol.BinaryCodec;
import com.livingmods.protocol.Envelope;
import com.livingmods.protocol.ErrorPayload;
import com.livingmods.protocol.HandshakePayload;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.ProtocolConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Framed async IPC client: dedicated reader, single-writer queue, multiple outstanding requests.
 * Never block-reads responses on the caller / Minecraft main thread.
 */
public final class SidecarClient implements AutoCloseable {
    private final UUID worldId;
    private final String host;
    private final int port;
    private final int requestTimeoutMillis;

    private final AtomicReference<ConnectionState> connectionState =
            new AtomicReference<>(ConnectionState.STARTING);
    private final ConcurrentHashMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Envelope> inboundEvents = new ConcurrentLinkedQueue<>();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final AtomicLong nextMessageId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "livingmods-ipc-writer");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "livingmods-ipc-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private volatile Thread readerThread;
    private volatile ScheduledFuture<?> heartbeatTask;

    private volatile long lastHeartbeatSent;
    private volatile long lastHeartbeatReceived;
    private volatile long roundTripLatency = -1L;
    private volatile String lastError = "";

    public SidecarClient(UUID worldId, String host, int port) {
        this(worldId, host, port, LivingModsConfig.defaults().ipcRequestTimeoutMillis());
    }

    public SidecarClient(UUID worldId, String host, int port, int requestTimeoutMillis) {
        this.worldId = worldId;
        this.host = host;
        this.port = port;
        this.requestTimeoutMillis = Math.max(100, requestTimeoutMillis);
    }

    public ConnectionState connectionState() {
        return connectionState.get();
    }

    public boolean isReady() {
        return connectionState.get() == ConnectionState.READY;
    }

    /** True when the client is not ready for gameplay queries. */
    public boolean degraded() {
        return !isReady();
    }

    public Envelope pollEvent() {
        return inboundEvents.poll();
    }

    public long lastHeartbeatSent() {
        return lastHeartbeatSent;
    }

    public long lastHeartbeatReceived() {
        return lastHeartbeatReceived;
    }

    public long roundTripLatency() {
        return roundTripLatency;
    }

    public Map<String, String> diagnostics() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("connectionState", connectionState.get().name());
        map.put("ready", String.valueOf(isReady()));
        map.put("worldId", worldId.toString());
        map.put("host", host);
        map.put("port", String.valueOf(port));
        map.put("pendingRequests", String.valueOf(pending.size()));
        map.put("inboundEvents", String.valueOf(inboundEvents.size()));
        map.put("lastHeartbeatSent", String.valueOf(lastHeartbeatSent));
        map.put("lastHeartbeatReceived", String.valueOf(lastHeartbeatReceived));
        map.put("roundTripLatencyMs", String.valueOf(roundTripLatency));
        map.put("requestTimeoutMillis", String.valueOf(requestTimeoutMillis));
        map.put("lastError", lastError == null ? "" : lastError);
        return map;
    }

    /**
     * Allocate a unique request id, register a future, enqueue an atomic write, return the future.
     * Never block-reads the response on this thread.
     */
    public CompletableFuture<Envelope> sendAsync(MessageType type, byte[] payload) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IOException("SidecarClient closed"));
        }
        long requestId = nextRequestId.getAndIncrement();
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            PendingRequest removed = pending.remove(requestId);
            if (removed != null) {
                removed.future().completeExceptionally(
                        new TimeoutException("IPC request " + requestId + " timed out after "
                                + requestTimeoutMillis + "ms"));
            }
        }, requestTimeoutMillis, TimeUnit.MILLISECONDS);

        pending.put(requestId, new PendingRequest(future, timeout, System.nanoTime(), type));

        Envelope request = new Envelope(
                ProtocolConstants.PROTOCOL_VERSION,
                type,
                0,
                nextMessageId.getAndIncrement(),
                requestId,
                0,
                worldId,
                payload == null ? new byte[0] : payload
        );

        writer.execute(() -> {
            try {
                ensureConnected();
                OutputStream out = socket.getOutputStream();
                BinaryCodec.writeEnvelope(out, request);
                if (type == MessageType.HEARTBEAT) {
                    lastHeartbeatSent = System.currentTimeMillis();
                }
            } catch (Exception e) {
                PendingRequest removed = pending.remove(requestId);
                if (removed != null) {
                    removed.cancelTimeout();
                    removed.future().completeExceptionally(e);
                }
                markDegraded(e);
            }
        });

        return future;
    }

    /** Compatibility overload — request id is allocated internally. */
    public CompletableFuture<Envelope> sendAsync(MessageType type, long ignoredRequestId, byte[] payload) {
        return sendAsync(type, payload);
    }

    public CompletableFuture<Void> handshake(WorldIdentityContract identity) {
        return handshake(identity, null, status -> {});
    }

    public CompletableFuture<Void> handshake(WorldIdentityContract identity, String worldRoot) {
        return handshake(identity, worldRoot, status -> {});
    }

    public CompletableFuture<Void> handshake(
            WorldIdentityContract identity,
            String worldRoot,
            Consumer<Map<String, String>> onReady
    ) {
        setState(ConnectionState.CONNECTING);
        try {
            HandshakePayload req = HandshakePayload.minecraftRequest(identity, worldRoot);
            return sendAsync(MessageType.HANDSHAKE_REQUEST, req.encode())
                    .thenAccept(response -> {
                        try {
                            if (response.isError()) {
                                throw new IOException(ErrorPayload.decode(response.payload()).message());
                            }
                            HandshakePayload ack = HandshakePayload.decode(response.payload());
                            if (ack.status() != HandshakePayload.HandshakeStatus.READY) {
                                throw new IOException("Sidecar rejected handshake: " + ack.message());
                            }
                            setState(ConnectionState.READY);
                            startHeartbeat();
                            onReady.accept(Map.of("status", "ready", "worldId", worldId.toString()));
                        } catch (IOException e) {
                            markFailed(e);
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            markFailed(e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Legacy fire-and-forget handshake without world identity contract fields. */
    public void handshake(Consumer<Map<String, String>> onReady) {
        WorldIdentityContract identity = WorldIdentityContract.of(worldId, 0L, 0L, 0);
        handshake(identity, null, onReady).exceptionally(ex -> {
            LivingModsMod.LOG.warn("Sidecar handshake failed: {}", ex.toString());
            return null;
        });
    }

    private void ensureConnected() throws IOException {
        Socket existing = socket;
        if (existing != null && existing.isConnected() && !existing.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), Math.min(1500, requestTimeoutMillis));
        s.setTcpNoDelay(true);
        s.setSoTimeout(Math.max(250, Math.min(requestTimeoutMillis, 1000)));
        this.socket = s;
        startReader(s);
    }

    private synchronized void startReader(Socket s) {
        Thread existing = readerThread;
        if (existing != null && existing.isAlive()) {
            return;
        }
        Thread t = new Thread(() -> readerLoop(s), "livingmods-ipc-reader");
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    private void readerLoop(Socket s) {
        try {
            InputStream in = s.getInputStream();
            while (!closed.get() && !s.isClosed()) {
                try {
                    Envelope envelope = BinaryCodec.readEnvelope(in);
                    dispatchInbound(envelope);
                } catch (SocketTimeoutException timeout) {
                    // Expected — allows stop/heartbeat checks without blocking forever.
                } catch (IOException e) {
                    if (!closed.get()) {
                        markDegraded(e);
                        failAllPending(e);
                    }
                    break;
                } catch (Exception e) {
                    LivingModsMod.LOG.warn("Malformed IPC envelope ignored: {}", e.toString());
                    lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
        } catch (Exception e) {
            if (!closed.get()) {
                markDegraded(e);
                failAllPending(e);
            }
        }
    }

    private void dispatchInbound(Envelope envelope) {
        if (envelope.type() == MessageType.EVENT) {
            inboundEvents.add(envelope);
            return;
        }
        if (envelope.type() == MessageType.HEARTBEAT) {
            long now = System.currentTimeMillis();
            lastHeartbeatReceived = now;
            PendingRequest hb = pending.get(envelope.requestId());
            if (hb != null && hb.sentNanos() > 0) {
                roundTripLatency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - hb.sentNanos());
            }
        }
        long requestId = envelope.requestId();
        if (requestId == 0L && envelope.type() != MessageType.HEARTBEAT) {
            LivingModsMod.LOG.debug("IPC message with requestId=0 type={}", envelope.type());
            return;
        }
        PendingRequest removed = pending.remove(requestId);
        if (removed == null) {
            LivingModsMod.LOG.debug("Duplicate/unknown IPC response requestId={} type={}",
                    requestId, envelope.type());
            return;
        }
        removed.cancelTimeout();
        if (envelope.isError()) {
            try {
                removed.future().completeExceptionally(
                        new IOException(ErrorPayload.decode(envelope.payload()).message()));
            } catch (IOException e) {
                removed.future().completeExceptionally(e);
            }
        } else {
            removed.future().complete(envelope);
        }
    }

    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (!isReady() || closed.get()) {
                return;
            }
            sendAsync(MessageType.HEARTBEAT, new byte[0]).exceptionally(ex -> {
                markDegraded(ex);
                return null;
            });
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void failAllPending(Throwable cause) {
        for (Long id : pending.keySet().toArray(new Long[0])) {
            PendingRequest removed = pending.remove(id);
            if (removed != null) {
                removed.cancelTimeout();
                removed.future().completeExceptionally(cause);
            }
        }
    }

    private void markDegraded(Throwable cause) {
        lastError = cause == null ? "" : String.valueOf(cause.getMessage() == null ? cause : cause.getMessage());
        ConnectionState current = connectionState.get();
        if (current == ConnectionState.STOPPING || current == ConnectionState.STOPPED
                || current == ConnectionState.FAILED) {
            return;
        }
        setState(ConnectionState.DEGRADED);
    }

    private void markFailed(Throwable cause) {
        lastError = cause == null ? "" : String.valueOf(cause.getMessage() == null ? cause : cause.getMessage());
        setState(ConnectionState.FAILED);
    }

    void setState(ConnectionState state) {
        connectionState.set(state);
    }

    void markReconnecting() {
        setState(ConnectionState.RECONNECTING);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        setState(ConnectionState.STOPPING);
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        failAllPending(new IOException("SidecarClient closed"));
        writer.shutdownNow();
        scheduler.shutdownNow();
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        Thread reader = readerThread;
        if (reader != null) {
            reader.interrupt();
        }
        setState(ConnectionState.STOPPED);
    }

    private record PendingRequest(
            CompletableFuture<Envelope> future,
            ScheduledFuture<?> timeout,
            long sentNanos,
            MessageType type
    ) {
        void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }
}
