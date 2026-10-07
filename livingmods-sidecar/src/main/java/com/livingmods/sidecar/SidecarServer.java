package com.livingmods.sidecar;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Binds loopback only and accepts a single Minecraft session. */
public final class SidecarServer implements AutoCloseable {
    private static final Logger LOG = SidecarLogging.logger(SidecarServer.class);

    private final SidecarSimulationHost host;
    private final DiagnosticsExporter diagnostics;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private ExecutorService acceptPool;
    private volatile SessionHandler activeSession;

    public SidecarServer(SidecarSimulationHost host, DiagnosticsExporter diagnostics, int port) {
        this.host = host;
        this.diagnostics = diagnostics;
        this.port = port;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
        acceptPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "livingmods-sidecar-accept");
            t.setDaemon(true);
            return t;
        });
        acceptPool.submit(this::acceptLoop);
        LOG.info("Sidecar IPC listening on 127.0.0.1:" + port);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    LOG.warning("Rejected non-loopback connection: " + socket.getRemoteSocketAddress());
                    socket.close();
                    continue;
                }
                if (activeSession != null) {
                    LOG.warning("Rejecting additional session — only one Minecraft session allowed");
                    socket.close();
                    continue;
                }
                SessionHandler handler = new SessionHandler(host, diagnostics, socket);
                activeSession = handler;
                Thread t = new Thread(() -> {
                    try {
                        handler.run();
                    } finally {
                        activeSession = null;
                    }
                }, "livingmods-sidecar-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    LOG.warning("Accept failed: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (acceptPool != null) acceptPool.shutdownNow();
    }
}
