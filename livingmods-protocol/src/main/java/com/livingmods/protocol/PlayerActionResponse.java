package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed response for player actions and interaction opens. */
public record PlayerActionResponse(
        boolean success,
        String status,
        String message,
        PlayerActionType action,
        UUID sessionId,
        double reputation,
        String standing,
        List<String> availableActions,
        List<String> dialogueLines,
        Map<String, String> data
) {
    public static final int MAGIC = 0x50415234; // PAR4
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ACTIONS = 32;
    public static final int MAX_LINES = 64;
    public static final int MAX_DATA = 128;
    public static final int MAX_STRING = 512;

    public PlayerActionResponse {
        if (status == null) status = success ? "ok" : "error";
        if (message == null) message = "";
        if (message.length() > MAX_STRING) message = message.substring(0, MAX_STRING);
        if (standing == null) standing = "NEUTRAL";
        if (sessionId == null) sessionId = new UUID(0, 0);
        if (availableActions == null) availableActions = List.of();
        if (dialogueLines == null) dialogueLines = List.of();
        if (data == null) data = Map.of();
        if (availableActions.size() > MAX_ACTIONS) {
            availableActions = List.copyOf(availableActions.subList(0, MAX_ACTIONS));
        }
        if (dialogueLines.size() > MAX_LINES) {
            dialogueLines = List.copyOf(dialogueLines.subList(0, MAX_LINES));
        }
    }

    public static PlayerActionResponse ok(PlayerActionType action, String message, Map<String, String> data) {
        return new PlayerActionResponse(true, "ok", message, action, new UUID(0, 0), 0, "NEUTRAL",
                List.of(), List.of(), data == null ? Map.of() : data);
    }

    public static PlayerActionResponse fail(PlayerActionType action, String message) {
        return new PlayerActionResponse(false, "error", message, action, new UUID(0, 0), 0, "NEUTRAL",
                List.of(), List.of(), Map.of());
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(MAGIC);
        dos.writeInt(FORMAT_VERSION);
        dos.writeBoolean(success);
        BinaryCodec.writeString(dos, status);
        BinaryCodec.writeString(dos, message);
        dos.writeInt(action == null ? -1 : action.ordinal());
        BinaryCodec.writeUuid(dos, sessionId);
        dos.writeDouble(reputation);
        BinaryCodec.writeString(dos, standing);
        dos.writeInt(availableActions.size());
        for (String a : availableActions) {
            BinaryCodec.writeString(dos, a);
        }
        dos.writeInt(dialogueLines.size());
        for (String line : dialogueLines) {
            BinaryCodec.writeString(dos, line);
        }
        dos.writeInt(data.size());
        for (Map.Entry<String, String> e : data.entrySet()) {
            BinaryCodec.writeString(dos, e.getKey());
            BinaryCodec.writeString(dos, e.getValue());
        }
        return bos.toByteArray();
    }

    public static PlayerActionResponse decode(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        int magic = dis.readInt();
        if (magic != MAGIC) {
            throw new IOException("not a typed PlayerActionResponse (bad magic)");
        }
        int version = dis.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported PlayerActionResponse version: " + version);
        }
        boolean success = dis.readBoolean();
        String status = BinaryCodec.readString(dis, MAX_STRING);
        String message = BinaryCodec.readString(dis, MAX_STRING);
        int ordinal = dis.readInt();
        PlayerActionType action = null;
        if (ordinal >= 0 && ordinal < PlayerActionType.values().length) {
            action = PlayerActionType.values()[ordinal];
        }
        UUID sessionId = BinaryCodec.readUuid(dis);
        double reputation = dis.readDouble();
        String standing = BinaryCodec.readString(dis, 64);
        int actionCount = PayloadIo.readBoundedCount(dis, MAX_ACTIONS, "availableActions");
        List<String> actions = new ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            actions.add(BinaryCodec.readString(dis, MAX_STRING));
        }
        int lineCount = PayloadIo.readBoundedCount(dis, MAX_LINES, "dialogueLines");
        List<String> lines = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            lines.add(BinaryCodec.readString(dis, MAX_STRING));
        }
        int dataCount = PayloadIo.readBoundedCount(dis, MAX_DATA, "response.data");
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < dataCount; i++) {
            data.put(BinaryCodec.readString(dis, 64), BinaryCodec.readString(dis, MAX_STRING));
        }
        return new PlayerActionResponse(
                success, status, message, action, sessionId, reputation, standing, actions, lines, data);
    }
}
