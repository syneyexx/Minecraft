package com.livingmods.worldgen.validation;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorldPlanValidator {

    public void validateOrThrow(WorldPlan plan) {
        List<String> errors = validate(plan);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("World plan validation failed: " + String.join("; ", errors));
        }
    }

    public List<String> validate(WorldPlan plan) {
        List<String> errors = new java.util.ArrayList<>();

        Map<com.livingmods.common.id.SettlementId, PlannedSettlement> settlements = plan.settlements();
        Set<com.livingmods.common.id.SettlementId> seenIds = new HashSet<>();

        for (PlannedSettlement s : settlements.values()) {
            if (!seenIds.add(s.id())) {
                errors.add("Duplicate settlement id " + s.id());
            }
            if (!s.underground() && s.ownerKingdom().isEmpty() && !s.capital()) {
                errors.add("Surface settlement " + s.name() + " lacks owner kingdom");
            }
            validateNoOverlappingBuildings(s, errors);
        }

        for (PlannedKingdom k : plan.kingdoms()) {
            if (k.underground()) continue;

            long capitals = settlements.values().stream()
                    .filter(s -> s.ownerKingdom().orElse(null) != null && s.ownerKingdom().get().equals(k.id()))
                    .filter(PlannedSettlement::capital)
                    .count();
            if (capitals != 1) {
                errors.add("Kingdom " + k.name() + " must have exactly one capital settlement, found " + capitals);
            }

            PlannedSettlement capital = settlements.get(k.capitalId());
            if (capital == null) {
                errors.add("Kingdom " + k.name() + " capital id not found in settlements");
            } else if (!capital.capital()) {
                errors.add("Kingdom " + k.name() + " capital settlement is not marked capital");
            }

            for (com.livingmods.common.id.SettlementId sid : k.settlementIds()) {
                if (!settlements.containsKey(sid)) {
                    errors.add("Kingdom " + k.name() + " references missing settlement " + sid);
                }
            }
        }

        if (plan.contentHash() == 0 && !settlements.isEmpty()) {
            errors.add("contentHash must be non-zero for non-empty plans");
        }

        if (plan.seed() == 0 && !plan.kingdoms().isEmpty()) {
            // allowed but note — no error
        }

        return errors;
    }

    private void validateNoOverlappingBuildings(PlannedSettlement s, List<String> errors) {
        Map<Long, PlannedBuilding> occupied = new HashMap<>();
        for (PlannedBuilding b : s.buildings()) {
            BoundingBox2 fp = b.footprint();
            for (int x = fp.minX(); x <= fp.maxX(); x++) {
                for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                    long key = ((long) x << 32) ^ (z & 0xffffffffL);
                    if (occupied.containsKey(key)) {
                        errors.add("Overlapping building footprints in settlement " + s.name() + " at " + x + "," + z);
                        return;
                    }
                    occupied.put(key, b);
                }
            }
        }
    }
}
