package com.dynamicvillagers.villager;

import com.dynamicvillagers.village.StorageLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * Killed villagers drop everything they carry — vanilla silently deletes the 8-slot villager
 * inventory on death, which violates design rule "no item deletion / player rules". Both the
 * vanilla inventory and our extra slots spill as ordinary item entities.
 */
public final class DeathDropSystem {

    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Villager villager && villager.level() instanceof ServerLevel level) {
            spill(event, villager, villager.getInventory());
            spill(event, villager, VillagerEssence.get(villager).getExtraInventory());
            StorageLedger.get(level).releaseAll(villager.getUUID()); // dead villagers hold no claims
        }
    }

    private static void spill(LivingDropsEvent event, Villager villager, SimpleContainer container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                ItemEntity drop = new ItemEntity(villager.level(),
                        villager.getX(), villager.getY() + 0.5, villager.getZ(),
                        stack.copyAndClear());
                drop.setDefaultPickUpDelay();
                event.getDrops().add(drop);
            }
        }
    }

    private DeathDropSystem() {
    }
}
