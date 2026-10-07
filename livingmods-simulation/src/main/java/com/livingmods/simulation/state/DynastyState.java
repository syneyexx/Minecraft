package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DynastyState {
    private final DynastyId id;
    private final String name;
    private final CitizenId founderId;
    private CitizenId currentHeadId;
    private final Set<CitizenId> members;
    private double prestige;
    private final Set<KingdomId> kingdomClaims;
    private final Set<SettlementId> settlementClaims;

    public DynastyState(DynastyId id, String name, CitizenId founderId, CitizenId currentHeadId) {
        this.id = id;
        this.name = name;
        this.founderId = founderId;
        this.currentHeadId = currentHeadId;
        this.members = new LinkedHashSet<>();
        if (founderId != null) members.add(founderId);
        if (currentHeadId != null) members.add(currentHeadId);
        this.prestige = 50.0;
        this.kingdomClaims = new LinkedHashSet<>();
        this.settlementClaims = new LinkedHashSet<>();
    }

    public DynastyId id() { return id; }
    public String name() { return name; }
    public CitizenId founderId() { return founderId; }
    public CitizenId currentHeadId() { return currentHeadId; }
    public void setCurrentHeadId(CitizenId currentHeadId) { this.currentHeadId = currentHeadId; }
    public Set<CitizenId> members() { return members; }
    public double prestige() { return prestige; }
    public void setPrestige(double prestige) { this.prestige = Math.max(0, Math.min(100, prestige)); }
    public Set<KingdomId> kingdomClaims() { return kingdomClaims; }
    public Set<SettlementId> settlementClaims() { return settlementClaims; }

    public List<CitizenId> memberList() {
        return new ArrayList<>(members);
    }
}
