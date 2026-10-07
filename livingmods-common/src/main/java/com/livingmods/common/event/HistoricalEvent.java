package com.livingmods.common.event;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.time.SimulationTime;

import java.util.Map;
import java.util.Optional;

public record HistoricalEvent(
        HistoricalEventId id,
        CivilizationEventType type,
        SimulationTime when,
        String title,
        String summary,
        Optional<BlockPos2> location,
        Map<String, String> tags
) {}
