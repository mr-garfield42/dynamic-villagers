package com.dynamicvillagers.network;

import com.dynamicvillagers.DynamicVillagers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a debug GUI button press. Actions: "set_role" (arg = role name),
 * "clear_tasks", "deposit", "pickup", "replan", "hunger" (arg = signed delta).
 */
public record VillagerDebugActionPayload(int entityId, String action, String arg) implements CustomPacketPayload {

    public static final Type<VillagerDebugActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "villager_debug_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerDebugActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, VillagerDebugActionPayload::entityId,
                    ByteBufCodecs.STRING_UTF8, VillagerDebugActionPayload::action,
                    ByteBufCodecs.STRING_UTF8, VillagerDebugActionPayload::arg,
                    VillagerDebugActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
