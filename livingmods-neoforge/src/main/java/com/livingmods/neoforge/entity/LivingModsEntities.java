package com.livingmods.neoforge.entity;

import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LivingModsEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, LivingModsMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN = ENTITIES.register(
            "citizen",
            () -> EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .build(LivingModsMod.MOD_ID + ":citizen")
    );

    public static final DeferredHolder<EntityType<?>, EntityType<ProjectedHumanoidEntity>> PROJECTED_HUMANOID =
            ENTITIES.register(
                    "projected_humanoid",
                    () -> EntityType.Builder.<ProjectedHumanoidEntity>of(ProjectedHumanoidEntity::new, MobCategory.CREATURE)
                            .sized(0.6f, 1.8f)
                            .build(LivingModsMod.MOD_ID + ":projected_humanoid")
            );

    private LivingModsEntities() {}

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
