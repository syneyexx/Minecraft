package com.livingmods.simulation.physical;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.StructureIntegrityStatus;

/**
 * Dynamic structure geometry owned by DynamicPhysicalState (not WorldPlan).
 */
public final class DynamicStructureRecord {
    private final StructureId structureId;
    private final SettlementId settlementId;
    private final BuildingRole role;
    private final BoundingBox2 footprint;
    private final PhysicalIntentId sourceIntentId;
    private StructureIntegrityStatus status;
    private double integrity;
    private int residentialSlots;
    private int workSlots;
    private int physicalRevision;
    private String cultureKey;
    private int foundationY;

    public DynamicStructureRecord(
            StructureId structureId,
            SettlementId settlementId,
            BuildingRole role,
            BoundingBox2 footprint,
            PhysicalIntentId sourceIntentId,
            StructureIntegrityStatus status,
            double integrity,
            int residentialSlots,
            int workSlots,
            int physicalRevision,
            String cultureKey,
            int foundationY
    ) {
        this.structureId = structureId;
        this.settlementId = settlementId;
        this.role = role;
        this.footprint = footprint;
        this.sourceIntentId = sourceIntentId;
        this.status = status == null ? StructureIntegrityStatus.ACTIVE : status;
        this.integrity = Math.max(0, Math.min(1, integrity));
        this.residentialSlots = Math.max(0, residentialSlots);
        this.workSlots = Math.max(0, workSlots);
        this.physicalRevision = physicalRevision;
        this.cultureKey = cultureKey == null ? "" : cultureKey;
        this.foundationY = foundationY;
    }

    public StructureId structureId() { return structureId; }
    public SettlementId settlementId() { return settlementId; }
    public BuildingRole role() { return role; }
    public BoundingBox2 footprint() { return footprint; }
    public PhysicalIntentId sourceIntentId() { return sourceIntentId; }
    public StructureIntegrityStatus status() { return status; }
    public void setStatus(StructureIntegrityStatus status) { this.status = status; }
    public double integrity() { return integrity; }
    public void setIntegrity(double integrity) {
        this.integrity = Math.max(0, Math.min(1, integrity));
        if (this.integrity <= 0.05) {
            this.status = StructureIntegrityStatus.DESTROYED;
        } else if (this.integrity < 0.7 && this.status == StructureIntegrityStatus.ACTIVE) {
            this.status = StructureIntegrityStatus.DAMAGED;
        }
    }
    public int residentialSlots() { return residentialSlots; }
    public int workSlots() { return workSlots; }
    public int physicalRevision() { return physicalRevision; }
    public void bumpPhysicalRevision() { physicalRevision++; }
    public String cultureKey() { return cultureKey; }
    public int foundationY() { return foundationY; }
    public void setFoundationY(int foundationY) { this.foundationY = foundationY; }

    public boolean contributesHousing() {
        return status.contributesCapacity() && residentialSlots > 0;
    }
}
