package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.DeliverItemsTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Serves the village request board (milestone 3.3). Two entry points, both enqueueing
 * DeliverItemsTask: `plan` is the idle fallback — haul requested goods out of known storage
 * to the requesting chest; `planCarriedDelivery` is the haul-time shortcut — a gatherer
 * whose armful matches an open request walks it straight to the requester instead of
 * general storage. The planner's keep list rides along so villagers never give away the
 * tools and food they work with.
 */
public final class RequestChore {
    public static final int HAUL_BATCH = 32; // one errand's worth; big requests take trips

    /** Idle fallback: fulfill the oldest serveable request in network range. */
    public static boolean plan(ServerLevel level, Villager villager, VillagerEssence essence,
                               List<String> keepFilters) {
        StorageLedger ledger = StorageLedger.get(level);
        BlockPos anchor = VillageAnchor.resolve(level, villager);
        long now = level.getGameTime();
        UUID self = villager.getUUID();
        for (StorageLedger.MaterialRequest request
                : ledger.openRequests(anchor, StorageLedger.NETWORK_RANGE)) {
            Predicate<ItemStack> matching = DeliverItemsTask.effectiveFilter(request.filter(), keepFilters);
            boolean carrying = essence.countItems(villager, matching) > 0;
            if (!carrying && ledger.findSource(anchor, villager.blockPosition(), self,
                    matching, now, Set.of(request.deliverTo())) == null) {
                continue; // nothing carried and no known source — someone else's errand
            }
            enqueue(essence, request, keepFilters);
            return true;
        }
        return false;
    }

    /** Haul-time shortcut: only fires when carried (non-kept) items match an open request. */
    public static boolean planCarriedDelivery(ServerLevel level, Villager villager,
                                              VillagerEssence essence, List<String> keepFilters) {
        StorageLedger ledger = StorageLedger.get(level);
        BlockPos anchor = VillageAnchor.resolve(level, villager);
        for (StorageLedger.MaterialRequest request
                : ledger.openRequests(anchor, StorageLedger.NETWORK_RANGE)) {
            Predicate<ItemStack> matching = DeliverItemsTask.effectiveFilter(request.filter(), keepFilters);
            if (essence.countItems(villager, matching) > 0) {
                enqueue(essence, request, keepFilters);
                return true;
            }
        }
        return false;
    }

    private static void enqueue(VillagerEssence essence, StorageLedger.MaterialRequest request,
                                List<String> keepFilters) {
        essence.getTaskQueue().enqueue(new DeliverItemsTask(request.filter(), keepFilters,
                Math.min(request.remaining(), HAUL_BATCH), request.deliverTo(), request.id()));
    }

    private RequestChore() {
    }
}
