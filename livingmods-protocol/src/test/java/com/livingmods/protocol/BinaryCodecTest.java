package com.livingmods.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BinaryCodecTest {
    @Test
    void roundTripEnvelope() throws Exception {
        UUID world = UUID.randomUUID();
        byte[] payload = HandshakePayload.minecraftRequest(world).encode();
        Envelope env = new Envelope(
                ProtocolConstants.PROTOCOL_VERSION,
                MessageType.HANDSHAKE_REQUEST,
                0,
                7L,
                9L,
                12345L,
                world,
                payload
        );
        byte[] bytes = BinaryCodec.encodeEnvelope(env);
        Envelope decoded = BinaryCodec.decodeEnvelope(bytes);
        assertEquals(env.type(), decoded.type());
        assertEquals(env.messageId(), decoded.messageId());
        assertEquals(env.requestId(), decoded.requestId());
        assertEquals(env.simulationTicks(), decoded.simulationTicks());
        assertEquals(env.worldSessionId(), decoded.worldSessionId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void rejectsBadMagic() {
        assertThrows(Exception.class, () -> BinaryCodec.decodeEnvelope(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
    }

    @Test
    void handshakeCompatibility() throws Exception {
        UUID world = UUID.randomUUID();
        HandshakePayload req = HandshakePayload.minecraftRequest(world);
        assertTrue(req.isCompatible());
        HandshakePayload ready = HandshakePayload.sidecarReady(world);
        assertEquals(HandshakePayload.HandshakeStatus.READY, ready.status());
        HandshakePayload rejected = HandshakePayload.rejected(world, "nope");
        assertEquals(HandshakePayload.HandshakeStatus.REJECTED, rejected.status());
        assertArrayEquals(req.encode(), HandshakePayload.decode(req.encode()).encode());
    }

    @Test
    void versionMismatchFailsClearly() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
        dos.writeInt(ProtocolConstants.MAGIC);
        dos.writeInt(ProtocolConstants.PROTOCOL_VERSION + 99);
        dos.writeInt(MessageType.HEARTBEAT.code());
        dos.writeInt(0);
        dos.writeLong(1);
        dos.writeLong(1);
        dos.writeLong(0);
        BinaryCodec.writeUuid(dos, UUID.randomUUID());
        dos.writeInt(0);
        byte[] bytes = bos.toByteArray();
        Exception ex = assertThrows(Exception.class, () -> BinaryCodec.decodeEnvelope(bytes));
        assertTrue(ex.getMessage().contains("Incompatible protocol"));
    }
}
