package com.livingmods.neoforge.worldgen;

import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.worldgen.plan.PlannedBuilding;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * World-local registry of materialized structures and their role / settlement metadata.
 */
public final class LivingModsStructureIndex extends SavedData {
    public static final String DATA_NAME = "livingmods_structure_index";

    public record Entry(
            StructureId structureId,
            BuildingRole role,
            SettlementId settlementId,
            DistrictId districtId,
            int capacity,
            int workSlots,
            int residentialSlots,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int foundationY,
            String cultureKey
    ) {}

    private final Map<UUID, Entry> byId = new LinkedHashMap<>();

    public LivingModsStructureIndex() {}

    public static LivingModsStructureIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LivingModsStructureIndex::new, LivingModsStructureIndex::load),
                DATA_NAME
        );
    }

    public static LivingModsStructureIndex load(CompoundTag tag, HolderLookup.Provider provider) {
        LivingModsStructureIndex index = new LivingModsStructureIndex();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            try {
                StructureId sid = StructureId.of(UUID.fromString(e.getString("id")));
                Entry entry = new Entry(
                        sid,
                        BuildingRole.valueOf(e.getString("role")),
                        SettlementId.of(UUID.fromString(e.getString("settlement"))),
                        DistrictId.of(UUID.fromString(e.getString("district"))),
                        e.getInt("capacity"),
                        e.getInt("work"),
                        e.getInt("residential"),
                        e.getInt("minX"),
                        e.getInt("minZ"),
                        e.getInt("maxX"),
                        e.getInt("maxZ"),
                        e.getInt("y"),
                        e.getString("culture")
                );
                index.byId.put(sid.value(), entry);
            } catch (Exception ignored) {
            }
        }
        return index;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Entry e : byId.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", e.structureId().value().toString());
            c.putString("role", e.role().name());
            c.putString("settlement", e.settlementId().value().toString());
            c.putString("district", e.districtId().value().toString());
            c.putInt("capacity", e.capacity());
            c.putInt("work", e.workSlots());
            c.putInt("residential", e.residentialSlots());
            c.putInt("minX", e.minX());
            c.putInt("minZ", e.minZ());
            c.putInt("maxX", e.maxX());
            c.putInt("maxZ", e.maxZ());
            c.putInt("y", e.foundationY());
            c.putString("culture", e.cultureKey());
            list.add(c);
        }
        tag.put("entries", list);
        return tag;
    }

    public void putBuilding(PlannedBuilding building) {
        Entry entry = new Entry(
                building.id(),
                building.role(),
                building.settlementId(),
                building.districtId(),
                building.capacity(),
                building.workSlots(),
                building.residentialSlots(),
                building.footprint().minX(),
                building.footprint().minZ(),
                building.footprint().maxX(),
                building.footprint().maxZ(),
                building.foundationY(),
                building.cultureKey()
        );
        byId.put(building.id().value(), entry);
        setDirty();
    }

    public Optional<Entry> get(StructureId id) {
        return Optional.ofNullable(byId.get(id.value()));
    }

    public Collection<Entry> all() {
        return byId.values();
    }
}
