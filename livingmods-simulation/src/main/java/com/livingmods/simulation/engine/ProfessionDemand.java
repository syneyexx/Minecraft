package com.livingmods.simulation.engine;

import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.MilitaryRole;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedSettlement;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Settlement demand → profession assignment (not mostly random farmer/trader/guard/builder). */
public final class ProfessionDemand {
    private ProfessionDemand() {}

    public static Profession pickForSettlement(
            PlannedSettlement planned,
            DeterministicRandom rng,
            boolean leaderCandidate,
            boolean child
    ) {
        if (child) return Profession.CHILD;
        if (leaderCandidate) return Profession.NOBLE;

        Map<Profession, Double> weights = baseWeights(planned.role());
        for (PlannedBuilding b : planned.buildings()) {
            applyBuilding(weights, b.role());
        }
        return weightedPick(weights, rng);
    }

    public static Profession apprenticeFromCaregiver(Profession caregiver, DeterministicRandom rng) {
        if (caregiver == null || caregiver == Profession.CHILD || caregiver == Profession.UNEMPLOYED) {
            return rng.pick(List.of(Profession.FARMER, Profession.ARTISAN, Profession.BUILDER));
        }
        return caregiver;
    }

    public static MilitaryRole militaryFor(Profession profession) {
        return switch (profession) {
            case GUARD -> MilitaryRole.GUARD;
            case SOLDIER -> MilitaryRole.SOLDIER;
            case RULER -> MilitaryRole.COMMANDER;
            case NOBLE -> MilitaryRole.OFFICER;
            default -> MilitaryRole.NONE;
        };
    }

    public static Profession workplaceProfession(BuildingRole role) {
        return switch (role) {
            case MINE_ENTRANCE -> Profession.MINER;
            case DOCK -> Profession.SAILOR;
            case SCHOOL -> Profession.TEACHER;
            case GUARDHOUSE, BARRACKS, GATEHOUSE, TOWER -> Profession.GUARD;
            case SMITHY -> Profession.BLACKSMITH;
            case WORKSHOP -> Profession.ARTISAN;
            case MARKET_HALL, MARKET_STALL, SHOP -> Profession.TRADER;
            case TEMPLE -> Profession.PRIEST;
            case CLINIC -> Profession.HEALER;
            case SAWMILL -> Profession.LUMBERJACK;
            case FARMHOUSE, BARN, MILL -> Profession.FARMER;
            case WAREHOUSE -> Profession.MERCHANT;
            case TAVERN -> Profession.TRADER;
            default -> Profession.UNEMPLOYED;
        };
    }

    private static Map<Profession, Double> baseWeights(SettlementRole role) {
        Map<Profession, Double> w = new EnumMap<>(Profession.class);
        w.put(Profession.FARMER, 1.0);
        w.put(Profession.ARTISAN, 0.4);
        w.put(Profession.BUILDER, 0.35);
        w.put(Profession.TRADER, 0.3);
        w.put(Profession.GUARD, 0.25);
        switch (role) {
            case MINING -> {
                w.put(Profession.MINER, 2.5);
                w.put(Profession.BLACKSMITH, 0.8);
            }
            case PORT, FISHING -> {
                w.put(Profession.SAILOR, 2.0);
                w.put(Profession.DOCKWORKER, 1.5);
                w.put(Profession.FISHER, 1.5);
            }
            case LOGGING -> w.put(Profession.LUMBERJACK, 2.0);
            case MILITARY -> {
                w.put(Profession.GUARD, 2.0);
                w.put(Profession.SOLDIER, 1.5);
            }
            case RELIGIOUS -> w.put(Profession.PRIEST, 1.8);
            case FARMING -> w.put(Profession.FARMER, 2.5);
            case WIZARD_TREES, UNDERGROUND -> {
                w.put(Profession.SCHOLAR, 1.5);
                w.put(Profession.PRIEST, 1.2);
            }
            default -> { }
        }
        return w;
    }

    private static void applyBuilding(Map<Profession, Double> weights, BuildingRole role) {
        Profession p = workplaceProfession(role);
        if (p != Profession.UNEMPLOYED) {
            weights.merge(p, 1.2, Double::sum);
        }
        if (role == BuildingRole.SCHOOL) {
            weights.merge(Profession.SCHOLAR, 0.8, Double::sum);
        }
    }

    private static Profession weightedPick(Map<Profession, Double> weights, DeterministicRandom rng) {
        List<Profession> options = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        double total = 0;
        for (Map.Entry<Profession, Double> e : weights.entrySet()) {
            if (e.getValue() <= 0) continue;
            options.add(e.getKey());
            probs.add(e.getValue());
            total += e.getValue();
        }
        if (options.isEmpty() || total <= 0) {
            return Profession.FARMER;
        }
        double roll = rng.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < options.size(); i++) {
            acc += probs.get(i);
            if (roll <= acc) {
                return options.get(i);
            }
        }
        return options.getLast();
    }
}
