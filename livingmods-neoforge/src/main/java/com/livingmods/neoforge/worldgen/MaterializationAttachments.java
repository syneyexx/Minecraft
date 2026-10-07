package com.livingmods.neoforge.worldgen;

import com.livingmods.neoforge.LivingModsMod;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Per-chunk LivingMods materialization provenance (StructureId + revision). */
public final class MaterializationAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LivingModsMod.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CompoundTag>> CHUNK_STATUS =
            ATTACHMENT_TYPES.register(
                    "chunk_materialization",
                    () -> AttachmentType.builder((Supplier<CompoundTag>) CompoundTag::new)
                            .serialize(CompoundTag.CODEC)
                            .build()
            );

    private MaterializationAttachments() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }
}
