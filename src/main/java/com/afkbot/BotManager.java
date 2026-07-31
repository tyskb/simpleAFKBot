package com.afkbot;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class BotManager {

    // Keeps track of all active bots: botName -> BotInfo
    private static final Map<String, BotInfo> activeBots = new HashMap<>();

    // Configurable limits (defaults, can be changed in-game by OPs)
    private static int maxBotsPerPlayer = 2;
    private static int maxBotsTotal = 10;

    // Auto-cleanup toggle (can be turned on/off by OPs)
    private static boolean autoCleanupEnabled = true;

    // Default simulation radius (in chunks) applied to newly spawned bots.
    // Clamped to the server's simulation-distance at spawn time.
    private static int defaultRadius = 10;

    // Hard upper bound for the radius command argument. The real cap is the
    // server's simulation-distance (see clampRadius).
    public static final int MAX_RADIUS_ARG = 32;

    // Auto-remove timer: ticks since server had no real players
    private static int emptyServerTicks = 0;
    private static final int AUTO_REMOVE_TICKS = 5 * 60 * 20; // 5 minutes = 6000 ticks

    // --- Config getters/setters ---

    public static int getMaxBotsPerPlayer() {
        return maxBotsPerPlayer;
    }

    public static void setMaxBotsPerPlayer(int value) {
        maxBotsPerPlayer = Math.max(1, value);
    }

    public static int getMaxBotsTotal() {
        return maxBotsTotal;
    }

    public static void setMaxBotsTotal(int value) {
        maxBotsTotal = Math.max(1, value);
    }

    public static boolean isAutoCleanupEnabled() {
        return autoCleanupEnabled;
    }

    public static void setAutoCleanupEnabled(boolean value) {
        autoCleanupEnabled = value;
    }

    public static int getDefaultRadius() {
        return defaultRadius;
    }

    public static void setDefaultRadius(int value) {
        defaultRadius = Math.max(1, value);
    }

    /** Result of clamping a requested radius against the server cap. */
    public static class ClampResult {
        public final int value;
        public final boolean wasClamped;
        public final int serverMax;

        public ClampResult(int value, boolean wasClamped, int serverMax) {
            this.value = value;
            this.wasClamped = wasClamped;
            this.serverMax = serverMax;
        }
    }

    /**
     * Clamps a requested radius to the server's simulation-distance, mirroring
     * how a real player's simulation distance can never exceed the server's.
     */
    public static ClampResult clampRadius(MinecraftServer server, int requested) {
        int serverMax = server.getPlayerList().getSimulationDistance();
        int clamped = Math.max(1, Math.min(requested, serverMax));
        return new ClampResult(clamped, clamped != requested, serverMax);
    }

    /**
     * Stores info about a bot: who owns it, the entity, and spawn position.
     */
    public static class BotInfo {
        public final ServerPlayer player;
        public final UUID ownerUUID;
        public final String ownerName;
        public final Vec3 spawnPos;
        public final ServerLevel spawnWorld;

        // Simulation radius (in chunks) and the chunks currently force-loaded
        // for this bot. Both change together via setBotRadius.
        public int simulationRadius;
        public Set<ChunkPos> forcedChunks = new HashSet<>();

        public BotInfo(ServerPlayer player, UUID ownerUUID, String ownerName, Vec3 spawnPos, ServerLevel spawnWorld, int simulationRadius) {
            this.player = player;
            this.ownerUUID = ownerUUID;
            this.ownerName = ownerName;
            this.spawnPos = spawnPos;
            this.spawnWorld = spawnWorld;
            this.simulationRadius = simulationRadius;
        }
    }

    /**
     * Called every server tick.
     * - Checks if any bot has died, and removes it.
     * - If auto-cleanup is enabled, checks if only bots remain. After 5 min, removes all bots.
     */
    public static void tick(MinecraftServer server) {
        // Check for dead bots and remove them
        List<String> deadBots = new ArrayList<>();
        for (Map.Entry<String, BotInfo> entry : activeBots.entrySet()) {
            ServerPlayer bot = entry.getValue().player;
            if (bot.isDeadOrDying()) {
                deadBots.add(entry.getKey());
            }
        }
        for (String name : deadBots) {
            AfkBotMod.LOGGER.info("Bot '{}' died — removing from server.", name);
            removeBotInternal(server, name);
        }

        // Auto-remove timer when no real players online (only if enabled)
        if (!autoCleanupEnabled || activeBots.isEmpty()) {
            emptyServerTicks = 0;
            return;
        }

        int totalPlayers = server.getPlayerList().getPlayers().size();
        int botCount = activeBots.size();
        int realPlayers = totalPlayers - botCount;

        if (realPlayers <= 0) {
            emptyServerTicks++;

            if (emptyServerTicks >= AUTO_REMOVE_TICKS) {
                AfkBotMod.LOGGER.info("Server empty for 5 minutes — removing all AFK bots.");
                removeAllBots(server);
                emptyServerTicks = 0;
            }
        } else {
            emptyServerTicks = 0;
        }
    }

    /**
     * Counts how many bots a specific player owns.
     */
    public static int countBotsOwnedBy(UUID ownerUUID) {
        int count = 0;
        for (BotInfo info : activeBots.values()) {
            if (info.ownerUUID.equals(ownerUUID)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Checks if a player owns a specific bot.
     */
    public static boolean isOwnedBy(String botName, UUID ownerUUID) {
        BotInfo info = activeBots.get(botName.toLowerCase());
        return info != null && info.ownerUUID.equals(ownerUUID);
    }

    /**
     * Spawns a fake player bot at the given position in the given world.
     */
    public static boolean spawnBot(MinecraftServer server, String name, Vec3 pos, ServerLevel world, UUID ownerUUID, String ownerName) {
        try {
            UUID botUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
            GameProfile profile = new GameProfile(botUUID, name);

            // Create a fake connection with a dummy Netty channel via reflection
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel dummyChannel = new EmbeddedChannel();
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(connection, dummyChannel);

            // Create the fake player entity
            ServerPlayer bot = new ServerPlayer(server, world, profile, ClientInformation.createDefault());
            bot.setPos(pos.x, pos.y, pos.z);

            // Add to server
            server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));

            // Determine the simulation radius (default, capped by the server)
            int radius = clampRadius(server, defaultRadius).value;
            BotInfo info = new BotInfo(bot, ownerUUID, ownerName, pos, world, radius);

            // Force-load the surrounding chunks so the area is fully simulated.
            // Uses the bot's live position so it stays correct even if a bot
            // is ever able to move.
            info.forcedChunks = BotChunkLoader.apply(world, bot.position(), radius);

            // Track it with owner info
            activeBots.put(name.toLowerCase(), info);

            AfkBotMod.LOGGER.info("Spawned AFK bot: {} (owner: {}) at ({}, {}, {}) with radius {}", name, ownerName, pos.x, pos.y, pos.z, radius);
            return true;

        } catch (Exception e) {
            AfkBotMod.LOGGER.error("Failed to spawn bot: {}", name, e);
            return false;
        }
    }

    /**
     * Internal remove — used by death check and public remove methods.
     */
    private static void removeBotInternal(MinecraftServer server, String name) {
        BotInfo info = activeBots.remove(name.toLowerCase());
        if (info != null) {
            BotChunkLoader.release(info.spawnWorld, info.forcedChunks);
            server.getPlayerList().remove(info.player);
        }
    }

    /**
     * Removes a bot by name. Returns true if found and removed.
     */
    public static boolean removeBot(MinecraftServer server, String name) {
        BotInfo info = activeBots.get(name.toLowerCase());
        if (info != null) {
            removeBotInternal(server, name);
            AfkBotMod.LOGGER.info("Removed AFK bot: {}", name);
            return true;
        }
        return false;
    }

    /**
     * Removes all bots owned by a specific player.
     */
    public static int removeAllBotsByOwner(MinecraftServer server, UUID ownerUUID) {
        List<String> toRemove = activeBots.entrySet().stream()
            .filter(e -> e.getValue().ownerUUID.equals(ownerUUID))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        for (String name : toRemove) {
            removeBotInternal(server, name);
        }
        return toRemove.size();
    }

    /**
     * Removes all bots (admin command).
     */
    public static int removeAllBots(MinecraftServer server) {
        int count = activeBots.size();
        for (BotInfo info : activeBots.values()) {
            BotChunkLoader.release(info.spawnWorld, info.forcedChunks);
            server.getPlayerList().remove(info.player);
        }
        activeBots.clear();
        AfkBotMod.LOGGER.info("Removed all {} AFK bot(s)", count);
        return count;
    }

    /**
     * Changes a bot's simulation radius: releases its old force-loaded chunks
     * and applies a new set around its spawn position. The value is assumed to
     * already be clamped by the caller via {@link #clampRadius}.
     */
    public static void setBotRadius(String name, int radius) {
        BotInfo info = activeBots.get(name.toLowerCase());
        if (info == null) {
            return;
        }
        // Nothing to do if the radius is unchanged — avoids needlessly
        // unloading and re-loading potentially hundreds of chunks.
        if (info.simulationRadius == radius) {
            return;
        }
        BotChunkLoader.release(info.spawnWorld, info.forcedChunks);
        info.simulationRadius = radius;
        info.forcedChunks = BotChunkLoader.apply(info.spawnWorld, info.player.position(), radius);
        AfkBotMod.LOGGER.info("Bot '{}' simulation radius set to {}", name, radius);
    }

    /** Returns a bot's current simulation radius, or -1 if no such bot. */
    public static int getBotRadius(String name) {
        BotInfo info = activeBots.get(name.toLowerCase());
        return info != null ? info.simulationRadius : -1;
    }

    public static boolean hasBot(String name) {
        return activeBots.containsKey(name.toLowerCase());
    }

    public static int getTotalBotCount() {
        return activeBots.size();
    }

    public static List<String> getBotNames() {
        return new ArrayList<>(activeBots.keySet());
    }

    /**
     * Returns bot entries formatted as "name (r=N)" for display, sorted by
     * name so the output is stable and easy to scan.
     */
    public static List<String> getBotDisplayList() {
        return activeBots.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + " (r=" + e.getValue().simulationRadius + ")")
            .collect(Collectors.toList());
    }
}
