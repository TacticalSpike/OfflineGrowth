// package com.example.offlinegrowth;

// import net.neoforged.fml.ModLoadingContext;
// import net.neoforged.fml.config.ModConfig;
// import net.neoforged.neoforge.common.ModConfigSpec;

// public final class Config {
//     private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

//     public static final ModConfigSpec.IntValue STAGES_PER_HOUR = B
//             .comment("How many growth stages to add per real-world hour offline.")
//             .defineInRange("stagesPerHour", 2, 0, 32);

//     public static final ModConfigSpec.IntValue MAX_STAGES_PER_CHUNK = B
//             .comment("Safety cap: maximum stages applied in one pass to a single chunk.")
//             .defineInRange("maxStagesPerChunk", 7, 0, 32);

//     public static final ModConfigSpec.IntValue CHUNKS_PER_TICK = B
//             .comment("How many loaded chunks to process each server tick.")
//             .defineInRange("chunksPerTick", 3, 1, 64);

//     public static final ModConfigSpec COMMON = B.build();

//     static void register() {
//         // NeoForge 1.21.1 signature includes the mod id
//         ModLoadingContext.get().registerConfig(OfflineGrowthNeo.MOD_ID, ModConfig.Type.COMMON, COMMON);
//     }
// }
package com.example.offlinegrowth;

public final class Config {
    // 1 stage per 10 minutes offline
    public static int STAGES_PER_HOUR = 6;
    public static int MAX_STAGES_PER_CHUNK = 7;
    public static int CHUNKS_PER_TICK = 3;
    static void register() {}
    private Config() {}
}

