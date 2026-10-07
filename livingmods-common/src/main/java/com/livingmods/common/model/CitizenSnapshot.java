package com.livingmods.common.model;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.FactionId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.time.SimulationTime;

public record CitizenSnapshot(
        CitizenId id,
        String givenName,
        String familyName,
        boolean female,
        SimulationTime birthDate,
        CultureId cultureId,
        String religionKey,
        FactionId factionId,
        SettlementId settlementId,
        HouseholdId householdId,
        Profession profession,
        double professionalSkill,
        double health,
        double wealth,
        Needs needs,
        Personality personality,
        AbstractActivity activity,
        WealthClass wealthClass,
        int appearanceSeed
) {
    public String displayName() {
        return givenName + " " + familyName;
    }

    public int ageYears(SimulationTime now) {
        long days = now.dayIndex() - birthDate.dayIndex();
        return (int) Math.max(0, days / SimulationTime.DAYS_PER_YEAR);
    }
}
