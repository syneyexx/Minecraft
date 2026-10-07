package com.livingmods.simulation.support;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;

import java.util.List;
import java.util.Optional;

public final class TestWorldPlans {
    private TestWorldPlans() {}

    public static WorldPlan twoKingdomPlan(long seed) {
        CultureId cultureA = CultureId.deterministic(seed, 0);
        CultureId cultureB = CultureId.deterministic(seed, 1);

        SettlementId capitalA = SettlementId.deterministic(seed, 0);
        SettlementId villageA = SettlementId.deterministic(seed, 1);
        SettlementId capitalB = SettlementId.deterministic(seed, 2);

        KingdomId kingdomA = KingdomId.deterministic(seed, 0);
        KingdomId kingdomB = KingdomId.deterministic(seed, 1);

        PlannedSettlement capA = settlement(capitalA, "Aldoria", kingdomA, cultureA, true, 120, BlockPos2.of(0, 0));
        PlannedSettlement vilA = settlement(villageA, "Millbrook", kingdomA, cultureA, false, 40, BlockPos2.of(200, 50));
        PlannedSettlement capB = settlement(capitalB, "Bravos", kingdomB, cultureB, true, 80, BlockPos2.of(800, 0));

        PlannedKingdom kA = new PlannedKingdom(
                kingdomA, "Kingdom A", cultureA, "culture_a", GovernmentType.MONARCHY,
                capitalA, capA.center(), List.of(capitalA, villageA), List.of(BlockPos2.of(0, 0)), false, "faith_a");
        PlannedKingdom kB = new PlannedKingdom(
                kingdomB, "Kingdom B", cultureB, "culture_b", GovernmentType.MONARCHY,
                capitalB, capB.center(), List.of(capitalB), List.of(BlockPos2.of(800, 0)), false, "faith_b");

        long hash = Hashing.stateHash(seed, capA.id().hashCode(), capB.id().hashCode());
        return new WorldPlan(
                seed,
                List.of(kA, kB),
                List.of(capA, vilA, capB),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                hash
        );
    }

    private static PlannedSettlement settlement(
            SettlementId id,
            String name,
            KingdomId owner,
            CultureId culture,
            boolean capital,
            int population,
            BlockPos2 center
    ) {
        return new PlannedSettlement(
                id,
                name,
                capital ? SettlementTier.CAPITAL : SettlementTier.VILLAGE,
                SettlementRole.GENERAL,
                center,
                BoundingBox2.around(center, 32),
                Optional.of(owner),
                culture,
                "culture",
                capital,
                capital,
                false,
                population,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
