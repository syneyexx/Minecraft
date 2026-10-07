package com.livingmods.neoforge.worldgen;

import com.livingmods.common.id.StructureId;
import com.livingmods.common.version.LivingModsVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent per-chunk status: which materialization revision / plan hash was applied,
 * plus StructureIds placed in this chunk. Once applied, content is never rewritten.
 */
public final class ChunkMaterializationState {
    private static final String KEY_APPLIED = "applied";
    private static final String KEY_REVISION = "revision";
    private static final String KEY_PLAN_HASH = "planHash";
    private static final String KEY_STRUCTURES = "structures";
    private static final String KEY_EMPTY = "empty";

    private final LevelChunk chunk;
    private final CompoundTag data;

    private ChunkMaterializationState(LevelChunk chunk, CompoundTag data) {
        this.chunk = chunk;
        this.data = data;
    }

    public static ChunkMaterializationState of(LevelChunk chunk) {
        CompoundTag tag = chunk.getData(MaterializationAttachments.CHUNK_STATUS.get());
        if (tag == null) {
            tag = new CompoundTag();
        } else {
            tag = tag.copy();
        }
        return new ChunkMaterializationState(chunk, tag);
    }

    public boolean isAppliedFor(int revision, long planHash) {
        if (!data.getBoolean(KEY_APPLIED)) {
            return false;
        }
        return data.getInt(KEY_REVISION) == revision && data.getLong(KEY_PLAN_HASH) == planHash;
    }

    public boolean isAppliedAnyRevision() {
        return data.getBoolean(KEY_APPLIED);
    }

    public Set<UUID> appliedStructures() {
        Set<UUID> out = new LinkedHashSet<>();
        ListTag list = data.getList(KEY_STRUCTURES, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                out.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public void recordStructure(StructureId id) {
        ListTag list = data.getList(KEY_STRUCTURES, Tag.TAG_STRING);
        String text = id.value().toString();
        for (int i = 0; i < list.size(); i++) {
            if (text.equals(list.getString(i))) {
                return;
            }
        }
        list.add(StringTag.valueOf(text));
        data.put(KEY_STRUCTURES, list);
    }

    public void markApplied(long planHash, boolean emptySlice) {
        data.putBoolean(KEY_APPLIED, true);
        data.putInt(KEY_REVISION, LivingModsVersions.PHYSICAL_CONTENT_REVISION);
        data.putLong(KEY_PLAN_HASH, planHash);
        data.putBoolean(KEY_EMPTY, emptySlice);
        commit();
    }

    public void commit() {
        chunk.setData(MaterializationAttachments.CHUNK_STATUS.get(), data.copy());
        chunk.setUnsaved(true);
    }
}
