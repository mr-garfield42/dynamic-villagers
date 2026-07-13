package com.dynamicvillagers.network;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.Village;
import com.dynamicvillagers.village.VillageManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public record VillageDebugStatePayload(int villageId, String name, String center, int radius,
                                       int population, int adults, int children, int beds,
                                       int freeBeds, int houses, int openSites, int publicStorage,
                                       int guards, boolean autoStaff, List<String> roles,
                                       List<String> siteProgress) implements CustomPacketPayload {
    public static final Type<VillageDebugStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "village_debug_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VillageDebugStatePayload> STREAM_CODEC =
            StreamCodec.of(VillageDebugStatePayload::encode, VillageDebugStatePayload::decode);

    public static VillageDebugStatePayload snapshot(ServerLevel level, Village village) {
        VillageManager.get(level).refreshTallies(level, village);
        int storage = (int) StorageLedger.get(level).recordsNear(village.center(), village.radius()).stream()
                .filter(entry -> entry.getValue().villageId() == village.id())
                .filter(entry -> entry.getValue().designation() == StorageLedger.Designation.PUBLIC).count();
        List<String> roles = village.roles().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey().lowerName() + ": " + entry.getValue()).toList();
        List<String> sites = ConstructionLedger.get(level).allSites().stream()
                .filter(site -> site.villageId() == village.id())
                .filter(site -> site.status() == ConstructionLedger.Status.OPEN)
                .map(site -> "#%d %s — %s".formatted(site.id(), site.type().name().toLowerCase(java.util.Locale.ROOT),
                        site.templateId().getPath())).toList();
        return new VillageDebugStatePayload(village.id(), village.name(), village.center().toShortString(),
                village.radius(), village.population(), village.adults(), village.children(), village.beds(),
                village.freeBeds(), village.houses(), village.openSites(), storage, village.guards(),
                village.autoStaff(), roles, sites);
    }

    private static void encode(RegistryFriendlyByteBuf buf, VillageDebugStatePayload payload) {
        buf.writeVarInt(payload.villageId);
        buf.writeUtf(payload.name);
        buf.writeUtf(payload.center);
        buf.writeVarInt(payload.radius);
        buf.writeVarInt(payload.population);
        buf.writeVarInt(payload.adults);
        buf.writeVarInt(payload.children);
        buf.writeVarInt(payload.beds);
        buf.writeVarInt(payload.freeBeds);
        buf.writeVarInt(payload.houses);
        buf.writeVarInt(payload.openSites);
        buf.writeVarInt(payload.publicStorage);
        buf.writeVarInt(payload.guards);
        buf.writeBoolean(payload.autoStaff);
        writeStrings(buf, payload.roles);
        writeStrings(buf, payload.siteProgress);
    }

    private static VillageDebugStatePayload decode(RegistryFriendlyByteBuf buf) {
        return new VillageDebugStatePayload(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                readStrings(buf), readStrings(buf));
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        for (String value : values) buf.writeUtf(value);
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        java.util.ArrayList<String> values = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) values.add(buf.readUtf());
        return values;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
