package com.livingmods.neoforge.physical;

/**
 * Typed materializer result — bare boolean success is insufficient for multi-chunk work.
 */
public record RealizationResult(
        boolean success,
        boolean blocked,
        boolean unsupported,
        int requiredBlocks,
        int placedBlocks,
        int protectedCollisions,
        int criticalFailures,
        String reason
) {
    public static RealizationResult ok(int required, int placed) {
        return new RealizationResult(true, false, false, required, placed, 0, 0, "");
    }

    public static RealizationResult blocked(String reason, int required, int placed, int protectedHits) {
        return new RealizationResult(false, true, false, required, placed, protectedHits, 0, reason);
    }

    public static RealizationResult failed(String reason, int required, int placed, int critical) {
        return new RealizationResult(false, false, false, required, placed, 0, critical, reason);
    }

    public static RealizationResult unsupported(String reason) {
        return new RealizationResult(false, false, true, 0, 0, 0, 0, reason);
    }

    public static RealizationResult planningComplete() {
        return new RealizationResult(true, false, false, 0, 0, 0, 0, "planning_meta");
    }

    public static RealizationResult projectionRouted() {
        return new RealizationResult(true, false, false, 0, 0, 0, 0, "projection_routed");
    }

    public double completionRatio() {
        if (requiredBlocks <= 0) return success ? 1.0 : 0.0;
        return placedBlocks / (double) requiredBlocks;
    }

    public boolean structurallyComplete() {
        if (unsupported || blocked) return false;
        if (criticalFailures > 0) return false;
        if (requiredBlocks <= 0) return success;
        return completionRatio() >= 0.7 && placedBlocks > 0;
    }
}
