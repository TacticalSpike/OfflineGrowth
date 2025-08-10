package com.example.offlinegrowth;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.WorldView;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;




public class OfflineGrowthMod implements ModInitializer {
    public static final String MODID = "offlinegrowth";

    // ---- debug toggle / helper ------------------------------------------------
    private static final boolean DEBUG = false; // flip to true for verbose logs
    private static void dbg(String msg) { if (DEBUG) System.out.println(msg); }

    // ---- tiny tick scheduler (server-thread only) -----------------------------
    private static final Map<MinecraftServer, List<_Task>> _tasks = new WeakHashMap<>();
    private static final class _Task {
        int ticks; Runnable run;
        _Task(int ticks, Runnable run) { this.ticks = ticks; this.run = run; }
    }
    private static void runLater(MinecraftServer server, int ticks, Runnable r) {
        _tasks.computeIfAbsent(server, s -> new ArrayList<>()).add(new _Task(ticks, r));
    }
    private static void initSchedulerHook() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<_Task> list = _tasks.get(server);
            if (list == null || list.isEmpty()) return;
            for (int i = 0; i < list.size(); ) {
                _Task t = list.get(i);
                if (--t.ticks <= 0) {
                    try { t.run.run(); } catch (Throwable e) { e.printStackTrace(); }
                    list.remove(i);
                } else i++;
            }
        });
    }
    // ---------------------------------------------------------------------------

    private Config config;
    private volatile int stagesToAdd = 0; // computed on server start
    private volatile boolean appliedThisSession = false;

    @Override
    public void onInitialize() {
        initSchedulerHook();

        // load config
        config = Config.load(FabricLoader.getInstance().getConfigDir());
        dbg("[OfflineGrowth] Config dir = " + FabricLoader.getInstance().getConfigDir().toAbsolutePath());
        dbg("[OfflineGrowth] Loaded config: hours=" + config.maxOfflineHours + " ticksPerStage=" + config.ticksPerStage);

        // lifecycle stamps
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // Apply once around the player on join, WITHOUT force-loading
    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
        if (stagesToAdd <= 0 || appliedThisSession) return;

        // wait ~4s so vanilla finishes loading/sending chunks
        runLater(server, 80, () -> {
            try {
                ServerWorld world = handler.player.getServerWorld();
                if (world.getRegistryKey() != World.OVERWORLD) return;

                // mirror vanilla: only touch chunks already loaded by view/sim distance
                int view = server.getPlayerManager().getViewDistance();          // chunks
                int sim  = server.getPlayerManager().getSimulationDistance();    // chunks
                int radius = Math.max(1, Math.min(view, sim));                   // conservative

                ChunkPos p = handler.player.getChunkPos();
                boolean anyChanged = false;

                // 1) Player's chunk first so you see it update immediately
                WorldChunk center = world.getChunkManager().getWorldChunk(p.x, p.z); // no force-load
                if (center != null && applyToChunk(world, center)) anyChanged = true;

                // 2) Surrounding chunks that are already loaded by vanilla
                for (int cx = p.x - radius; cx <= p.x + radius; cx++) {
                    for (int cz = p.z - radius; cz <= p.z + radius; cz++) {
                        if (cx == p.x && cz == p.z) continue;
                        WorldChunk c = world.getChunkManager().getWorldChunk(cx, cz); // null if not loaded
                        if (c == null) continue;
                        if (applyToChunk(world, c)) anyChanged = true;
                    }
                }

                if (anyChanged) {
                    stagesToAdd = 0;            // one-time per offline stint
                    appliedThisSession = true;  // one-time per session
                }

                dbg("[OfflineGrowth] JOIN vanilla-radius apply; radius=" + radius +
                    " anyChanged=" + anyChanged + " stagesToAdd=" + stagesToAdd);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    });

    }

    private void onServerStarted(MinecraftServer server) {
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        Path stampFile = worldRoot.resolve("offlinegrowth_last_seen.txt");

        long now = Instant.now().toEpochMilli();
        long last = readLast(stampFile);
        // Write immediately so a crash still preserves something for next run
        writeLast(stampFile, now);

        if (last <= 0) {
            stagesToAdd = 0;
            dbg("[OfflineGrowth] First run detected; timestamp set. No offline growth this time.");
            return;
        }

        long elapsedMs = Math.max(0, now - last);
        long maxMs = (long) config.maxOfflineHours * 60L * 60L * 1000L;
        long usedMs = Math.min(elapsedMs, maxMs);

        long offlineTicks = usedMs / 50L; // 20 tps
        int stages = (int) Math.max(0, Math.min(7, offlineTicks / Math.max(1, config.ticksPerStage)));
        stagesToAdd = stages;

        dbg("[OfflineGrowth] last=" + last +
            " now=" + now +
            " elapsedMs=" + elapsedMs +
            " usedMs=" + usedMs +
            " ticksPerStage=" + config.ticksPerStage +
            " -> stagesToAdd=" + stagesToAdd);
    }

    private void onServerStopping(MinecraftServer server) {
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        Path stampFile = worldRoot.resolve("offlinegrowth_last_seen.txt");
        writeLast(stampFile, Instant.now().toEpochMilli());
    }

    private static long readLast(Path file) {
        try {
            if (Files.exists(file)) {
                String s = Files.readString(file).trim();
                return Long.parseLong(s);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static void writeLast(Path file, long value) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(value));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // returns true iff something in this chunk changed
    private boolean applyToChunk(World world, WorldChunk chunk) {
        int add = stagesToAdd;
        if (add <= 0) return false; // bail quietly when nothing to do

        dbg("[OfflineGrowth] applyToChunk add=" + add +
            " chunk=" + chunk.getPos().x + "," + chunk.getPos().z +
            " dim=" + world.getRegistryKey().getValue());

        ChunkPos cp = chunk.getPos();
        int x0 = cp.getStartX();
        int z0 = cp.getStartZ();
        int x1 = cp.getEndX();
        int z1 = cp.getEndZ();

        AtomicInteger changed = new AtomicInteger();

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                int bottomY = Math.max(world.getBottomY(), topY - 48); // scan top ~48 blocks
                for (int y = topY - 1; y >= bottomY; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    // Wheat, Carrots, Potatoes, Beetroots (respect config by ID)
                    if (config.affectWheat || config.affectCarrots || config.affectPotatoes || config.affectBeetroots) {
                        if (block instanceof CropBlock crop) {
                            Identifier id = Registries.BLOCK.getId(block);
                            String path = id.getPath();
                            boolean skip =
                                (path.equals("wheat") && !config.affectWheat) ||
                                (path.equals("carrots") && !config.affectCarrots) ||
                                (path.equals("potatoes") && !config.affectPotatoes) ||
                                (path.equals("beetroots") && !config.affectBeetroots);
                            if (!skip) {
                                bumpCrop(world, pos, state, crop, add, changed);
                            }
                        }
                    }

                    // Nether wart
                    if (config.affectNetherWart && block instanceof NetherWartBlock) {
                        int age = state.get(NetherWartBlock.AGE);
                        int max = 3;
                        int newAge = Math.min(max, age + add);
                        if (newAge != age) {
                            world.setBlockState(pos, state.with(NetherWartBlock.AGE, newAge), Block.NOTIFY_LISTENERS);
                            changed.incrementAndGet();
                        }
                    }

                    // Sweet berry bush
                    if (config.affectSweetBerry && block instanceof SweetBerryBushBlock) {
                        int age = state.get(SweetBerryBushBlock.AGE);
                        int max = 3;
                        int newAge = Math.min(max, age + add);
                        if (newAge != age) {
                            world.setBlockState(pos, state.with(SweetBerryBushBlock.AGE, newAge), Block.NOTIFY_LISTENERS);
                            changed.incrementAndGet();
                        }
                    }
                }
            }
        }

        dbg("[OfflineGrowth] changed=" + changed.get());
        return changed.get() > 0;
    }

    private void bumpCrop(World world, BlockPos pos, BlockState state, CropBlock crop, int add, AtomicInteger changed) {
        int age = crop.getAge(state);
        int max = crop.getMaxAge();
        if (age >= max) return;

        int newAge = Math.min(max, age + add);

        // Optional: enforce vanilla-ish light rule later if you want
        if (!hasSufficientLight(world, pos)) return;

        if (newAge != age) {
            world.setBlockState(pos, crop.withAge(newAge), Block.NOTIFY_LISTENERS);
            changed.incrementAndGet();
        }
    }

    private boolean hasSufficientLight(WorldView world, BlockPos pos) {
        return true; // or: return world.getBaseLightLevel(pos, 0) >= 9;
    }
}
