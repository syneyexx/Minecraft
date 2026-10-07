package com.livingmods.neoforge;

import com.livingmods.neoforge.command.LivingModsCommands;
import com.livingmods.neoforge.entity.CitizenEntity;
import com.livingmods.neoforge.entity.LivingModsEntities;
import com.livingmods.neoforge.integrations.ModIntegrations;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.ChunkMaterializationHandler;
import com.livingmods.neoforge.worldgen.MaterializationAttachments;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(LivingModsMod.MOD_ID)
public final class LivingModsMod {
    public static final String MOD_ID = "livingmods";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public LivingModsMod(IEventBus modBus) {
        LivingModsEntities.register(modBus);
        MaterializationAttachments.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(LivingModsEntities.CITIZEN.get(), CitizenEntity.createAttributes().build()));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(com.livingmods.neoforge.client.LivingModsClientSetup::registerRenderers);
        }
        NeoForge.EVENT_BUS.addListener(LivingModsCommands::register);
        NeoForge.EVENT_BUS.register(ChunkMaterializationHandler.class);
        NeoForge.EVENT_BUS.register(WorldSessionLifecycle.class);
        // LivingModsClientEvents is Dist.CLIENT via @EventBusSubscriber — do not register on dedicated servers.
        ModIntegrations.logAvailability();
        LOG.info("LivingMods loaded");
    }
}
