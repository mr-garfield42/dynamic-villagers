package com.dynamicvillagers.client;

import com.dynamicvillagers.network.VillagerDebugStatePayload;
import net.minecraft.client.Minecraft;

/**
 * Client-only entry point for debug GUI payloads. Referenced exclusively from a lambda body
 * in DVNetwork so this class is never loaded on a dedicated server.
 */
public final class VillagerDebugClient {

    public static void handleState(VillagerDebugStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VillagerDebugScreen screen
                && screen.entityId() == payload.entityId()) {
            screen.update(payload);
        } else if (payload.open()) {
            minecraft.setScreen(new VillagerDebugScreen(payload));
        }
    }

    private VillagerDebugClient() {
    }
}
