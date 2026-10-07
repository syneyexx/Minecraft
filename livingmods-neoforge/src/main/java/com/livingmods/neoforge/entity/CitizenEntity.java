package com.livingmods.neoforge.entity;

import com.livingmods.common.model.ScheduleState;
import com.livingmods.neoforge.entity.ai.CitizenScheduleGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Physical projection of a canonical citizen. Identity + revision persist through
 * entity save/reload — never assign a new random canonical id on load.
 */
public class CitizenEntity extends PathfinderMob {
    private static final String TAG_CITIZEN_ID = "LivingModsCitizenId";
    private static final String TAG_DISPLAY_NAME = "LivingModsDisplayName";
    private static final String TAG_REVISION = "LivingModsProjectionRevision";
    private static final String TAG_SCHEDULE = "LivingModsSchedule";
    private static final String TAG_TARGET_X = "LivingModsTargetX";
    private static final String TAG_TARGET_Y = "LivingModsTargetY";
    private static final String TAG_TARGET_Z = "LivingModsTargetZ";
    private static final String TAG_CULTURE = "LivingModsCulture";
    private static final String TAG_PROFESSION = "LivingModsProfession";
    private static final String TAG_FEMALE = "LivingModsFemale";
    private static final String TAG_AGE = "LivingModsAge";

    /** Empty sentinel — never a random UUID. Bound later via {@link #bindCitizen}. */
    private static final String UNBOUND = "";

    private static final EntityDataAccessor<String> DATA_NAME =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_CITIZEN_ID =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SCHEDULE =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN_KEY =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);

    private long projectionRevision;
    private double targetX;
    private double targetY;
    private double targetZ;
    private boolean hasTarget;
    private String cultureKey = "avalon";
    private String professionKey = "FARMER";
    private boolean female;
    private int ageYears = 25;

    public CitizenEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_NAME, "Citizen");
        builder.define(DATA_CITIZEN_ID, UNBOUND);
        builder.define(DATA_SCHEDULE, ScheduleState.HOME.name());
        builder.define(DATA_SKIN_KEY, "avalon/common/farmer/m/adult");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new CitizenScheduleGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    public void bindCitizen(UUID citizenId, String displayName) {
        bindCitizen(citizenId, displayName, 0L, ScheduleState.HOME, "avalon", "FARMER", false, 25);
    }

    public void bindCitizen(
            UUID citizenId,
            String displayName,
            long revision,
            ScheduleState schedule,
            String cultureKey,
            String professionKey,
            boolean female,
            int ageYears
    ) {
        entityData.set(DATA_CITIZEN_ID, citizenId.toString());
        entityData.set(DATA_NAME, displayName == null ? "Citizen" : displayName);
        setCustomName(net.minecraft.network.chat.Component.literal(entityData.get(DATA_NAME)));
        setCustomNameVisible(true);
        this.projectionRevision = revision;
        setSchedule(schedule == null ? ScheduleState.HOME : schedule);
        this.cultureKey = cultureKey == null ? "avalon" : cultureKey;
        this.professionKey = professionKey == null ? "FARMER" : professionKey;
        this.female = female;
        this.ageYears = ageYears;
        refreshSkinKey();
    }

    public void refreshSkinKey() {
        String key = com.livingmods.neoforge.client.skin.CitizenSkinLibrary.skinKey(
                citizenIdOrNull(), cultureKey, professionKey, female, ageYears);
        entityData.set(DATA_SKIN_KEY, key);
    }

    public UUID citizenIdOrNull() {
        String raw = entityData.get(DATA_CITIZEN_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isBound() {
        return citizenIdOrNull() != null;
    }

    public String displayName() {
        return entityData.get(DATA_NAME);
    }

    public long projectionRevision() {
        return projectionRevision;
    }

    public void setProjectionRevision(long projectionRevision) {
        this.projectionRevision = projectionRevision;
    }

    public ScheduleState schedule() {
        try {
            return ScheduleState.valueOf(entityData.get(DATA_SCHEDULE));
        } catch (Exception e) {
            return ScheduleState.HOME;
        }
    }

    public void setSchedule(ScheduleState schedule) {
        entityData.set(DATA_SCHEDULE, schedule.name());
    }

    public String skinKey() {
        return entityData.get(DATA_SKIN_KEY);
    }

    public void setNavigationTarget(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.hasTarget = true;
    }

    public boolean hasNavigationTarget() {
        return hasTarget;
    }

    public double targetX() { return targetX; }
    public double targetY() { return targetY; }
    public double targetZ() { return targetZ; }

    public String cultureKey() { return cultureKey; }
    public String professionKey() { return professionKey; }
    public boolean femaleCitizen() { return female; }
    public int ageYears() { return ageYears; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        String id = entityData.get(DATA_CITIZEN_ID);
        if (id != null && !id.isBlank()) {
            tag.putString(TAG_CITIZEN_ID, id);
        }
        tag.putString(TAG_DISPLAY_NAME, entityData.get(DATA_NAME));
        tag.putLong(TAG_REVISION, projectionRevision);
        tag.putString(TAG_SCHEDULE, entityData.get(DATA_SCHEDULE));
        tag.putBoolean(TAG_FEMALE, female);
        tag.putInt(TAG_AGE, ageYears);
        tag.putString(TAG_CULTURE, cultureKey);
        tag.putString(TAG_PROFESSION, professionKey);
        if (hasTarget) {
            tag.putDouble(TAG_TARGET_X, targetX);
            tag.putDouble(TAG_TARGET_Y, targetY);
            tag.putDouble(TAG_TARGET_Z, targetZ);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_CITIZEN_ID)) {
            entityData.set(DATA_CITIZEN_ID, tag.getString(TAG_CITIZEN_ID));
        } else {
            entityData.set(DATA_CITIZEN_ID, UNBOUND);
        }
        if (tag.contains(TAG_DISPLAY_NAME)) {
            String name = tag.getString(TAG_DISPLAY_NAME);
            entityData.set(DATA_NAME, name);
            setCustomName(net.minecraft.network.chat.Component.literal(name));
        }
        projectionRevision = tag.getLong(TAG_REVISION);
        if (tag.contains(TAG_SCHEDULE)) {
            entityData.set(DATA_SCHEDULE, tag.getString(TAG_SCHEDULE));
        }
        female = tag.getBoolean(TAG_FEMALE);
        ageYears = tag.contains(TAG_AGE) ? tag.getInt(TAG_AGE) : 25;
        cultureKey = tag.contains(TAG_CULTURE) ? tag.getString(TAG_CULTURE) : "avalon";
        professionKey = tag.contains(TAG_PROFESSION) ? tag.getString(TAG_PROFESSION) : "FARMER";
        if (tag.contains(TAG_TARGET_X)) {
            targetX = tag.getDouble(TAG_TARGET_X);
            targetY = tag.getDouble(TAG_TARGET_Y);
            targetZ = tag.getDouble(TAG_TARGET_Z);
            hasTarget = true;
        }
        refreshSkinKey();
    }
}
