package com.livingmods.simulation.state;

import com.livingmods.common.id.CrimeId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.PlayerLegalStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Per-player legal state for one jurisdiction (settlement or kingdom). */
public final class PlayerLegalRecord {
    private final KingdomId kingdomId;
    private final SettlementId settlementId;
    private PlayerLegalStatus status;
    private double outstandingFine;
    private final List<CrimeId> openCrimeIds;
    private long updatedDay;

    public PlayerLegalRecord(KingdomId kingdomId, SettlementId settlementId) {
        this.kingdomId = kingdomId;
        this.settlementId = settlementId;
        this.status = PlayerLegalStatus.CLEAR;
        this.outstandingFine = 0;
        this.openCrimeIds = new ArrayList<>();
        this.updatedDay = 0;
    }

    public KingdomId kingdomId() { return kingdomId; }
    public SettlementId settlementId() { return settlementId; }
    public PlayerLegalStatus status() { return status; }
    public void setStatus(PlayerLegalStatus status) {
        this.status = status == null ? PlayerLegalStatus.CLEAR : status;
    }
    public double outstandingFine() { return outstandingFine; }
    public void setOutstandingFine(double fine) {
        this.outstandingFine = Math.max(0, fine);
    }
    public List<CrimeId> openCrimeIds() { return openCrimeIds; }
    public long updatedDay() { return updatedDay; }
    public void setUpdatedDay(long day) { this.updatedDay = day; }

    public Optional<SettlementId> settlementOptional() {
        return settlementId == null ? Optional.empty() : Optional.of(settlementId);
    }

    public boolean isWantedOrWorse() {
        return status == PlayerLegalStatus.WANTED
                || status == PlayerLegalStatus.CONVICTED
                || status == PlayerLegalStatus.FINE_OUTSTANDING;
    }
}
