package com.livingmods.worldgen.planner;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.DistrictType;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.architecture.ArchitectureGrammar;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedDistrict;
import com.livingmods.worldgen.plan.PlannedLot;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class UrbanPlanner {
    private final CultureRegistry cultures;
    private final TerrainAnalyzer terrain;
    private final ArchitectureGrammar grammar;

    public UrbanPlanner(CultureRegistry cultures, TerrainAnalyzer terrain) {
        this.cultures = cultures;
        this.terrain = terrain;
        this.grammar = new ArchitectureGrammar();
    }

    public List<PlannedSettlement> planUrbanLayouts(long seed, List<PlannedSettlement> settlements) {
        List<PlannedSettlement> out = new ArrayList<>();
        long base = Hashing.mix(seed, 0x555242414EL);

        for (int i = 0; i < settlements.size(); i++) {
            PlannedSettlement s = settlements.get(i);
            if (!s.tier().isUrban() && s.tier() != SettlementTier.CAPITAL) {
                out.add(s);
                continue;
            }
            CultureDefinition culture = cultures.get(s.cultureKey())
                    .orElseThrow(() -> new IllegalStateException("culture " + s.cultureKey()));
            DeterministicRandom random = new DeterministicRandom(Hashing.mix(base, i));
            out.add(layoutSettlement(seed, i, s, culture, random));
        }
        return out;
    }

    private PlannedSettlement layoutSettlement(long seed, int index, PlannedSettlement s,
                                               CultureDefinition culture, DeterministicRandom random) {
        int radius = s.tier().footprintRadius();
        BoundingBox2 bounds = BoundingBox2.around(s.center(), radius);
        List<BlockPos2> streets = majorStreets(s.center(), radius, culture, random);
        List<PlannedDistrict> districts = planDistricts(seed, index, s, bounds, culture, random);
        List<PlannedLot> lots = new ArrayList<>();
        List<PlannedBuilding> buildings = new ArrayList<>();
        int lotOrdinal = 0;
        int buildingOrdinal = 0;

        for (PlannedDistrict district : districts) {
            List<PlannedLot> districtLots = subdivideLots(seed, index, s, district, culture, random, lotOrdinal);
            lotOrdinal += districtLots.size();
            for (PlannedLot lot : districtLots) {
                lots.add(lot);
                if (!lot.occupied()) continue;
                BuildingRole role = pickRole(district.type(), lot, random);
                WealthClass wealth = wealthFor(district.type(), s.tier());
                int foundationY = (int) terrain.sample(lot.bounds().center()).elevation();
                LotId lotId = lot.id();
                ArchitectureGrammar.Blueprint bp = grammar.generate(
                        seed, index * 10_000L + buildingOrdinal, culture, role, wealth,
                        lotId, lot.bounds(), lot.entranceDirection(), foundationY
                );
                buildings.add(bp.building());
                buildingOrdinal++;
            }
        }

        List<BlockPos2> secondary = secondaryStreets(streets, bounds, culture, random);
        streets = new ArrayList<>(streets);
        streets.addAll(secondary);

        List<BlockPos2> wallPath = List.of();
        List<BlockPos2> gates = List.of();
        if (s.walls()) {
            wallPath = wallRing(s.center(), radius);
            gates = gatePositions(wallPath, random);
        }

        return new PlannedSettlement(
                s.id(), s.name(), s.tier(), s.role(), s.center(), bounds,
                s.ownerKingdom(), s.cultureId(), s.cultureKey(), s.capital(), s.walls(),
                s.underground(), s.plannedPopulation(),
                districts, lots, buildings, streets, wallPath, gates
        );
    }

    private List<PlannedDistrict> planDistricts(long seed, int index, PlannedSettlement s,
                                              BoundingBox2 bounds, CultureDefinition culture,
                                              DeterministicRandom random) {
        List<PlannedDistrict> districts = new ArrayList<>();
        int cx = s.center().x();
        int cz = s.center().z();
        int r = s.tier().footprintRadius();
        int d = 0;

        if (s.capital() || s.tier() == SettlementTier.CAPITAL) {
            districts.add(district(seed, index, d++, s, DistrictType.CASTLE,
                    BoundingBox2.of(cx - r / 4, cz - r / 4, cx + r / 4, cz + r / 4)));
            districts.add(district(seed, index, d++, s, DistrictType.GOVERNMENT,
                    BoundingBox2.of(cx - r / 3, cz - r / 6, cx + r / 6, cz + r / 6)));
            districts.add(district(seed, index, d++, s, DistrictType.MARKET,
                    BoundingBox2.of(cx - r / 6, cz - r / 6, cx + r / 6, cz + r / 6)));
            districts.add(district(seed, index, d++, s, DistrictType.WEALTHY_RESIDENTIAL,
                    BoundingBox2.of(cx + r / 8, cz - r / 3, cx + r / 2, cz + r / 8)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMON_RESIDENTIAL,
                    BoundingBox2.of(cx - r / 2, cz + r / 8, cx + r / 8, cz + r / 2)));
            districts.add(district(seed, index, d++, s, DistrictType.MILITARY,
                    BoundingBox2.of(cx + r / 4, cz + r / 4, cx + r / 2, cz + r / 2)));
            districts.add(district(seed, index, d++, s, DistrictType.RELIGIOUS,
                    BoundingBox2.of(cx - r / 2, cz - r / 2, cx - r / 6, cz - r / 6)));
        } else if (s.tier() == SettlementTier.CITY) {
            districts.add(district(seed, index, d++, s, DistrictType.HISTORIC_CENTER,
                    BoundingBox2.of(cx - r / 3, cz - r / 3, cx + r / 3, cz + r / 3)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMERCIAL,
                    BoundingBox2.of(cx - r / 4, cz - r / 8, cx + r / 4, cz + r / 4)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMON_RESIDENTIAL,
                    BoundingBox2.of(cx - r / 2, cz - r / 2, cx + r / 2, cz + r / 2)));
        } else {
            districts.add(district(seed, index, d++, s, DistrictType.PLAZA,
                    BoundingBox2.of(cx - r / 5, cz - r / 5, cx + r / 5, cz + r / 5)));
            districts.add(district(seed, index, d++, s, DistrictType.CRAFTSMEN,
                    BoundingBox2.of(cx - r / 2, cz - r / 2, cx + r / 2, cz + r / 2)));
        }

        return districts;
    }

    private PlannedDistrict district(long seed, int settlementIndex, int ordinal,
                                     PlannedSettlement s, DistrictType type, BoundingBox2 box) {
        DistrictId id = DistrictId.deterministic(seed, settlementIndex * 100L + ordinal);
        return new PlannedDistrict(id, s.id(), type, clamp(box, s.bounds()));
    }

    private BoundingBox2 clamp(BoundingBox2 inner, BoundingBox2 outer) {
        return BoundingBox2.of(
                Math.max(inner.minX(), outer.minX()),
                Math.max(inner.minZ(), outer.minZ()),
                Math.min(inner.maxX(), outer.maxX()),
                Math.min(inner.maxZ(), outer.maxZ())
        );
    }

    private List<PlannedLot> subdivideLots(long seed, int settlementIndex, PlannedSettlement s,
                                           PlannedDistrict district, CultureDefinition culture,
                                           DeterministicRandom random, int lotBase) {
        List<PlannedLot> lots = new ArrayList<>();
        BoundingBox2 b = district.bounds();
        int lotW = culture.architecture().layoutStyle() == CultureDefinition.LayoutStyle.GRID ? 12 : 14;
        int lotD = 10;
        int row = 0;
        for (int z = b.minZ(); z + lotD <= b.maxZ(); z += lotD + 2) {
            for (int x = b.minX(); x + lotW <= b.maxX(); x += lotW + 2) {
                BoundingBox2 lotBox = BoundingBox2.of(x, z, x + lotW - 1, z + lotD - 1);
                LotId lotId = LotId.deterministic(seed, settlementIndex * 1000L + lotBase + lots.size());
                double slope = terrain.averageSlope(lotBox, 4);
                EnumSet<BuildingRole> roles = rolesFor(district.type());
                boolean occupied = random.chance(0.82);
                int entrance = row % 2 == 0 ? 0 : 2;
                lots.add(new PlannedLot(
                        lotId, s.id(), district.id(), district.type(), lotBox,
                        entrance, slope, roles, occupied
                ));
            }
            row++;
        }
        return lots;
    }

    private List<BlockPos2> majorStreets(BlockPos2 center, int radius, CultureDefinition culture,
                                         DeterministicRandom random) {
        List<BlockPos2> streets = new ArrayList<>();
        CultureDefinition.LayoutStyle layout = culture.architecture().layoutStyle();
        int cx = center.x();
        int cz = center.z();

        if (layout == CultureDefinition.LayoutStyle.GRID || layout == CultureDefinition.LayoutStyle.TERRACED) {
            for (int x = cx - radius; x <= cx + radius; x += 8) {
                streets.add(BlockPos2.of(x, cz));
            }
            for (int z = cz - radius; z <= cz + radius; z += 8) {
                streets.add(BlockPos2.of(cx, z));
            }
        } else {
            int rays = 8;
            for (int i = 0; i < rays; i++) {
                double angle = (Math.PI * 2 * i) / rays + random.nextDouble() * 0.1;
                for (int d = 8; d <= radius; d += 8) {
                    int x = cx + (int) (Math.cos(angle) * d);
                    int z = cz + (int) (Math.sin(angle) * d);
                    streets.add(BlockPos2.of(x, z));
                }
            }
        }
        return streets;
    }

    private List<BlockPos2> secondaryStreets(List<BlockPos2> major, BoundingBox2 bounds,
                                             CultureDefinition culture, DeterministicRandom random) {
        if (!culture.architecture().preferStraightStreets()) {
            return List.of();
        }
        List<BlockPos2> secondary = new ArrayList<>();
        int step = 16;
        for (int z = bounds.minZ() + step; z < bounds.maxZ(); z += step) {
            for (int x = bounds.minX() + step; x < bounds.maxX(); x += step) {
                if (random.chance(0.35)) {
                    secondary.add(BlockPos2.of(x, z));
                }
            }
        }
        return secondary;
    }

    private List<BlockPos2> wallRing(BlockPos2 center, int radius) {
        List<BlockPos2> ring = new ArrayList<>();
        int cx = center.x();
        int cz = center.z();
        int steps = 48;
        for (int i = 0; i < steps; i++) {
            double angle = (Math.PI * 2 * i) / steps;
            int x = cx + (int) (Math.cos(angle) * radius);
            int z = cz + (int) (Math.sin(angle) * radius);
            ring.add(BlockPos2.of(x, z));
        }
        return ring;
    }

    private List<BlockPos2> gatePositions(List<BlockPos2> wallPath, DeterministicRandom random) {
        if (wallPath.size() < 4) return List.of();
        List<BlockPos2> gates = new ArrayList<>();
        int count = 2 + random.nextInt(2);
        int step = wallPath.size() / count;
        for (int i = 0; i < count; i++) {
            gates.add(wallPath.get(i * step));
        }
        return gates;
    }

    private EnumSet<BuildingRole> rolesFor(DistrictType type) {
        return switch (type) {
            case CASTLE -> EnumSet.of(BuildingRole.CASTLE_KEEP, BuildingRole.GATEHOUSE, BuildingRole.TOWER);
            case GOVERNMENT -> EnumSet.of(BuildingRole.PALACE, BuildingRole.MONUMENT);
            case MARKET, COMMERCIAL -> EnumSet.of(BuildingRole.MARKET_HALL, BuildingRole.SHOP, BuildingRole.MARKET_STALL);
            case WEALTHY_RESIDENTIAL -> EnumSet.of(BuildingRole.MANOR, BuildingRole.TOWNHOUSE);
            case COMMON_RESIDENTIAL -> EnumSet.of(BuildingRole.HOUSE, BuildingRole.TOWNHOUSE);
            case MILITARY -> EnumSet.of(BuildingRole.BARRACKS, BuildingRole.GUARDHOUSE, BuildingRole.TOWER);
            case RELIGIOUS -> EnumSet.of(BuildingRole.TEMPLE);
            case CRAFTSMEN -> EnumSet.of(BuildingRole.WORKSHOP, BuildingRole.SMITHY);
            case DOCKS -> EnumSet.of(BuildingRole.DOCK, BuildingRole.WAREHOUSE);
            default -> EnumSet.of(BuildingRole.HOUSE, BuildingRole.SHOP);
        };
    }

    private BuildingRole pickRole(DistrictType type, PlannedLot lot, DeterministicRandom random) {
        List<BuildingRole> roles = new ArrayList<>(lot.allowedRoles());
        if (roles.isEmpty()) return BuildingRole.HOUSE;
        return random.pick(roles);
    }

    private WealthClass wealthFor(DistrictType type, SettlementTier tier) {
        return switch (type) {
            case CASTLE, GOVERNMENT -> WealthClass.ROYAL;
            case WEALTHY_RESIDENTIAL -> WealthClass.WEALTHY;
            case MARKET, COMMERCIAL -> WealthClass.COMFORTABLE;
            case MILITARY -> WealthClass.COMMON;
            default -> tier.isCapitalClass() ? WealthClass.COMFORTABLE : WealthClass.COMMON;
        };
    }
}
