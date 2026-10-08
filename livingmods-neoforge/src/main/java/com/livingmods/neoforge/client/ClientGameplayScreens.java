package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;

/** Client-only screen openers invoked from network bridge. */
public final class ClientGameplayScreens {
    private ClientGameplayScreens() {}

    public static void openCitizen(CitizenInteractionPayloads.OpenScreen payload) {
        Minecraft.getInstance().setScreen(new CitizenDialogueScreen(payload));
    }

    public static void updateDialogue(CitizenInteractionPayloads.DialogueUpdate update) {
        if (Minecraft.getInstance().screen instanceof CitizenDialogueScreen screen) {
            screen.applyDialogueUpdate(update);
        }
    }

    public static void openMarket(CitizenInteractionPayloads.MarketScreenData data) {
        if (Minecraft.getInstance().screen instanceof MarketScreen screen) {
            screen.applyMarketData(data);
        } else {
            Minecraft.getInstance().setScreen(new MarketScreen(data));
        }
    }

    public static void openRealm(CitizenInteractionPayloads.RealmPanel panel) {
        Minecraft.getInstance().setScreen(new RealmManagementScreen(panel));
    }

    public static void mergeTasks(CitizenInteractionPayloads.TaskJournalUpdate update) {
        if (Minecraft.getInstance().screen instanceof TaskJournalScreen screen) {
            screen.merge(update);
        } else {
            Minecraft.getInstance().setScreen(new TaskJournalScreen(new ArrayList<>(update.tasks())));
        }
    }
}
