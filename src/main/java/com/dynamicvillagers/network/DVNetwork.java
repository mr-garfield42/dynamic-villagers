package com.dynamicvillagers.network;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

public final class DVNetwork {
    private static final double MAX_DEBUG_DISTANCE = 32.0;

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // Deliberately a lambda, NOT a method reference: the client class must only be
        // resolved when the handler actually runs (never on a dedicated server).
        registrar.playToClient(VillagerDebugStatePayload.TYPE, VillagerDebugStatePayload.STREAM_CODEC,
                (payload, context) -> com.dynamicvillagers.client.VillagerDebugClient.handleState(payload));
        registrar.playToServer(VillagerDebugRequestPayload.TYPE, VillagerDebugRequestPayload.STREAM_CODEC,
                DVNetwork::handleRequest);
        registrar.playToServer(VillagerDebugActionPayload.TYPE, VillagerDebugActionPayload.STREAM_CODEC,
                DVNetwork::handleAction);
    }

    private static void handleRequest(VillagerDebugRequestPayload payload, IPayloadContext context) {
        Villager villager = resolveTarget(context, payload.entityId());
        if (villager != null) {
            PacketDistributor.sendToPlayer((ServerPlayer) context.player(),
                    VillagerDebugStatePayload.snapshot(villager, false));
        }
    }

    private static void handleAction(VillagerDebugActionPayload payload, IPayloadContext context) {
        Villager villager = resolveTarget(context, payload.entityId());
        if (villager == null) {
            return;
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        switch (payload.action()) {
            case "set_role" -> {
                VillagerRole role = VillagerRole.byName(payload.arg());
                if (role != null) {
                    essence.setRole(role);
                }
            }
            case "clear_tasks" -> essence.getTaskQueue().clear();
            case "deposit" -> essence.getTaskQueue().enqueue(new DepositToContainerTask());
            case "pickup" -> essence.getTaskQueue().enqueue(
                    new PickUpItemsTask(villager.blockPosition(), 8.0));
            case "replan" -> essence.setNextPlanTime(0);
            case "hunger" -> {
                try {
                    essence.addHunger(Integer.parseInt(payload.arg()));
                } catch (NumberFormatException ignored) {
                }
            }
            default -> {
            }
        }
        // immediate refresh so the GUI reflects the action without waiting for the next poll
        PacketDistributor.sendToPlayer((ServerPlayer) context.player(),
                VillagerDebugStatePayload.snapshot(villager, false));
    }

    /** Permission + distance gate shared by all debug payloads. */
    @Nullable
    private static Villager resolveTarget(IPayloadContext context, int entityId) {
        if (context.player() instanceof ServerPlayer player
                && player.hasPermissions(2)
                && player.level().getEntity(entityId) instanceof Villager villager
                && villager.isAlive()
                && villager.distanceTo(player) <= MAX_DEBUG_DISTANCE) {
            return villager;
        }
        return null;
    }

    private DVNetwork() {
    }
}
