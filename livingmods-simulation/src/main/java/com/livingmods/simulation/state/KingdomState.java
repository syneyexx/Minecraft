package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.GovernmentType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class KingdomState {
    private final KingdomId id;
    private final String name;
    private final CultureId cultureId;
    private final GovernmentType governmentType;
    private final SettlementId capitalId;
    private CitizenId rulerId;
    private final List<SettlementId> settlementIds;
    private double treasury;
    private double taxRate;
    private double legitimacy;
    private DynastyId dynastyId;
    private String religionKey;
    private final Set<KingdomId> adjacentKingdoms;
    private double warExhaustion;
    private double publicOpinion;
    private double propaganda;
    private double unrest;

    public KingdomState(
            KingdomId id,
            String name,
            CultureId cultureId,
            GovernmentType governmentType,
            SettlementId capitalId,
            CitizenId rulerId,
            List<SettlementId> settlementIds,
            double treasury,
            double taxRate,
            double legitimacy
    ) {
        this.id = id;
        this.name = name;
        this.cultureId = cultureId;
        this.governmentType = governmentType;
        this.capitalId = capitalId;
        this.rulerId = rulerId;
        this.settlementIds = new ArrayList<>(settlementIds);
        this.treasury = treasury;
        this.taxRate = taxRate;
        this.legitimacy = legitimacy;
        this.dynastyId = null;
        this.religionKey = "none";
        this.adjacentKingdoms = new LinkedHashSet<>();
        this.warExhaustion = 0.0;
        this.publicOpinion = 50.0;
        this.propaganda = 0.0;
        this.unrest = 0.0;
    }

    public KingdomId id() { return id; }
    public String name() { return name; }
    public CultureId cultureId() { return cultureId; }
    public GovernmentType governmentType() { return governmentType; }
    public SettlementId capitalId() { return capitalId; }
    public CitizenId rulerId() { return rulerId; }
    public void setRulerId(CitizenId rulerId) { this.rulerId = rulerId; }
    public List<SettlementId> settlementIds() { return settlementIds; }
    public double treasury() { return treasury; }
    public void setTreasury(double treasury) { this.treasury = treasury; }
    public double taxRate() { return taxRate; }
    public void setTaxRate(double taxRate) { this.taxRate = Math.max(0, Math.min(0.5, taxRate)); }
    public double legitimacy() { return legitimacy; }
    public void setLegitimacy(double legitimacy) { this.legitimacy = Math.max(0, Math.min(100, legitimacy)); }
    public DynastyId dynastyId() { return dynastyId; }
    public void setDynastyId(DynastyId dynastyId) { this.dynastyId = dynastyId; }
    public String religionKey() { return religionKey; }
    public void setReligionKey(String religionKey) {
        this.religionKey = religionKey == null ? "none" : religionKey;
    }
    public Set<KingdomId> adjacentKingdoms() { return adjacentKingdoms; }
    public double warExhaustion() { return warExhaustion; }
    public void setWarExhaustion(double warExhaustion) {
        this.warExhaustion = Math.max(0, Math.min(1, warExhaustion));
    }
    public double publicOpinion() { return publicOpinion; }
    public void setPublicOpinion(double publicOpinion) {
        this.publicOpinion = Math.max(0, Math.min(100, publicOpinion));
    }
    public double propaganda() { return propaganda; }
    public void setPropaganda(double propaganda) {
        this.propaganda = Math.max(0, Math.min(1, propaganda));
    }
    public double unrest() { return unrest; }
    public void setUnrest(double unrest) {
        this.unrest = Math.max(0, Math.min(1, unrest));
    }
}
