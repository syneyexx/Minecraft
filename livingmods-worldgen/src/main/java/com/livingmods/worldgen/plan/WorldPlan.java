package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.geo.ChunkCoord;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.version.LivingModsVersions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Complete deterministic civilization plan independent of chunk generation order.
 * Chunks only query which predetermined placements intersect them.
 */
public final class WorldPlan {
    private final long seed;
    private final int worldgenVersion;
    private final List<PlannedKingdom> kingdoms;
    private final Map<SettlementId, PlannedSettlement> settlements;
    private final List<PlannedRoad> roads;
    private final List<PlannedRuin> ruins;
    private final List<PlannedResourceSite> resourceSites;
    private final List<PlannedBanditCamp> banditCamps;
    private final long contentHash;

    public WorldPlan(
            long seed,
            List<PlannedKingdom> kingdoms,
            List<PlannedSettlement> settlements,
            List<PlannedRoad> roads,
            List<PlannedRuin> ruins,
            List<PlannedResourceSite> resourceSites,
            List<PlannedBanditCamp> banditCamps,
            long contentHash
    ) {
        this.seed = seed;
        this.worldgenVersion = LivingModsVersions.WORLDGEN_VERSION;
        this.kingdoms = List.copyOf(kingdoms);
        Map<SettlementId, PlannedSettlement> map = new LinkedHashMap<>();
        for (PlannedSettlement s : settlements) {
            map.put(s.id(), s);
        }
        this.settlements = Collections.unmodifiableMap(map);
        this.roads = List.copyOf(roads);
        this.ruins = List.copyOf(ruins);
        this.resourceSites = List.copyOf(resourceSites);
        this.banditCamps = List.copyOf(banditCamps);
        this.contentHash = contentHash;
    }

    public long seed() { return seed; }
    public int worldgenVersion() { return worldgenVersion; }
    public List<PlannedKingdom> kingdoms() { return kingdoms; }
    public Map<SettlementId, PlannedSettlement> settlements() { return settlements; }
    public List<PlannedRoad> roads() { return roads; }
    public List<PlannedRuin> ruins() { return ruins; }
    public List<PlannedResourceSite> resourceSites() { return resourceSites; }
    public List<PlannedBanditCamp> banditCamps() { return banditCamps; }
    public long contentHash() { return contentHash; }

    public Optional<PlannedSettlement> settlement(SettlementId id) {
        return Optional.ofNullable(settlements.get(id));
    }

    /** Chunk materialization query — never invents new plans. */
    public ChunkCivilizationSlice sliceForChunk(ChunkCoord chunk) {
        BoundingBox2 chunkBox = BoundingBox2.of(
                chunk.x() << 4, chunk.z() << 4,
                (chunk.x() << 4) + 15, (chunk.z() << 4) + 15
        );
        List<PlannedSettlement> hitSettlements = new ArrayList<>();
        for (PlannedSettlement s : settlements.values()) {
            if (s.bounds().intersects(chunkBox)) {
                hitSettlements.add(s);
            }
        }
        List<PlannedRoad> hitRoads = new ArrayList<>();
        for (PlannedRoad r : roads) {
            for (BlockPos2 p : r.path()) {
                if (chunkBox.contains(p)) {
                    hitRoads.add(r);
                    break;
                }
            }
        }
        List<PlannedRuin> hitRuins = new ArrayList<>();
        for (PlannedRuin r : ruins) {
            if (r.bounds().intersects(chunkBox)) hitRuins.add(r);
        }
        List<PlannedResourceSite> hitSites = new ArrayList<>();
        for (PlannedResourceSite r : resourceSites) {
            if (chunkBox.contains(r.center())) hitSites.add(r);
        }
        List<PlannedBanditCamp> hitCamps = new ArrayList<>();
        for (PlannedBanditCamp c : banditCamps) {
            if (chunkBox.contains(c.center())) hitCamps.add(c);
        }
        return new ChunkCivilizationSlice(chunk, hitSettlements, hitRoads, hitRuins, hitSites, hitCamps);
    }
}
