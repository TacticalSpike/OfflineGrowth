package com.example.offlinegrowth;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class OfflineGrowthMod implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("OfflineGrowth");

    // Simple testing config (JSON hookup later)
    private static int applyDelayTicks = -1; // -1 = idle
    private static final int TICKS_PER_STAGE = 12000; // ~10s per stage (testing)
    private static final boolean REQUIRE_LIGHT = false; // reserved for later

    private static final AtomicInteger STAGES_TO_ADD = new AtomicInteger(0);

    private static Path getConfigPath() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        return dir.resolve("offline_growth_last_seen.txt");
    }

    @Override
    public void onInitialize() {
        LOG.warn("[OfflineGrowth] onInitialize() fired");
        LOG.warn("[OfflineGrowth] loaded from: {}", OfflineGrowthMod.class.getProtectionDomain().getCodeSource().getLocation());

        // Compute stages once at server start (no chunk work here).
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            int stages = computeStagesToAdd(server);
            STAGES_TO_ADD.set(stages);
            LOG.warn("[OfflineGrowth] ticksPerStage={} ({} ms per stage)", TICKS_PER_STAGE, TICKS_PER_STAGE * 50L);
            LOG.warn("[OfflineGrowth] (STARTING) computed {} stage(s).", stages);
        });

        // After a player joins, wait ~2s so vanilla spawn chunks are ready, then apply locally.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            applyDelayTicks = Math.max(applyDelayTicks, 40); // run once after countdown
        });

        // Countdown + run once
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            if (applyDelayTicks >= 0 && --applyDelayTicks == 0) {
                applyGrowthForAllPlayers(srv);
                applyDelayTicks = -1; // idle again
            }
        });

        // Record the "last seen" time on clean shutdown so next launch can compute offline time.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> writeTimestamp());
    }

    private static void writeTimestamp() {
        try {
            Files.createDirectories(getConfigPath().getParent());
            Files.writeString(getConfigPath(), Long.toString(Instant.now().toEpochMilli()));
        } catch (IOException e) {
            LOG.warn("[OfflineGrowth] Failed writing timestamp '{}': {}", getConfigPath(), e.toString());
        }
    }

    private static long readTimestamp() {
        try {
            Path f = getConfigPath();
            if (Files.exists(f)) {
                String s = Files.readString(f).trim();
                return Long.parseLong(s);
            }
        } catch (Exception e) {
            LOG.warn("[OfflineGrowth] Failed reading timestamp '{}': {}", getConfigPath(), e.toString());
        }
        return 0L;
    }

    private static int computeStagesToAdd(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long last = readTimestamp();
        long offlineMs = (last == 0L) ? 0L : Math.max(0L, now - last);
        long msPerStage = TICKS_PER_STAGE * 50L;
        int stages = (msPerStage <= 0) ? 0 : (int) (offlineMs / msPerStage);
        return Math.max(0, stages);
    }

    /** Run once after join: apply stages around each online player to already-loaded chunks only. */
    private static void applyGrowthForAllPlayers(MinecraftServer srv) {
        int stages = STAGES_TO_ADD.getAndSet(0); // run once
        if (stages <= 0) return;

        int totalChanged = 0;
        for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
            totalChanged += applyAroundPlayer(p, stages);
        }
        LOG.info("[OfflineGrowth] Applied +{} stage(s) total; changed {} block(s) around online players.", stages, totalChanged);

        // Optional: update timestamp now so we don't re-apply if the server stays up a long time.
        writeTimestamp();
    }

    /** Apply stages to already-loaded chunks around the player. Does not generate or force-load chunks. */
    private static int applyAroundPlayer(ServerPlayerEntity player, int stages) {
        if (stages <= 0) return 0;
        ServerWorld world = player.getServerWorld();

        final int radiusChunks = 2; // small, safe radius
        ChunkPos center = player.getChunkPos();
        int changed = 0;

        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;

                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue; // skip if not already loaded

                changed += applyToChunk(world, chunk, stages);
            }
        }
        return changed;
    }

    private static int applyToChunk(ServerWorld world, WorldChunk chunk, int stages) {
        int changed = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int endX = chunk.getPos().getEndX();
        int endZ = chunk.getPos().getEndZ();

        // Only iterate reasonable Y-range for crops
        int minY = world.getBottomY();
        int maxY = Math.min(world.getTopY(), 256);

        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                for (int y = minY; y < maxY; y++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state == null) continue;

                    BlockState grown = growOnceOrMore(state, stages);
                    if (grown != state) {
                        // NO_REDRAW flag doesn’t exist on 1.20.4; NOTIFY_LISTENERS is fine here.
                        world.setBlockState(pos, grown, Block.NOTIFY_LISTENERS);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private static BlockState growOnceOrMore(BlockState state, int stages) {
        if (stages <= 0) return state;

        if (state.getBlock() instanceof CropBlock crop) {
            int age = crop.getAge(state);
            int max = crop.getMaxAge();
            int newAge = Math.min(max, age + stages);
            if (newAge != age) {
                return crop.withAge(newAge);
            }
            return state;
        }

        if (state.getBlock() instanceof NetherWartBlock) {
            Integer age = state.get(NetherWartBlock.AGE);
            if (age == null) return state;
            int newAge = Math.min(3, age + stages);
            if (newAge != age) return state.with(NetherWartBlock.AGE, newAge);
            return state;
        }

        return state;
    }
}
