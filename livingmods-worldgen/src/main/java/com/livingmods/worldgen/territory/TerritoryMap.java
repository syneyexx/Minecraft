package com.livingmods.worldgen.territory;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Queryable kingdom territory system: macrocell ownership raster driven by influence fields
 * and natural boundaries (mountains, rivers, coast), not capital-radius circles.
 * <p>
 * Persisted with the world plan so ownership stays stable across algorithm upgrades.
 */
public final class TerritoryMap {
    public static final int DEFAULT_CELL_SIZE = 64;

    private final int cellSize;
    private final int originX;
    private final int originZ;
    private final int widthCells;
    private final int heightCells;
    private final List<KingdomId> kingdomIndex;
    /** Kingdom ordinal per cell; -1 = unclaimed, -2 = contested. */
    private final short[] ownerOrdinal;
    private final byte[] zoneOrdinal;
    private final Map<KingdomId, List<KingdomId>> adjacency;

    public TerritoryMap(
            int cellSize,
            int originX,
            int originZ,
            int widthCells,
            int heightCells,
            List<KingdomId> kingdomIndex,
            short[] ownerOrdinal,
            byte[] zoneOrdinal,
            Map<KingdomId, List<KingdomId>> adjacency
    ) {
        this.cellSize = cellSize;
        this.originX = originX;
        this.originZ = originZ;
        this.widthCells = widthCells;
        this.heightCells = heightCells;
        this.kingdomIndex = List.copyOf(kingdomIndex);
        this.ownerOrdinal = ownerOrdinal.clone();
        this.zoneOrdinal = zoneOrdinal.clone();
        Map<KingdomId, List<KingdomId>> adj = new LinkedHashMap<>();
        for (Map.Entry<KingdomId, List<KingdomId>> e : adjacency.entrySet()) {
            adj.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.adjacency = Collections.unmodifiableMap(adj);
    }

    public int cellSize() { return cellSize; }
    public int originX() { return originX; }
    public int originZ() { return originZ; }
    public int widthCells() { return widthCells; }
    public int heightCells() { return heightCells; }
    public List<KingdomId> kingdomIndex() { return kingdomIndex; }
    public short[] ownerOrdinalRaw() { return ownerOrdinal.clone(); }
    public byte[] zoneOrdinalRaw() { return zoneOrdinal.clone(); }
    public Map<KingdomId, List<KingdomId>> adjacency() { return adjacency; }

    public Optional<KingdomId> ownerAt(int x, int z) {
        int idx = cellIndex(x, z);
        if (idx < 0) return Optional.empty();
        short ord = ownerOrdinal[idx];
        if (ord < 0 || ord >= kingdomIndex.size()) return Optional.empty();
        return Optional.of(kingdomIndex.get(ord));
    }

    public TerritoryZone zoneAt(int x, int z) {
        int idx = cellIndex(x, z);
        if (idx < 0) return TerritoryZone.UNCLAIMED;
        return TerritoryZone.values()[zoneOrdinal[idx] & 0xff];
    }

    public boolean containsBlock(int x, int z) {
        return cellIndex(x, z) >= 0;
    }

    public List<KingdomId> adjacentKingdoms(KingdomId id) {
        return adjacency.getOrDefault(id, List.of());
    }

    public boolean insideOwnedTerritory(KingdomId id, int x, int z) {
        return ownerAt(x, z).filter(id::equals).isPresent();
    }

    public List<BlockPos2> territoryPolygonApprox(KingdomId id, int rays) {
        int ord = kingdomIndex.indexOf(id);
        if (ord < 0) return List.of();
        long sumX = 0, sumZ = 0;
        int n = 0;
        for (int i = 0; i < ownerOrdinal.length; i++) {
            if (ownerOrdinal[i] == ord) {
                int cx = originX + (i % widthCells) * cellSize + cellSize / 2;
                int cz = originZ + (i / widthCells) * cellSize + cellSize / 2;
                sumX += cx;
                sumZ += cz;
                n++;
            }
        }
        if (n == 0) return List.of();
        int centerX = (int) (sumX / n);
        int centerZ = (int) (sumZ / n);
        List<BlockPos2> poly = new ArrayList<>(rays);
        for (int i = 0; i < rays; i++) {
            double angle = (Math.PI * 2 * i) / rays;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            int lastX = centerX;
            int lastZ = centerZ;
            for (int dist = cellSize; dist < Math.max(widthCells, heightCells) * cellSize; dist += cellSize) {
                int x = centerX + (int) (dx * dist);
                int z = centerZ + (int) (dz * dist);
                if (!insideOwnedTerritory(id, x, z)) break;
                lastX = x;
                lastZ = z;
            }
            poly.add(BlockPos2.of(lastX, lastZ));
        }
        return poly;
    }

    private int cellIndex(int x, int z) {
        int cx = Math.floorDiv(x - originX, cellSize);
        int cz = Math.floorDiv(z - originZ, cellSize);
        if (cx < 0 || cz < 0 || cx >= widthCells || cz >= heightCells) return -1;
        return cz * widthCells + cx;
    }

    /**
     * Build territory from kingdom capitals + settlements, modulated by terrain barriers.
     */
    public static TerritoryMap build(
            TerrainProvider terrain,
            List<PlannedKingdom> kingdoms,
            List<PlannedSettlement> settlements,
            int civilizationRadiusBlocks,
            int cellSize
    ) {
        int half = Math.max(cellSize, civilizationRadiusBlocks);
        int originX = -half;
        int originZ = -half;
        int extent = half * 2;
        int widthCells = Math.max(1, (extent + cellSize - 1) / cellSize);
        int heightCells = widthCells;

        List<KingdomId> index = new ArrayList<>();
        List<InfluenceNode> nodes = new ArrayList<>();
        for (PlannedKingdom k : kingdoms) {
            if (k.underground()) continue;
            int ord = index.size();
            index.add(k.id());
            nodes.add(new InfluenceNode(ord, k.capitalCenter(), 1.0));
        }
        Map<KingdomId, Integer> ordinalOf = new HashMap<>();
        for (int i = 0; i < index.size(); i++) {
            ordinalOf.put(index.get(i), i);
        }
        for (PlannedSettlement s : settlements) {
            if (s.underground() || s.ownerKingdom().isEmpty()) continue;
            Integer ord = ordinalOf.get(s.ownerKingdom().get());
            if (ord == null) continue;
            double weight = s.capital() ? 0.85 : 0.35 + s.tier().ordinal() * 0.05;
            nodes.add(new InfluenceNode(ord, s.center(), weight));
        }

        short[] owners = new short[widthCells * heightCells];
        byte[] zones = new byte[widthCells * heightCells];
        double[] best = new double[owners.length];
        double[] second = new double[owners.length];
        short[] secondOwner = new short[owners.length];

        for (int i = 0; i < owners.length; i++) {
            owners[i] = -1;
            secondOwner[i] = -1;
            best[i] = 0;
            second[i] = 0;
            zones[i] = (byte) TerritoryZone.UNCLAIMED.ordinal();
        }

        for (int cz = 0; cz < heightCells; cz++) {
            for (int cx = 0; cx < widthCells; cx++) {
                int i = cz * widthCells + cx;
                int bx = originX + cx * cellSize + cellSize / 2;
                int bz = originZ + cz * cellSize + cellSize / 2;
                if (Math.hypot(bx, bz) > civilizationRadiusBlocks * 1.05) {
                    continue;
                }
                TerrainSample sample = terrain.sample(bx, bz);
                double barrier = terrainBarrier(sample);

                for (InfluenceNode node : nodes) {
                    double dist = node.pos.distanceTo(BlockPos2.of(bx, bz));
                    double reach = 2800 * node.weight;
                    if (dist > reach * 1.4) continue;
                    double influence = node.weight * Math.exp(-dist / (reach * 0.55)) / (1.0 + barrier);
                    if (sample.water() && !sample.river()) {
                        influence *= 0.15;
                    }
                    if (influence > best[i]) {
                        second[i] = best[i];
                        secondOwner[i] = owners[i];
                        best[i] = influence;
                        owners[i] = (short) node.ordinal;
                    } else if (influence > second[i]) {
                        second[i] = influence;
                        secondOwner[i] = (short) node.ordinal;
                    }
                }
            }
        }

        double claimThreshold = 0.08;
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] < 0 || best[i] < claimThreshold) {
                owners[i] = -1;
                zones[i] = (byte) TerritoryZone.UNCLAIMED.ordinal();
                continue;
            }
            int cx = i % widthCells;
            int cz = i / widthCells;
            int bx = originX + cx * cellSize + cellSize / 2;
            int bz = originZ + cz * cellSize + cellSize / 2;
            double radial = Math.hypot(bx, bz) / Math.max(1.0, civilizationRadiusBlocks);

            boolean contested = secondOwner[i] >= 0
                    && second[i] > claimThreshold
                    && second[i] / best[i] > 0.72
                    && secondOwner[i] != owners[i];
            if (contested) {
                owners[i] = -2;
                zones[i] = (byte) TerritoryZone.CONTESTED.ordinal();
                continue;
            }

            boolean nearRival = secondOwner[i] >= 0 && second[i] > claimThreshold * 0.6
                    && second[i] / best[i] > 0.45;
            if (radial > 0.82 || best[i] < claimThreshold * 1.6) {
                zones[i] = (byte) TerritoryZone.FRONTIER.ordinal();
            } else if (nearRival) {
                zones[i] = (byte) TerritoryZone.BORDER.ordinal();
            } else {
                zones[i] = (byte) TerritoryZone.CORE.ordinal();
            }
        }

        Map<KingdomId, Set<KingdomId>> adjSets = new LinkedHashMap<>();
        for (KingdomId id : index) {
            adjSets.put(id, new HashSet<>());
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int cz = 0; cz < heightCells; cz++) {
            for (int cx = 0; cx < widthCells; cx++) {
                int i = cz * widthCells + cx;
                short a = owners[i];
                if (a < 0) continue;
                for (int[] d : dirs) {
                    int nx = cx + d[0];
                    int nz = cz + d[1];
                    if (nx < 0 || nz < 0 || nx >= widthCells || nz >= heightCells) continue;
                    short b = owners[nz * widthCells + nx];
                    if (b < 0 || b == a) continue;
                    KingdomId ka = index.get(a);
                    KingdomId kb = index.get(b);
                    adjSets.get(ka).add(kb);
                    adjSets.get(kb).add(ka);
                }
            }
        }
        Map<KingdomId, List<KingdomId>> adjacency = new LinkedHashMap<>();
        for (Map.Entry<KingdomId, Set<KingdomId>> e : adjSets.entrySet()) {
            List<KingdomId> list = new ArrayList<>(e.getValue());
            list.sort(KingdomId::compareTo);
            adjacency.put(e.getKey(), list);
        }

        return new TerritoryMap(
                cellSize, originX, originZ, widthCells, heightCells,
                index, owners, zones, adjacency
        );
    }

    private static double terrainBarrier(TerrainSample s) {
        double barrier = 0;
        if (s.elevation() > 105 || s.biomeHint().contains("peaks")) barrier += 1.8;
        else if (s.elevation() > 95 || s.slope() > 0.4) barrier += 0.9;
        if (s.river()) barrier += 0.55;
        if (s.water() && !s.river()) barrier += 2.5;
        if (s.coastal()) barrier += 0.25;
        barrier += s.roughness() * 0.6;
        return barrier;
    }

    private record InfluenceNode(int ordinal, BlockPos2 pos, double weight) {}

    public static TerritoryMap empty() {
        return new TerritoryMap(
                DEFAULT_CELL_SIZE, 0, 0, 1, 1,
                List.of(), new short[]{-1}, new byte[]{(byte) TerritoryZone.UNCLAIMED.ordinal()},
                Map.of()
        );
    }
}
