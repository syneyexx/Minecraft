package com.livingmods.neoforge.command;

import com.livingmods.neoforge.LivingModsWorldIds;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LivingModsCommands {
    private LivingModsCommands() {}

    public static void register(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("livingmods")
                .then(Commands.literal("locate")
                        .then(Commands.argument("category", StringArgumentType.word())
                                .executes(ctx -> locate(ctx.getSource(), StringArgumentType.getString(ctx, "category"), "", 5))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> locate(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "category"),
                                                StringArgumentType.getString(ctx, "filter"),
                                                5))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 32))
                                                .executes(ctx -> locate(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "category"),
                                                        StringArgumentType.getString(ctx, "filter"),
                                                        IntegerArgumentType.getInteger(ctx, "limit"))))))));
    }

    private static int locate(CommandSourceStack source, String category, String filter, int limit) {
        if (source.getEntity() == null) {
            source.sendFailure(Component.literal("Requires entity context"));
            return 0;
        }
        int ox = (int) source.getEntity().getX();
        int oz = (int) source.getEntity().getZ();
        UUID worldId = LivingModsWorldIds.fromSeed(source.getServer().overworld().getSeed());
        SidecarClient client = WorldSessionLifecycle.clientFor(worldId);
        if (client != null && !client.degraded()) {
            RequestPayloads.LocateQuery query = new RequestPayloads.LocateQuery(category, filter, ox, oz, limit);
            byte[] payload;
            try {
                payload = query.encode();
            } catch (Exception e) {
                source.sendFailure(Component.literal("Locate encode failed"));
                return 0;
            }
            CompletableFuture<?> future = client.sendAsync(MessageType.LOCATE, System.nanoTime(), payload)
                    .thenAccept(env -> {
                        try {
                            List<String> hits = PayloadIo.decodeStringList(env.payload());
                            for (String hit : hits) {
                                source.sendSuccess(() -> Component.literal(hit), false);
                            }
                        } catch (Exception e) {
                            source.sendFailure(Component.literal("Locate failed: " + e.getMessage()));
                        }
                    });
            source.sendSuccess(() -> Component.literal("Locating via sidecar…"), false);
            return 1;
        }
        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) {
            source.sendFailure(Component.literal("World plan not ready"));
            return 0;
        }
        int count = 0;
        for (PlannedSettlement s : plan.settlements().values()) {
            if (!categoryMatches(category, s)) continue;
            if (!filter.isEmpty() && !s.name().toLowerCase().contains(filter.toLowerCase())) continue;
            source.sendSuccess(() -> Component.literal(s.name() + " @ " + s.center().x() + ", " + s.center().z()), false);
            if (++count >= limit) break;
        }
        return count;
    }

    private static boolean categoryMatches(String category, PlannedSettlement s) {
        String cat = category.toLowerCase();
        if (cat.equals("settlement")) return true;
        if (cat.equals("capital")) return s.capital();
        if (cat.equals("hamlet")) return s.tier().name().equalsIgnoreCase("HAMLET");
        if (cat.equals("village")) return s.tier().name().equalsIgnoreCase("VILLAGE");
        if (cat.equals("town")) return s.tier().name().equalsIgnoreCase("TOWN");
        if (cat.equals("city")) return s.tier().name().equalsIgnoreCase("CITY");
        if (cat.equals("port")) return s.role().name().equalsIgnoreCase("PORT");
        if (cat.equals("wizardtrees")) return s.role().name().equalsIgnoreCase("WIZARD_TREES");
        return s.tier().name().toLowerCase().contains(cat) || s.role().name().toLowerCase().contains(cat);
    }
}
