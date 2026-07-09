package com.dynamicvillagers.item;

import com.dynamicvillagers.villager.VillagerEssence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Click the block where a strip tunnel should start (while facing the direction it should
 * head) to assign it to the bound villager. See SiteMarkerItem for the binding flow.
 */
public class MineMarkerItem extends SiteMarkerItem {

    public MineMarkerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !mayDesignate(player)) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        Villager villager = boundVillager(level, context.getItemInHand());
        if (villager == null) {
            player.displayClientMessage(
                    Component.literal("Right-click a villager first to bind this marker"), true);
            return InteractionResult.FAIL;
        }
        BlockPos start = context.getClickedPos();
        Direction direction = context.getHorizontalDirection();
        VillagerEssence.get(villager).setMineSite(new VillagerEssence.MineSite(start, direction));
        ensureMinerRole(villager);
        player.displayClientMessage(Component.literal(
                "Mine site: tunnel from %s heading %s".formatted(start.toShortString(), direction.getName())), true);
        return InteractionResult.CONSUME;
    }
}
