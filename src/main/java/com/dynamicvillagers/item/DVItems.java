package com.dynamicvillagers.item;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.network.VillagerDebugStatePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DVItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DynamicVillagers.MOD_ID);

    public static final DeferredItem<Item> DEBUG_WAND =
            ITEMS.registerSimpleItem("debug_wand", new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> MINE_MARKER =
            ITEMS.register("mine_marker", () -> new MineMarkerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> QUARRY_MARKER =
            ITEMS.register("quarry_marker", () -> new QuarryMarkerItem(new Item.Properties().stacksTo(1)));

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DEBUG_WAND);
            event.accept(MINE_MARKER);
            event.accept(QUARRY_MARKER);
        }
    }

    /**
     * Wand-on-villager opens the debug GUI. Handled via the interact event (not an item
     * override) because Villager#mobInteract would otherwise consume the click for trading
     * before the item ever gets asked.
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)) {
            return;
        }
        if (event.getItemStack().is(DEBUG_WAND.get())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
            if (event.getEntity() instanceof ServerPlayer player) {
                if (player.hasPermissions(2)) {
                    PacketDistributor.sendToPlayer(player, VillagerDebugStatePayload.snapshot(villager, true));
                } else {
                    player.displayClientMessage(Component.literal("The debug wand requires permission level 2"), true);
                }
            }
        } else if (event.getItemStack().getItem() instanceof SiteMarkerItem) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
            if (event.getEntity() instanceof ServerPlayer player) {
                if (SiteMarkerItem.mayDesignate(player)) {
                    SiteMarkerItem.bind(event.getItemStack(), villager);
                    player.displayClientMessage(Component.literal(
                            "Marker bound to %s — now click blocks to designate the site"
                                    .formatted(villager.getName().getString())), true);
                } else {
                    player.displayClientMessage(
                            Component.literal("Site markers require creative mode or permission level 2"), true);
                }
            }
        }
    }

    private DVItems() {
    }
}
