package com.dynamicvillagers.network;

import com.dynamicvillagers.DynamicVillagers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: the open debug screen asks for a fresh snapshot of this villager. */
public record VillagerDebugRequestPayload(int entityId) implements CustomPacketPayload {

    public static final Type<VillagerDebugRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "villager_debug_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerDebugRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, VillagerDebugRequestPayload::entityId,
                    VillagerDebugRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
