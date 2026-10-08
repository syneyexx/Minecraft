package com.livingmods.neoforge.gameplay;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact slot/stack inventory mutations with deterministic rollback.
 * Avoids generic removeItems that can touch unrelated stacks incorrectly on rollback.
 */
public final class ServerInventoryTransaction {
    public record SlotDelta(int slot, ItemStack before, ItemStack after) {}

    private final ServerPlayer player;
    private final List<SlotDelta> deltas = new ArrayList<>();
    private boolean committed;
    private boolean rolledBack;

    public ServerInventoryTransaction(ServerPlayer player) {
        this.player = player;
    }

    public boolean hasEnough(Item item, int amount) {
        if (item == null || amount <= 0) return amount <= 0;
        int counted = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) counted += stack.getCount();
            if (counted >= amount) return true;
        }
        return false;
    }

    public boolean hasEmptySpaceFor(Item item, int amount) {
        if (item == null || amount <= 0) return true;
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                remaining -= Math.min(remaining, new ItemStack(item).getMaxStackSize());
            } else if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                remaining -= Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
            }
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    /** Consume exact amount; records per-slot before/after for rollback. */
    public boolean consume(Item item, int amount) {
        if (committed || rolledBack) return false;
        if (!hasEnough(item, amount)) return false;
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item)) continue;
            ItemStack before = stack.copy();
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            deltas.add(new SlotDelta(i, before, stack.copy()));
        }
        return remaining == 0;
    }

    /** Insert exact amount into inventory; fails (and rolls back this insert) if full. */
    public boolean give(Item item, int amount) {
        if (committed || rolledBack || item == null || amount <= 0) return amount <= 0;
        if (!hasEmptySpaceFor(item, amount)) return false;
        int remaining = amount;
        // Fill existing stacks first
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item) || stack.getCount() >= stack.getMaxStackSize()) continue;
            ItemStack before = stack.copy();
            int space = stack.getMaxStackSize() - stack.getCount();
            int add = Math.min(space, remaining);
            stack.grow(add);
            remaining -= add;
            deltas.add(new SlotDelta(i, before, stack.copy()));
        }
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) continue;
            ItemStack before = stack.copy();
            int add = Math.min(new ItemStack(item).getMaxStackSize(), remaining);
            ItemStack placed = new ItemStack(item, add);
            player.getInventory().setItem(i, placed);
            remaining -= add;
            deltas.add(new SlotDelta(i, before, placed.copy()));
        }
        if (remaining > 0) {
            rollback();
            return false;
        }
        return true;
    }

    public void commit() {
        committed = true;
        deltas.clear();
    }

    public void rollback() {
        if (committed || rolledBack) return;
        // Restore in reverse order so stacked ops on same slot unwind correctly.
        for (int i = deltas.size() - 1; i >= 0; i--) {
            SlotDelta d = deltas.get(i);
            player.getInventory().setItem(d.slot(), d.before().copy());
        }
        deltas.clear();
        rolledBack = true;
    }

    public boolean consumeCurrency(int amount) {
        return consume(ResourceItemMapping.currencyItem(), amount);
    }

    public boolean giveCurrency(int amount) {
        return give(ResourceItemMapping.currencyItem(), amount);
    }
}
