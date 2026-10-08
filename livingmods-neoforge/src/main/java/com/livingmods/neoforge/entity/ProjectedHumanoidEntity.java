package com.livingmods.neoforge.entity;

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
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Shared LivingMods humanoid projection for soldiers, guards, bandits, and caravan leads.
 * Stable NBT identity survives LOD despawn/rebind; despawn is never death.
 */
public class ProjectedHumanoidEntity extends PathfinderMob {
    public enum Kind { SOLDIER, GUARD, BANDIT, CARAVAN, MIGRANT, REFUGEE }

    private static final String TAG_KIND = "LivingModsKind";
    private static final String TAG_CANONICAL = "LivingModsCanonicalId";
    private static final String TAG_FACTION = "LivingModsFactionId";
    private static final String TAG_REVISION = "LivingModsProjectionRevision";
    private static final String TAG_ROLE = "LivingModsRole";
    private static final String TAG_CULTURE = "LivingModsCulture";
    private static final String TAG_TARGET_X = "LivingModsTargetX";
    private static final String TAG_TARGET_Z = "LivingModsTargetZ";
    private static final String TAG_HOSTILE = "LivingModsHostile";
    private static final String TAG_CARGO = "LivingModsCargo";

    private static final EntityDataAccessor<String> DATA_KIND =
            SynchedEntityData.defineId(ProjectedHumanoidEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_NAME =
            SynchedEntityData.defineId(ProjectedHumanoidEntity.class, EntityDataSerializers.STRING);

    private UUID canonicalId;
    private UUID factionId;
    private long projectionRevision;
    private String roleKey = "";
    private String cultureKey = "avalon";
    private String cargoMeta = "";
    private double targetX;
    private double targetZ;
    private boolean hasTarget;
    private boolean hostileToPlayer;

    public ProjectedHumanoidEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_KIND, Kind.SOLDIER.name());
        builder.define(DATA_NAME, "Soldier");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, false));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.85));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, ProjectedHumanoidEntity.class, 10, true, false,
                other -> other instanceof ProjectedHumanoidEntity foe && isHostileToward(foe)));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                living -> living instanceof Player p && isHostileTowardPlayer(p)));
    }

    /**
     * Evaluate hostility against a specific player using jurisdiction-aware cache.
     * Bandits are always hostile; guards/soldiers use FactionDispositionCache.
     * No IPC — cache only.
     */
    public boolean isHostileTowardPlayer(Player player) {
        if (player == null) return false;
        Kind self = kind();
        if (self == Kind.BANDIT) return true;
        if (self == Kind.CARAVAN || self == Kind.MIGRANT || self == Kind.REFUGEE) return false;
        // Re-evaluate from cache so already-spawned entities react to legal/war changes.
        if (factionId != null) {
            if (self == Kind.GUARD) {
                UUID settlement = getPersistentData().hasUUID("livingmods_settlement")
                        ? getPersistentData().getUUID("livingmods_settlement") : factionId;
                return com.livingmods.neoforge.gameplay.FactionDispositionCache.get()
                        .guardsHostileToPlayer(player.getUUID(), settlement, factionId);
            }
            return com.livingmods.neoforge.gameplay.FactionDispositionCache.get()
                    .hostileToPlayer(player.getUUID(), factionId);
        }
        return hostileToPlayer;
    }

    public void bind(
            Kind kind,
            UUID canonicalId,
            UUID factionId,
            long revision,
            String displayName,
            String roleKey,
            String cultureKey,
            boolean hostileToPlayer
    ) {
        this.canonicalId = canonicalId;
        this.factionId = factionId;
        this.projectionRevision = revision;
        this.roleKey = roleKey == null ? "" : roleKey;
        this.cultureKey = cultureKey == null || cultureKey.isBlank() ? "avalon" : cultureKey;
        this.hostileToPlayer = hostileToPlayer;
        entityData.set(DATA_KIND, kind.name());
        entityData.set(DATA_NAME, displayName == null ? kind.name() : displayName);
        setCustomName(net.minecraft.network.chat.Component.literal(entityData.get(DATA_NAME)));
        setCustomNameVisible(true);
        // Persist identity on entity for death/interact bridges.
        getPersistentData().putString("livingmods_kind", kind.name());
        if (canonicalId != null) {
            getPersistentData().putUUID("livingmods_canonical", canonicalId);
            if (kind == Kind.SOLDIER) getPersistentData().putUUID("livingmods_army", canonicalId);
            if (kind == Kind.CARAVAN) getPersistentData().putUUID("livingmods_shipment", canonicalId);
            if (kind == Kind.BANDIT) getPersistentData().putUUID("livingmods_camp", canonicalId);
            if (kind == Kind.GUARD) getPersistentData().putUUID("livingmods_settlement",
                    factionId == null ? canonicalId : factionId);
        }
        if (factionId != null) {
            getPersistentData().putUUID("livingmods_faction", factionId);
        }
        getPersistentData().putLong("livingmods_revision", revision);
        getPersistentData().putString("livingmods_culture", this.cultureKey);
        getPersistentData().putString("livingmods_role", this.roleKey);
    }

    public void setNavigationTarget(double x, double z) {
        this.targetX = x;
        this.targetZ = z;
        this.hasTarget = true;
        getNavigation().moveTo(x, getY(), z, 1.1);
    }

    public void setCargoMeta(String cargo) {
        this.cargoMeta = cargo == null ? "" : cargo;
        getPersistentData().putString("livingmods_cargo", this.cargoMeta);
    }

    /**
     * Geopolitical hostility is NOT "different faction ID = enemy".
     * Bandit rules are local; soldier-vs-soldier uses cached war/diplomacy disposition.
     */
    public boolean isHostileToward(ProjectedHumanoidEntity other) {
        Kind self = kind();
        Kind o = other.kind();
        if (self == Kind.BANDIT) {
            return o == Kind.SOLDIER || o == Kind.GUARD || o == Kind.CARAVAN;
        }
        if (o == Kind.BANDIT && (self == Kind.SOLDIER || self == Kind.GUARD)) {
            return true;
        }
        if (self == Kind.SOLDIER && o == Kind.SOLDIER
                && factionId != null && other.factionId != null
                && !factionId.equals(other.factionId)) {
            return com.livingmods.neoforge.gameplay.FactionDispositionCache.get()
                    .militaryHostile(factionId, other.factionId);
        }
        if (self == Kind.GUARD && o == Kind.SOLDIER
                && factionId != null && other.factionId != null
                && !factionId.equals(other.factionId)) {
            return com.livingmods.neoforge.gameplay.FactionDispositionCache.get()
                    .militaryHostile(factionId, other.factionId);
        }
        return false;
    }

    public void setHostileToPlayer(boolean hostile) {
        this.hostileToPlayer = hostile;
    }

    public boolean hostileToPlayer() {
        return hostileToPlayer;
    }

    public Kind kind() {
        try {
            return Kind.valueOf(entityData.get(DATA_KIND));
        } catch (Exception e) {
            return Kind.SOLDIER;
        }
    }

    public UUID canonicalIdOrNull() { return canonicalId; }
    public UUID factionIdOrNull() { return factionId; }
    public long projectionRevision() { return projectionRevision; }
    public String cultureKey() { return cultureKey; }
    public String roleKey() { return roleKey; }
    public String cargoMeta() { return cargoMeta; }
    public boolean hasTarget() { return hasTarget; }
    public double targetX() { return targetX; }
    public double targetZ() { return targetZ; }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && hasTarget && tickCount % 40 == 0) {
            getNavigation().moveTo(targetX, getY(), targetZ, 1.1);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(TAG_KIND, kind().name());
        if (canonicalId != null) tag.putUUID(TAG_CANONICAL, canonicalId);
        if (factionId != null) tag.putUUID(TAG_FACTION, factionId);
        tag.putLong(TAG_REVISION, projectionRevision);
        tag.putString(TAG_ROLE, roleKey);
        tag.putString(TAG_CULTURE, cultureKey);
        tag.putString(TAG_CARGO, cargoMeta);
        tag.putBoolean(TAG_HOSTILE, hostileToPlayer);
        if (hasTarget) {
            tag.putDouble(TAG_TARGET_X, targetX);
            tag.putDouble(TAG_TARGET_Z, targetZ);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        Kind kind = Kind.SOLDIER;
        try {
            kind = Kind.valueOf(tag.getString(TAG_KIND));
        } catch (Exception ignored) {
        }
        UUID canonical = tag.hasUUID(TAG_CANONICAL) ? tag.getUUID(TAG_CANONICAL) : null;
        UUID faction = tag.hasUUID(TAG_FACTION) ? tag.getUUID(TAG_FACTION) : null;
        bind(kind, canonical, faction, tag.getLong(TAG_REVISION),
                getDisplayName().getString(), tag.getString(TAG_ROLE), tag.getString(TAG_CULTURE),
                tag.getBoolean(TAG_HOSTILE));
        setCargoMeta(tag.getString(TAG_CARGO));
        if (tag.contains(TAG_TARGET_X) && tag.contains(TAG_TARGET_Z)) {
            setNavigationTarget(tag.getDouble(TAG_TARGET_X), tag.getDouble(TAG_TARGET_Z));
        }
    }
}
