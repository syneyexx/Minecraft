package com.livingmods.simulation.tick;

import com.livingmods.common.geo.RegionCoord;

import java.util.ArrayList;
import java.util.List;

/** Phase-1 read-only output for one region, committed in sorted region order. */
public final class RegionalWork {
    private final RegionCoord region;
    private final List<Runnable> commits = new ArrayList<>();

    public RegionalWork(RegionCoord region) {
        this.region = region;
    }

    public RegionCoord region() { return region; }

    public void enqueueCommit(Runnable commit) {
        commits.add(commit);
    }

    public void applyCommits() {
        for (Runnable r : commits) {
            r.run();
        }
    }
}
