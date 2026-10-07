package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.model.GovernmentType;

import java.util.ArrayList;
import java.util.List;

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
    public void setTaxRate(double taxRate) { this.taxRate = taxRate; }
    public double legitimacy() { return legitimacy; }
    public void setLegitimacy(double legitimacy) { this.legitimacy = legitimacy; }
}
