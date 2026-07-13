package com.dynamicvillagers.client;

import com.dynamicvillagers.network.VillageDebugStatePayload;
import net.minecraft.client.Minecraft;

public final class VillageDebugClient {
    public static void handleState(VillageDebugStatePayload payload) {
        Minecraft.getInstance().setScreen(new VillageDebugScreen(payload));
    }

    private VillageDebugClient() {
    }
}
