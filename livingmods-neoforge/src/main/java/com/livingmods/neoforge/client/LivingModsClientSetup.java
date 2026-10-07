package com.livingmods.neoforge.client;

import com.livingmods.neoforge.entity.LivingModsEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@OnlyIn(Dist.CLIENT)
public final class LivingModsClientSetup {
    private LivingModsClientSetup() {}

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LivingModsEntities.CITIZEN.get(), CitizenRenderer::new);
    }
}
