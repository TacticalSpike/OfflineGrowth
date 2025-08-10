package com.example.offlinegrowth;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class OfflineGrowthSavedData extends SavedData {
    private static final String NAME = "offlinegrowth_data";

    long lastSeenEpochMs = 0L;
    double stageRemainder = 0.0;

    public OfflineGrowthSavedData() {}

    public static OfflineGrowthSavedData get(ServerLevel level) {
        SavedData.Factory<OfflineGrowthSavedData> factory =
            new SavedData.Factory<>(OfflineGrowthSavedData::new, OfflineGrowthSavedData::load, null);
        return level.getDataStorage().computeIfAbsent(factory, NAME);
    }

    public static OfflineGrowthSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        OfflineGrowthSavedData d = new OfflineGrowthSavedData();
        if (tag.contains("lastSeen")) d.lastSeenEpochMs = tag.getLong("lastSeen");
        if (tag.contains("remainder")) d.stageRemainder = tag.getDouble("remainder");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putLong("lastSeen", lastSeenEpochMs);
        tag.putDouble("remainder", stageRemainder);
        return tag;
    }
}


