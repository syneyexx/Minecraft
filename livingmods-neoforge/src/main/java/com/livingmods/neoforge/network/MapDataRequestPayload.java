package com.livingmods.neoforge.network;

import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server request for a map snapshot centered near the player. */
public record MapDataRequestPayload(int centerX, int centerZ, int radiusBlocks, int detailLevel)
        implements CustomPacketPayload {
    public static final Type<MapDataRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingModsMod.MOD_ID, "map_data_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MapDataRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.centerX);
                        buf.writeVarInt(p.centerZ);
                        buf.writeVarInt(p.radiusBlocks);
                        buf.writeVarInt(p.detailLevel);
                    },
                    buf -> new MapDataRequestPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
