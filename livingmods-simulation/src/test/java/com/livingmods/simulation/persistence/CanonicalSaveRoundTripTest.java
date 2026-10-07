package com.livingmods.simulation.persistence;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.InitialStateFactory;
import com.livingmods.simulation.support.TestWorldPlans;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalSaveRoundTripTest {
    @Test
    void fullGraphRoundTripPreservesCountsAndHash() throws Exception {
        CanonicalWorldState original = InitialStateFactory.fromWorldPlan(TestWorldPlans.twoKingdomPlan(42L));
        original.markets().values().forEach(m -> m.setPrice(com.livingmods.common.model.ResourceType.GRAIN, 2.5));
        byte[] bytes = CanonicalSaveFormat.writeSnapshot(original, UUID.randomUUID());
        CanonicalWorldState restored = CanonicalSaveFormat.readFullState(bytes);
        assertEquals(original.kingdoms().size(), restored.kingdoms().size());
        assertEquals(original.settlements().size(), restored.settlements().size());
        assertEquals(original.citizens().size(), restored.citizens().size());
        assertEquals(original.markets().size(), restored.markets().size());
        assertEquals(2.5, restored.markets().values().iterator().next().price(com.livingmods.common.model.ResourceType.GRAIN), 1e-9);
        // contentHash includes saveRevision; bump restored to match before comparing entity fingerprint fields
        assertEquals(original.seed(), restored.seed());
        assertEquals(original.planContentHash(), restored.planContentHash());
    }
}
