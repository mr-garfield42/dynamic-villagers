package com.dynamicvillagers.item;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
 * Base for the creative-mode designation tools: right-click a villager to bind the marker to
 * it (handled in DVItems' interact listener, before trading can eat the click), then click
 * blocks to designate work sites for the bound villager. The binding lives in the stack's
 * custom data, so each marker item remembers its own villager.
 */
public abstract class SiteMarkerItem extends Item {
    protected static final String VILLAGER_KEY = "dv_villager";

    protected SiteMarkerItem(Properties properties) {
        super(properties);
    }

    public static boolean mayDesignate(Player player) {
        return player.isCreative() || player.hasPermissions(2);
    }

    public static void bind(ItemStack stack, Villager villager) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putUUID(VILLAGER_KEY, villager.getUUID()));
    }

    @Nullable
    protected static Villager boundVillager(ServerLevel level, ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.hasUUID(VILLAGER_KEY)) {
            return null;
        }
        return level.getEntity(tag.getUUID(VILLAGER_KEY)) instanceof Villager villager
                && villager.isAlive() ? villager : null;
    }

    /** Designating a mining site implies the job — no point marking sites nobody works. */
    protected static void ensureMinerRole(Villager villager) {
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.getRole() != VillagerRole.MINER) {
            essence.setRole(VillagerRole.MINER);
        }
    }
}
