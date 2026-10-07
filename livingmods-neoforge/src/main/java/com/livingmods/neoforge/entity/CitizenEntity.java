package com.livingmods.neoforge.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class CitizenEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_NAME =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_CITIZEN_ID =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);

    public CitizenEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_NAME, "Citizen");
        builder.define(DATA_CITIZEN_ID, UUID.randomUUID().toString());
    }

    public void bindCitizen(UUID citizenId, String displayName) {
        entityData.set(DATA_CITIZEN_ID, citizenId.toString());
        entityData.set(DATA_NAME, displayName);
        setCustomName(net.minecraft.network.chat.Component.literal(displayName));
    }
}
