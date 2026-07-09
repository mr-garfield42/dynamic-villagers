package com.dynamicvillagers;

import com.dynamicvillagers.command.DVCommands;
import com.dynamicvillagers.item.DVItems;
import com.dynamicvillagers.network.DVNetwork;
import com.dynamicvillagers.registry.DVAttachments;
import com.dynamicvillagers.villager.DeathDropSystem;
import com.dynamicvillagers.villager.HungerSystem;
import com.dynamicvillagers.villager.PerceptionSystem;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(DynamicVillagers.MOD_ID)
public class DynamicVillagers {
    public static final String MOD_ID = "dynamicvillagers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicVillagers(IEventBus modEventBus, ModContainer modContainer) {
        DVAttachments.ATTACHMENT_TYPES.register(modEventBus);
        DVItems.ITEMS.register(modEventBus);
        modEventBus.addListener(DVNetwork::register);
        modEventBus.addListener(DVItems::addCreative);

        NeoForge.EVENT_BUS.addListener(HungerSystem::onEntityTick);
        NeoForge.EVENT_BUS.addListener(PerceptionSystem::onEntityTick);
        NeoForge.EVENT_BUS.addListener(DeathDropSystem::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(DVItems::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(DVCommands::onRegisterCommands);

        LOGGER.info("Dynamic Villagers initializing");
    }
}
