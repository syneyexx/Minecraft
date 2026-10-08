package com.livingmods.worldgen.validation;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedRuin;
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

        Map<SettlementId, PlannedSettlement> settlements = plan.settlements();
        Set<SettlementId> seenSettlementIds = new HashSet<>();
        Set<KingdomId> seenKingdomIds = new HashSet<>();
        Set<RoadId> seenRoadIds = new HashSet<>();

        int half = 100_000; // absolute coordinate sanity bound
        for (PlannedKingdom k : plan.kingdoms()) {
            if (!seenKingdomIds.add(k.id())) {
                errors.add("Duplicate kingdom id " + k.id());
            }
        }

        for (PlannedSettlement s : settlements.values()) {
            if (!seenSettlementIds.add(s.id())) {
                errors.add("Duplicate settlement id " + s.id());
            }
            if (!s.underground() && s.ownerKingdom().isEmpty() && !s.capital()) {
                errors.add("Surface settlement " + s.name() + " lacks owner kingdom");
            }
            if (Math.abs(s.center().x()) > half || Math.abs(s.center().z()) > half) {
                errors.add("Settlement " + s.name() + " outside coordinate bounds");
            }
            if (s.ownerKingdom().isPresent()) {
                boolean ownerExists = plan.kingdoms().stream().anyMatch(k -> k.id().equals(s.ownerKingdom().get()));
                if (!ownerExists) {
                    errors.add("Settlement " + s.name() + " references missing owner kingdom");
                }
            }
            validateBuildings(s, errors);
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
            } else if (capital.ownerKingdom().isEmpty() || !capital.ownerKingdom().get().equals(k.id())) {
                errors.add("Kingdom " + k.name() + " capital ownership mismatch");
            }

            for (SettlementId sid : k.settlementIds()) {
                if (!settlements.containsKey(sid)) {
                    errors.add("Kingdom " + k.name() + " references missing settlement " + sid);
                }
            }
        }

        for (PlannedRoad road : plan.roads()) {
            if (!seenRoadIds.add(road.id())) {
                errors.add("Duplicate road id " + road.id());
            }
            if (road.path() == null || road.path().isEmpty()) {
                errors.add("Road " + road.id() + " has empty path");
            }
            if (road.fromSettlement().isPresent() && !settlements.containsKey(road.fromSettlement().get())) {
                errors.add("Road " + road.id() + " fromSettlement missing");
            }
            if (road.toSettlement().isPresent() && !settlements.containsKey(road.toSettlement().get())) {
                errors.add("Road " + road.id() + " toSettlement missing");
            }
            if (road.bridges() != null) {
                for (var bridge : road.bridges()) {
                    if (bridge.start() == null || bridge.end() == null) {
                        errors.add("Bridge on road " + road.id() + " missing endpoints");
                    }
                }
            }
        }

        Set<Long> resourceKeys = new HashSet<>();
        for (PlannedResourceSite site : plan.resourceSites()) {
            long key = site.center().packed();
            if (!resourceKeys.add(key)) {
                errors.add("Duplicate resource site at " + site.center());
            }
            if (Math.abs(site.center().x()) > half || Math.abs(site.center().z()) > half) {
                errors.add("Resource site outside bounds at " + site.center());
            }
        }

        for (int i = 0; i < plan.banditCamps().size(); i++) {
            PlannedBanditCamp a = plan.banditCamps().get(i);
            for (PlannedSettlement s : settlements.values()) {
                if (s.bounds() != null && s.bounds().expand(-8).contains(a.center())) {
                    errors.add("Bandit camp inside core settlement footprint near " + s.name());
                    break;
                }
            }
            for (int j = i + 1; j < plan.banditCamps().size(); j++) {
                PlannedBanditCamp b = plan.banditCamps().get(j);
                if (a.center().distanceTo(b.center()) < 24) {
                    errors.add("Bandit camps too close at " + a.center());
                    break;
                }
            }
        }

        for (PlannedRuin ruin : plan.ruins()) {
            if (ruin.bounds() == null || ruin.bounds().width() <= 0 || ruin.bounds().depth() <= 0) {
                errors.add("Invalid ruin bounds");
            }
        }

        for (PlannedSettlement s : settlements.values()) {
            if (s.role() == com.livingmods.common.model.SettlementRole.WIZARD_TREES && !s.underground()) {
                errors.add("Wizard Trees settlement " + s.name() + " must be underground");
            }
        }

        if (plan.contentHash() == 0 && !settlements.isEmpty()) {
            errors.add("contentHash must be non-zero for non-empty plans");
        }

        return errors;
    }

    private void validateBuildings(PlannedSettlement s, List<String> errors) {
        Map<Long, PlannedBuilding> occupied = new HashMap<>();
        BoundingBox2 settlementBounds = s.bounds();
        for (PlannedBuilding b : s.buildings()) {
            BoundingBox2 fp = b.footprint();
            if (settlementBounds != null && !settlementBounds.expand(8).intersects(fp)) {
                errors.add("Building outside settlement space in " + s.name());
                return;
            }
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
            // Soft check: buildings that require access should be near a street when streets exist.
            if (s.streetNetwork() != null && !s.streetNetwork().isEmpty()
                    && (b.role().name().contains("HOUSE") || b.role().name().contains("SHOP")
                    || b.role().name().contains("MARKET") || b.role().name().contains("WAREHOUSE"))) {
                boolean nearStreet = false;
                var center = fp.center();
                for (var street : s.streetNetwork()) {
                    if (center.distanceTo(street) <= 12) {
                        nearStreet = true;
                        break;
                    }
                }
                if (!nearStreet) {
                    // Non-fatal aesthetic — skip adding as hard error.
                }
            }
        }

        if (s.walls() && s.gatePositions() != null && !s.gatePositions().isEmpty()) {
            for (var gate : s.gatePositions()) {
                boolean wallNear = false;
                if (s.wallPath() != null) {
                    for (var wp : s.wallPath()) {
                        if (gate.distanceTo(wp) <= 6) {
                            wallNear = true;
                            break;
                        }
                    }
                }
                if (!wallNear && s.wallPath() != null && !s.wallPath().isEmpty()) {
                    errors.add("Gate without nearby wall in settlement " + s.name());
                    return;
                }
            }
        }
    }
}
