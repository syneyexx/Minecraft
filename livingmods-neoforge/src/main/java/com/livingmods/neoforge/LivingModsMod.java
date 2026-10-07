package com.livingmods.neoforge;

import com.livingmods.neoforge.command.LivingModsCommands;
import com.livingmods.neoforge.entity.CitizenEntity;
import com.livingmods.neoforge.entity.LivingModsEntities;
import com.livingmods.neoforge.integrations.ModIntegrations;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.ChunkMaterializationHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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
        modBus.addListener(LivingModsCommands::register);
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(LivingModsEntities.CITIZEN.get(), CitizenEntity.createAttributes().build()));
        NeoForge.EVENT_BUS.register(ChunkMaterializationHandler.class);
        NeoForge.EVENT_BUS.register(WorldSessionLifecycle.class);
        NeoForge.EVENT_BUS.register(LivingModsClientEvents.class);
        ModIntegrations.logAvailability();
        LOG.info("LivingMods loaded");
    }
}
