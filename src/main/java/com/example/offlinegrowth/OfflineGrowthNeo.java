package com.example.offlinegrowth;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.neoforged.neoforge.common.NeoForge;



@Mod(OfflineGrowthNeo.MOD_ID)
public class OfflineGrowthNeo {
    public static final String MOD_ID = "offlinegrowth";

    private final Map<ServerLevel, Queue<ChunkPos>> pendingByLevel = new WeakHashMap<>();
    private volatile int stagesToApplyThisSession = 0;


    public OfflineGrowthNeo(IEventBus modBus) {
        modBus.addListener(this::setup);
        Config.register();
        NeoForge.EVENT_BUS.register(this);   // <-- REQUIRED so RegisterCommandsEvent fires
    }

    private void setup(final FMLCommonSetupEvent e) { }

    /* ================= Commands ================= */

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(
            Commands.literal("offlinegrowth")
                .then(Commands.literal("simulate")
                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                            simulateOfflineMinutes(ctx.getSource(), minutes);
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("Simulated " + minutes + " minutes offline."), true);
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                )
        );
    }

    private void simulateOfflineMinutes(net.minecraft.commands.CommandSourceStack src, int minutes) {
        MinecraftServer server = src.getServer();
        ServerLevel level = src.getLevel();
        if (level == null) return;

        OfflineGrowthSavedData data = OfflineGrowthSavedData.get(level);

        double hours = minutes / 60.0;
        double totalStages = hours * Config.STAGES_PER_HOUR + data.stageRemainder;
        int toApply = (int) Math.floor(totalStages);
        int applied = Math.min(toApply, Config.MAX_STAGES_PER_CHUNK);

        stagesToApplyThisSession = applied;
        data.stageRemainder = totalStages - applied;
        data.lastSeenEpochMs = System.currentTimeMillis();
        data.setDirty();

        // Immediately queue chunks around the player
        var player = src.getPlayer();
        if (player != null) {
            ChunkPos center = player.chunkPosition();
            Queue<ChunkPos> q = pendingByLevel.computeIfAbsent(level, k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
            int radius = 2; // queue a 5x5 area around player
            for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
                for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                    q.add(new ChunkPos(cx, cz));
                }
            }
        }
    }


    /* ============== Lifecycle ============== */

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) {
        MinecraftServer server = e.getServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        OfflineGrowthSavedData data = OfflineGrowthSavedData.get(overworld);
        long now = System.currentTimeMillis();
        long last = data.lastSeenEpochMs;

        if (last <= 0L) {
            data.lastSeenEpochMs = now;
            data.setDirty();
            stagesToApplyThisSession = 0;
            return;
        }

        long millis = Math.max(0L, now - last);
        double hours = millis / 3_600_000.0;

        double totalStages = hours * Config.STAGES_PER_HOUR + data.stageRemainder;
        int toApply = (int) Math.floor(totalStages);
        int applied = Math.min(toApply, Config.MAX_STAGES_PER_CHUNK);

        stagesToApplyThisSession = applied;
        data.stageRemainder = totalStages - applied;
        data.lastSeenEpochMs = now;
        data.setDirty();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) {
        ServerLevel overworld = e.getServer().getLevel(Level.OVERWORLD);
        if (overworld != null) {
            OfflineGrowthSavedData data = OfflineGrowthSavedData.get(overworld);
            data.lastSeenEpochMs = System.currentTimeMillis();
            data.setDirty();
        }
    }

    /* ============== Work queue ============== */

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        pendingByLevel.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(e.getChunk().getPos());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post e) {
        if (stagesToApplyThisSession <= 0) return;
        MinecraftServer server = e.getServer();

        for (ServerLevel level : server.getAllLevels()) {
            Queue<ChunkPos> q = pendingByLevel.get(level);
            if (q == null || q.isEmpty()) continue;

            int budget = Math.max(1, Config.CHUNKS_PER_TICK);
            while (budget-- > 0) {
                ChunkPos pos = q.poll();
                if (pos == null) break;

                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) continue;

                applyGrowthToChunk(level, chunk, stagesToApplyThisSession);
            }
        }
    }

    /* ============== Growth ============== */

    private void applyGrowthToChunk(ServerLevel level, LevelChunk chunk, int stages) {
        if (stages <= 0) return;

        final int minY = level.getMinBuildHeight();
        final int maxY = level.getMaxBuildHeight() - 1;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(minX + dx, y, minZ + dz);
                    BlockState state = level.getBlockState(cursor);

                    IntegerProperty ageProp = getAgeProperty(state);
                    if (ageProp == null) continue;

                    int cur = state.getValue(ageProp);
                    int max = Collections.max(ageProp.getPossibleValues());
                    int next = Math.min(max, cur + stages);
                    if (next != cur) {
                        level.setBlock(cursor, state.setValue(ageProp, next), 2);
                    }
                }
            }
        }
    }

    private static IntegerProperty getAgeProperty(BlockState state) {
        for (var prop : state.getProperties()) {
            if (prop instanceof IntegerProperty ip && "age".equals(ip.getName())) {
                return ip;
            }
        }
        return null;
    }
}
