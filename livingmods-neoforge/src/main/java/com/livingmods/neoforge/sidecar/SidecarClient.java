package com.livingmods.neoforge.sidecar;

import com.livingmods.protocol.BinaryCodec;
import com.livingmods.protocol.Envelope;
import com.livingmods.protocol.ErrorPayload;
import com.livingmods.protocol.HandshakePayload;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Async IPC client — never blocks the server thread.
 */
public final class SidecarClient implements AutoCloseable {
    private final UUID worldId;
    private final String host;
    private final int port;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "livingmods-ipc");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean degraded = new AtomicBoolean(true);
    private final AtomicLong messageId = new AtomicLong(1);
    private final ConcurrentLinkedQueue<Envelope> inboundEvents = new ConcurrentLinkedQueue<>();
    private volatile Socket socket;

    public SidecarClient(UUID worldId, String host, int port) {
        this.worldId = worldId;
        this.host = host;
        this.port = port;
    }

    public boolean degraded() {
        return degraded.get();
    }

    public Envelope pollEvent() {
        return inboundEvents.poll();
    }

    public CompletableFuture<Envelope> sendAsync(MessageType type, long requestId, byte[] payload) {
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        ioPool.submit(() -> {
            try {
                ensureConnected();
                Envelope request = new Envelope(
                        ProtocolConstants.PROTOCOL_VERSION,
                        type,
                        0,
                        messageId.getAndIncrement(),
                        requestId,
                        0,
                        worldId,
                        payload
                );
                BinaryCodec.writeEnvelope(socket.getOutputStream(), request);
                Envelope response = BinaryCodec.readEnvelope(socket.getInputStream());
                if (response.type() == MessageType.EVENT) {
                    inboundEvents.add(response);
                    future.complete(response);
                } else if (response.isError()) {
                    future.completeExceptionally(new IOException(ErrorPayload.decode(response.payload()).message()));
                } else {
                    future.complete(response);
                }
            } catch (Exception e) {
                degraded.set(true);
                connected.set(false);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void handshake(Consumer<Map<String, String>> onReady) {
        ioPool.submit(() -> {
            try {
                ensureConnected();
                HandshakePayload req = HandshakePayload.minecraftRequest(worldId);
                Envelope env = new Envelope(
                        ProtocolConstants.PROTOCOL_VERSION,
                        MessageType.HANDSHAKE_REQUEST,
                        0,
                        messageId.getAndIncrement(),
                        0,
                        0,
                        worldId,
                        req.encode()
                );
                BinaryCodec.writeEnvelope(socket.getOutputStream(), env);
                Envelope response = BinaryCodec.readEnvelope(socket.getInputStream());
                HandshakePayload ack = HandshakePayload.decode(response.payload());
                if (ack.status() != HandshakePayload.HandshakeStatus.READY) {
                    throw new IOException("Sidecar rejected handshake: " + ack.message());
                }
                degraded.set(false);
                onReady.accept(Map.of("status", "ready"));
            } catch (Exception e) {
                degraded.set(true);
            }
        });
    }

    private void ensureConnected() throws IOException {
        if (connected.get() && socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 1500);
        s.setTcpNoDelay(true);
        this.socket = s;
        connected.set(true);
    }

    @Override
    public void close() {
        ioPool.shutdownNow();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
