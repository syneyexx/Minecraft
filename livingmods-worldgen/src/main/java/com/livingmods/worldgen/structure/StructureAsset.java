package com.livingmods.worldgen.structure;

import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;

import java.util.List;
import java.util.Optional;

/**
 * Immutable production structure asset metadata.
 * Visual variety lives in archetype/tags; simulation semantics stay on {@link BuildingRole}.
 */
public record StructureAsset(
        String assetId,
        String source,
        String sourceId,
        String sourceUrl,
        String sourceAuthor,
        String sourceTitle,
        String cultureKey,
        BuildingRole canonicalRole,
        String archetype,
        String variant,
        SettlementTier settlementTierMin,
        SettlementTier settlementTierMax,
        WealthClass wealthClass,
        StructureSizeClass sizeClass,
        int width,
        int depth,
        int height,
        int blockCount,
        String entranceFacing,
        int entranceX,
        int entranceY,
        int entranceZ,
        double entranceConfidence,
        List<Integer> allowedRotations,
        boolean mirrorAllowed,
        double weight,
        List<String> tags,
        List<String> biomeTags,
        boolean coastalRequired,
        boolean underground,
        boolean defensive,
        boolean uniquePerSettlement,
        boolean uniquePerKingdom,
        int requiredClearance,
        int terrainTolerance,
        FoundationMode foundationMode,
        String anchor,
        InteriorClass interiorClass,
        boolean hasInterior,
        int occupationCapacityHint,
        int residentialSlotsHint,
        int workSlotsHint,
        String contentPath,
        String sourceHash,
        String contentHash,
        String geometryHash,
        String uniquenessGroup,
        int importRevision,
        ImportStatus status,
        boolean sanitized
) {
    public StructureAsset {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId required");
        }
        cultureKey = cultureKey == null || cultureKey.isBlank() ? "*" : cultureKey;
        archetype = archetype == null || archetype.isBlank() ? canonicalRole.name().toLowerCase() : archetype;
        variant = variant == null ? "v0" : variant;
        source = source == null ? "unknown" : source;
        sourceId = sourceId == null ? "" : sourceId;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        sourceTitle = sourceTitle == null ? "" : sourceTitle;
        settlementTierMin = settlementTierMin == null ? SettlementTier.HAMLET : settlementTierMin;
        settlementTierMax = settlementTierMax == null ? SettlementTier.CAPITAL : settlementTierMax;
        wealthClass = wealthClass == null ? WealthClass.COMMON : wealthClass;
        sizeClass = sizeClass == null ? StructureSizeClass.fromDimensions(width, depth) : sizeClass;
        entranceFacing = entranceFacing == null || entranceFacing.isBlank() ? "south" : entranceFacing;
        allowedRotations = allowedRotations == null || allowedRotations.isEmpty()
                ? List.of(0, 90, 180, 270) : List.copyOf(allowedRotations);
        tags = tags == null ? List.of() : List.copyOf(tags);
        biomeTags = biomeTags == null ? List.of() : List.copyOf(biomeTags);
        foundationMode = foundationMode == null ? FoundationMode.CUT_AND_FILL : foundationMode;
        anchor = anchor == null ? "center" : anchor;
        interiorClass = interiorClass == null
                ? (hasInterior ? InteriorClass.FULL_INTERIOR : InteriorClass.SHELL)
                : interiorClass;
        contentPath = contentPath == null ? "" : contentPath;
        sourceHash = sourceHash == null ? "" : sourceHash;
        contentHash = contentHash == null ? "" : contentHash;
        geometryHash = geometryHash == null ? "" : geometryHash;
        uniquenessGroup = uniquenessGroup == null || uniquenessGroup.isBlank()
                ? (canonicalRole.name().toLowerCase() + ":" + archetype)
                : uniquenessGroup;
        // Mirror is only advertised when the engine supports it — currently disabled.
        mirrorAllowed = false;
        status = status == null ? ImportStatus.AUTHORED : status;
        weight = weight <= 0 ? 1.0 : weight;
        entranceConfidence = Math.max(0, Math.min(1, entranceConfidence));
        width = Math.max(1, width);
        depth = Math.max(1, depth);
        height = Math.max(1, height);
        blockCount = Math.max(0, blockCount);
        requiredClearance = Math.max(0, requiredClearance);
        terrainTolerance = Math.max(0, terrainTolerance);
        occupationCapacityHint = Math.max(0, occupationCapacityHint);
        residentialSlotsHint = Math.max(0, residentialSlotsHint);
        workSlotsHint = Math.max(0, workSlotsHint);
        importRevision = Math.max(0, importRevision);
        entranceX = Math.max(0, Math.min(width - 1, entranceX));
        entranceY = Math.max(0, Math.min(height - 1, entranceY));
        entranceZ = Math.max(0, Math.min(depth - 1, entranceZ));
    }

    public boolean matchesCulture(String key) {
        return cultureKey.equals("*") || cultureKey.equals(key);
    }

    public boolean matchesTier(SettlementTier tier) {
        if (tier == null) return true;
        return tier.ordinal() >= settlementTierMin.ordinal()
                && tier.ordinal() <= settlementTierMax.ordinal();
    }

    public boolean isProductionReady() {
        return status == ImportStatus.IMPORTED_EXACT
                || status == ImportStatus.IMPORTED_SANITIZED
                || status == ImportStatus.AUTHORED
                || status == ImportStatus.VALIDATED;
    }

    public Optional<String> contentResourcePath() {
        return contentPath.isBlank() ? Optional.empty() : Optional.of(contentPath);
    }
}
