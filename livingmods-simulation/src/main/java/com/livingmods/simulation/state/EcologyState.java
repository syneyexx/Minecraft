package com.livingmods.simulation.state;

import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.SpeciesId;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Scalable cohort counts per region and species. */
public final class EcologyState {
    private final Map<RegionCoord, Map<SpeciesId, SpeciesCohort>> cohorts = new HashMap<>();

    public Map<RegionCoord, Map<SpeciesId, SpeciesCohort>> cohorts() { return cohorts; }

    public SpeciesCohort cohort(RegionCoord region, SpeciesId species) {
        return cohorts
                .computeIfAbsent(region, r -> new HashMap<>())
                .computeIfAbsent(species, s -> new SpeciesCohort(species, 100, 0.0));
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
