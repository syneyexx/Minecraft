package com.livingmods.neoforge.command;

import com.livingmods.neoforge.LivingModsWorldIds;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRuin;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LivingModsCommands {
    private static final String[] LOCATE_CATEGORIES = {
            "settlement", "capital", "city", "town", "village", "hamlet",
            "mine", "port", "ruin", "kingdom", "wizardtrees"
    };

    private LivingModsCommands() {}

    public static void register(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> locate = Commands.literal("locate");
        for (String category : LOCATE_CATEGORIES) {
            locate = locate.then(Commands.literal(category)
                    .executes(ctx -> locate(ctx.getSource(), category, 5)));
        }
        dispatcher.register(Commands.literal("livingmods").then(locate));
    }

    private static int locate(CommandSourceStack source, String category, int limit) {
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("Requires entity context"));
            return 0;
        }
        int ox = (int) source.getEntity().getX();
        int oz = (int) source.getEntity().getZ();
        UUID worldId = LivingModsWorldIds.fromSeed(source.getServer().overworld().getSeed());
        SidecarClient client = WorldSessionLifecycle.clientFor(worldId);
        if (client != null && !client.degraded()) {
            RequestPayloads.LocateQuery query = new RequestPayloads.LocateQuery(category, "", ox, oz, limit);
            byte[] payload;
            try {
                payload = query.encode();
            } catch (Exception e) {
                source.sendFailure(Component.literal("Locate encode failed"));
                return 0;
            }
            CompletableFuture<?> ignored = client.sendAsync(MessageType.LOCATE, System.nanoTime(), payload)
                    .thenAccept(env -> {
                        try {
                            List<String> hits = PayloadIo.decodeStringList(env.payload());
                            if (hits.isEmpty()) {
                                source.sendFailure(Component.literal("No matches for " + category));
                                return;
                            }
                            for (String hit : hits) {
                                source.sendSuccess(() -> Component.literal(hit), false);
                            }
                        } catch (Exception e) {
                            source.sendFailure(Component.literal("Locate failed: " + e.getMessage()));
                        }
                    });
            source.sendSuccess(() -> Component.literal("Locating " + category + " via sidecar…"), false);
            return 1;
        }

        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) {
            source.sendFailure(Component.literal("World plan not ready (sidecar offline — plan cache empty)"));
            return 0;
        }
        int count = locateOffline(source, plan, category, limit);
        if (count == 0) {
            source.sendFailure(Component.literal("No matches for " + category));
        }
        return count;
    }

    private static int locateOffline(CommandSourceStack source, WorldPlan plan, String category, int limit) {
        String cat = category.toLowerCase();
        int count = 0;
        if (cat.equals("kingdom")) {
            for (PlannedKingdom k : plan.kingdoms()) {
                source.sendSuccess(() -> Component.literal(
                        k.name() + " @ " + k.capitalCenter().x() + ", " + k.capitalCenter().z() + " [KINGDOM]"), false);
                if (++count >= limit) {
                    break;
                }
            }
            return count;
        }
        if (cat.equals("mine")) {
            for (PlannedResourceSite site : plan.resourceSites()) {
                if (site.resource().name().contains("IRON") || site.resource().name().contains("COAL")
                        || site.resource().name().contains("GOLD") || site.resource().name().contains("STONE")) {
                    source.sendSuccess(() -> Component.literal(
                            site.resource().name() + " @ " + site.center().x() + ", " + site.center().z() + " [MINE]"), false);
                    if (++count >= limit) {
                        break;
                    }
                }
            }
            return count;
        }
        if (cat.equals("ruin")) {
            for (PlannedRuin ruin : plan.ruins()) {
                var c = ruin.bounds().center();
                source.sendSuccess(() -> Component.literal(
                        ruin.historicalNote() + " @ " + c.x() + ", " + c.z() + " [RUIN]"), false);
                if (++count >= limit) {
                    break;
                }
            }
            return count;
        }
        for (PlannedSettlement s : plan.settlements().values()) {
            if (!categoryMatches(cat, s)) {
                continue;
            }
            source.sendSuccess(() -> Component.literal(
                    s.name() + " @ " + s.center().x() + ", " + s.center().z() + " [" + s.tier() + "]"), false);
            if (++count >= limit) {
                break;
            }
        }
        return count;
    }

    private static boolean categoryMatches(String category, PlannedSettlement s) {
        return switch (category) {
            case "settlement" -> true;
            case "capital" -> s.capital();
            case "hamlet" -> s.tier().name().equalsIgnoreCase("HAMLET");
            case "village" -> s.tier().name().equalsIgnoreCase("VILLAGE");
            case "town" -> s.tier().name().equalsIgnoreCase("TOWN");
            case "city" -> s.tier().name().equalsIgnoreCase("CITY") || s.tier().name().equalsIgnoreCase("METROPOLIS");
            case "port" -> s.role().name().equalsIgnoreCase("PORT");
            case "wizardtrees" -> s.role().name().equalsIgnoreCase("WIZARD_TREES") || s.underground();
            default -> s.tier().name().equalsIgnoreCase(category) || s.role().name().equalsIgnoreCase(category);
        };
    }
}
