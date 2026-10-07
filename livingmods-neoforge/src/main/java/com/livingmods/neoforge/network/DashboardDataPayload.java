package com.livingmods.neoforge.network;

import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** Server → client dashboard metrics snapshot. */
public record DashboardDataPayload(Map<String, String> metrics) implements CustomPacketPayload {
    public static final Type<DashboardDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingModsMod.MOD_ID, "dashboard_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DashboardDataPayload> STREAM_CODEC =
            StreamCodec.of(DashboardDataPayload::encode, DashboardDataPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, DashboardDataPayload payload) {
        buf.writeVarInt(payload.metrics.size());
        for (Map.Entry<String, String> e : payload.metrics.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue() == null ? "" : e.getValue());
        }
    }

    private static DashboardDataPayload decode(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(buf.readUtf(), buf.readUtf());
        }
        return new DashboardDataPayload(map);
    }
}
