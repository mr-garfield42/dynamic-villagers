package com.dynamicvillagers.network;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.Task;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client snapshot of one villager's Dynamic Villagers state, for the debug GUI.
 * {@code open} is true only for the wand click that should open the screen; polling
 * responses use false so a just-closed screen doesn't reopen.
 */
public record VillagerDebugStatePayload(int entityId, boolean open, int hunger, String role,
                                        List<String> tasks, int knownContainers, int knownTrees,
                                        List<ItemStack> vanillaInventory,
                                        List<ItemStack> extraInventory) implements CustomPacketPayload {

    public static final Type<VillagerDebugStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVillagers.MOD_ID, "villager_debug_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerDebugStatePayload> STREAM_CODEC =
            StreamCodec.of(VillagerDebugStatePayload::encode, VillagerDebugStatePayload::decode);

    public static VillagerDebugStatePayload snapshot(Villager villager, boolean open) {
        VillagerEssence essence = VillagerEssence.get(villager);
        return new VillagerDebugStatePayload(
                villager.getId(),
                open,
                essence.getHunger(),
                essence.getRole().lowerName(),
                essence.getTaskQueue().tasks().stream().map(Task::typeId).toList(),
                essence.getMemory().knownContainers().size(),
                essence.getMemory().knownSpots("tree").size(),
                copyStacks(villager.getInventory()),
                copyStacks(essence.getExtraInventory()));
    }

    // copies so the client never aliases live server stacks over the singleplayer local channel
    private static List<ItemStack> copyStacks(SimpleContainer container) {
        List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            stacks.add(container.getItem(i).copy());
        }
        return stacks;
    }

    private static void encode(RegistryFriendlyByteBuf buf, VillagerDebugStatePayload payload) {
        buf.writeVarInt(payload.entityId);
        buf.writeBoolean(payload.open);
        buf.writeVarInt(payload.hunger);
        buf.writeUtf(payload.role);
        buf.writeVarInt(payload.tasks.size());
        for (String task : payload.tasks) {
            buf.writeUtf(task);
        }
        buf.writeVarInt(payload.knownContainers);
        buf.writeVarInt(payload.knownTrees);
        writeStacks(buf, payload.vanillaInventory);
        writeStacks(buf, payload.extraInventory);
    }

    private static VillagerDebugStatePayload decode(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        boolean open = buf.readBoolean();
        int hunger = buf.readVarInt();
        String role = buf.readUtf();
        int taskCount = buf.readVarInt();
        List<String> tasks = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            tasks.add(buf.readUtf());
        }
        int knownContainers = buf.readVarInt();
        int knownTrees = buf.readVarInt();
        List<ItemStack> vanilla = readStacks(buf);
        List<ItemStack> extra = readStacks(buf);
        return new VillagerDebugStatePayload(entityId, open, hunger, role, tasks,
                knownContainers, knownTrees, vanilla, extra);
    }

    private static void writeStacks(RegistryFriendlyByteBuf buf, List<ItemStack> stacks) {
        buf.writeVarInt(stacks.size());
        for (ItemStack stack : stacks) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
    }

    private static List<ItemStack> readStacks(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return stacks;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
