package com.afkbot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Handles force-loading a square of chunks around a bot so that the area is
 * fully simulated (mobs spawn, redstone runs, crops grow, hoppers work) —
 * the same mechanism as the /forceload command.
 *
 * <p>All chunk work is isolated here so {@link BotManager} only has to call
 * {@link #apply} when a bot appears / changes radius and {@link #release} when
 * it goes away. Callers are responsible for storing the returned chunk set and
 * passing it back to {@link #release} — otherwise the chunks stay force-loaded
 * forever.
 */
public final class BotChunkLoader {

    private BotChunkLoader() {}

    /**
     * Force-loads a (2*radius+1) x (2*radius+1) square of chunks centered on
     * the given position.
     *
     * @return the set of chunks that were force-loaded, to be passed to
     *         {@link #release} later.
     */
    public static Set<ChunkPos> apply(ServerLevel world, Vec3 pos, int radius) {
        Set<ChunkPos> forced = new HashSet<>();
        int centerX = ((int) Math.floor(pos.x)) >> 4;
        int centerZ = ((int) Math.floor(pos.z)) >> 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                world.setChunkForced(cx, cz, true);
                forced.add(new ChunkPos(cx, cz));
            }
        }
        return forced;
    }

    /**
     * Removes the force-load flag from every chunk in the given set.
     */
    public static void release(ServerLevel world, Set<ChunkPos> chunks) {
        if (chunks == null) {
            return;
        }
        for (ChunkPos pos : chunks) {
            world.setChunkForced(pos.x(), pos.z(), false);
        }
    }
}
