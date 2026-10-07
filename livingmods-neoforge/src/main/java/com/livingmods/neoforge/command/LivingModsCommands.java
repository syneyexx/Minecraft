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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        UUID worldId = WorldSessionLifecycle.activeWorldId();
        if (worldId == null) {
            worldId = LivingModsWorldIds.fromSeed(source.getServer().overworld().getSeed());
        }
        SidecarClient client = WorldSessionLifecycle.clientFor(worldId);
        if (client != null && client.isReady()) {
            RequestPayloads.LocateQuery query = new RequestPayloads.LocateQuery(category, "", ox, oz, limit);
            byte[] payload;
            try {
                payload = query.encode();
            } catch (Exception e) {
                source.sendFailure(Component.literal("Locate encode failed"));
                return 0;
            }
            CompletableFuture<?> ignored = client.sendAsync(MessageType.LOCATE, payload)
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
        int count = locateOffline(source, plan, category, ox, oz, limit);
        if (count == 0) {
            source.sendFailure(Component.literal("No matches for " + category));
        }
        return count;
    }

    private static int locateOffline(
            CommandSourceStack source,
            WorldPlan plan,
            String category,
            int ox,
            int oz,
            int limit
    ) {
        String cat = category.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        Map<String, String> kingdomNames = new java.util.HashMap<>();
        for (PlannedKingdom k : plan.kingdoms()) {
            kingdomNames.put(k.id().toString(), k.name());
        }

        if (cat.equals("kingdom")) {
            for (PlannedKingdom k : plan.kingdoms()) {
                hits.add(Hit.of(k.name(), "KINGDOM", k.name(),
                        k.capitalCenter().x(), k.capitalCenter().z(), ox, oz));
            }
        } else if (cat.equals("mine")) {
            for (PlannedResourceSite site : plan.resourceSites()) {
                String n = site.resource().name();
                if (n.contains("IRON") || n.contains("COAL") || n.contains("GOLD") || n.contains("STONE")) {
                    hits.add(Hit.of(n, "MINE", "", site.center().x(), site.center().z(), ox, oz));
                }
            }
        } else if (cat.equals("ruin")) {
            for (PlannedRuin ruin : plan.ruins()) {
                var c = ruin.bounds().center();
                hits.add(Hit.of(ruin.historicalNote(), "RUIN", "", c.x(), c.z(), ox, oz));
            }
        } else {
            for (PlannedSettlement s : plan.settlements().values()) {
                if (!categoryMatches(cat, s)) continue;
                String kingdom = s.ownerKingdom().map(id -> kingdomNames.getOrDefault(id.toString(), ""))
                        .orElse("");
                hits.add(Hit.of(s.name(), s.tier().name(), kingdom, s.center().x(), s.center().z(), ox, oz));
            }
        }

        hits.sort(Comparator.comparingDouble(Hit::distance));
        int count = 0;
        for (Hit hit : hits) {
            source.sendSuccess(() -> Component.literal(hit.format()), false);
            if (++count >= limit) break;
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

    private record Hit(String name, String type, String kingdom, int x, int z, double distance) {
        static Hit of(String name, String type, String kingdom, int x, int z, int ox, int oz) {
            double dx = x - ox;
            double dz = z - oz;
            return new Hit(name, type, kingdom == null ? "" : kingdom, x, z, Math.sqrt(dx * dx + dz * dz));
        }

        String format() {
            String k = kingdom.isEmpty() ? "-" : kingdom;
            return String.format(Locale.ROOT, "%s | %s | %s | %d, %d | %.0fm",
                    name, type, k, x, z, distance);
        }
    }
}
