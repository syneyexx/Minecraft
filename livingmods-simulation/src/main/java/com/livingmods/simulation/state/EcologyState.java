package com.livingmods.simulation.state;

import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.SpeciesId;
import com.livingmods.common.model.SpeciesDefinition;

import java.util.HashMap;
import java.util.Map;

/** Scalable cohort counts per region and species. */
public final class EcologyState {
    private final Map<RegionCoord, Map<SpeciesId, SpeciesCohort>> cohorts = new HashMap<>();
    private final Map<RegionCoord, Double> huntingPressure = new HashMap<>();
    private final Map<RegionCoord, Double> agriculturePressure = new HashMap<>();

    public Map<RegionCoord, Map<SpeciesId, SpeciesCohort>> cohorts() { return cohorts; }

    public SpeciesCohort cohort(RegionCoord region, SpeciesId species) {
        return cohorts
                .computeIfAbsent(region, r -> new HashMap<>())
                .computeIfAbsent(species, s -> {
                    SpeciesDefinition def = find(s);
                    double seedPop = def == null ? 50.0 : def.carryingCapacity() * 0.4;
                    return new SpeciesCohort(species, seedPop, seedPop * biomassFactor(def));
                });
    }

    public double huntingPressure(RegionCoord region) {
        return huntingPressure.getOrDefault(region, 0.0);
    }

    public void setHuntingPressure(RegionCoord region, double value) {
        huntingPressure.put(region, Math.max(0, value));
    }

    public double agriculturePressure(RegionCoord region) {
        return agriculturePressure.getOrDefault(region, 0.0);
    }

    public void setAgriculturePressure(RegionCoord region, double value) {
        agriculturePressure.put(region, Math.max(0, value));
    }

    public Map<RegionCoord, Double> huntingPressureMap() { return huntingPressure; }
    public Map<RegionCoord, Double> agriculturePressureMap() { return agriculturePressure; }

    private static SpeciesDefinition find(SpeciesId id) {
        for (SpeciesDefinition def : SpeciesDefinition.catalog()) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    private static double biomassFactor(SpeciesDefinition def) {
        if (def == null) {
            return 1.0;
        }
        return switch (def.archetype()) {
            case PREDATOR -> 2.0;
            case LIVESTOCK -> 2.5;
            case HERBIVORE -> 1.5;
            case AQUATIC -> 0.8;
            case BIRD -> 0.6;
            case SMALL_GAME -> 0.4;
        };
    }

    public static final class SpeciesCohort {
        private final SpeciesId species;
        private double population;
        private double biomass;

        public SpeciesCohort(SpeciesId species, double population, double biomass) {
            this.species = species;
            this.population = population;
            this.biomass = biomass;
        }

        public SpeciesId species() { return species; }
        public double population() { return population; }
        public void setPopulation(double population) { this.population = Math.max(0, population); }
        public double biomass() { return biomass; }
        public void setBiomass(double biomass) { this.biomass = Math.max(0, biomass); }
    }
}
