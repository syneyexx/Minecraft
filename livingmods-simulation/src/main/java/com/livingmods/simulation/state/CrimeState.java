package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CrimeId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.CrimeStatus;
import com.livingmods.common.model.CrimeType;
import com.livingmods.common.model.CrimeVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CrimeState {
    private final CrimeId id;
    private final CrimeType type;
    private final CitizenId suspectId;
    private final CitizenId victimId;
    private final SettlementId jurisdiction;
    private final long committedDay;
    private final List<CitizenId> witnesses;
    private double evidence;
    private CrimeStatus status;
    private CrimeVerdict verdict;
    private int sentenceDays;
    private long sentenceEndsDay;
    /** When set, the offender is a Minecraft player rather than a canonical citizen. */
    private PlayerId playerOffender;

    public CrimeState(
            CrimeId id,
            CrimeType type,
            CitizenId suspectId,
            CitizenId victimId,
            SettlementId jurisdiction,
            long committedDay
    ) {
        this.id = id;
        this.type = type;
        this.suspectId = suspectId;
        this.victimId = victimId;
        this.jurisdiction = jurisdiction;
        this.committedDay = committedDay;
        this.witnesses = new ArrayList<>();
        this.evidence = 0.3;
        this.status = CrimeStatus.REPORTED;
        this.verdict = CrimeVerdict.NONE;
        this.sentenceDays = 0;
        this.sentenceEndsDay = 0;
        this.playerOffender = null;
    }

    public CrimeId id() { return id; }
    public CrimeType type() { return type; }
    public CitizenId suspectId() { return suspectId; }
    public CitizenId victimId() { return victimId; }
    public SettlementId jurisdiction() { return jurisdiction; }
    public long committedDay() { return committedDay; }
    public List<CitizenId> witnesses() { return witnesses; }
    public double evidence() { return evidence; }
    public void setEvidence(double evidence) { this.evidence = Math.max(0, Math.min(1, evidence)); }
    public CrimeStatus status() { return status; }
    public void setStatus(CrimeStatus status) { this.status = status; }
    public CrimeVerdict verdict() { return verdict; }
    public void setVerdict(CrimeVerdict verdict) { this.verdict = verdict; }
    public int sentenceDays() { return sentenceDays; }
    public void setSentenceDays(int sentenceDays) { this.sentenceDays = Math.max(0, sentenceDays); }
    public long sentenceEndsDay() { return sentenceEndsDay; }
    public void setSentenceEndsDay(long sentenceEndsDay) { this.sentenceEndsDay = sentenceEndsDay; }
    public Optional<PlayerId> playerOffender() { return Optional.ofNullable(playerOffender); }
    public void setPlayerOffender(PlayerId playerOffender) { this.playerOffender = playerOffender; }
    public boolean isPlayerCrime() { return playerOffender != null; }
}
