package com.dynamicvillagers;

import com.dynamicvillagers.command.DVCommands;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.construction.BuildingCatalog;
import com.dynamicvillagers.item.DVItems;
import com.dynamicvillagers.network.DVNetwork;
import com.dynamicvillagers.registry.DVAttachments;
import com.dynamicvillagers.villager.DeathDropSystem;
import com.dynamicvillagers.villager.HungerSystem;
import com.dynamicvillagers.villager.PerceptionSystem;
import com.dynamicvillagers.villager.work.ContainerAnimator;
import com.dynamicvillagers.village.VillageEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(DynamicVillagers.MOD_ID)
public class DynamicVillagers {
    public static final String MOD_ID = "dynamicvillagers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicVillagers(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, VillageConfig.SPEC);
        DVAttachments.ATTACHMENT_TYPES.register(modEventBus);
        DVItems.ITEMS.register(modEventBus);
        modEventBus.addListener(DVNetwork::register);
        modEventBus.addListener(DVItems::addCreative);

        NeoForge.EVENT_BUS.addListener(HungerSystem::onEntityTick);
        NeoForge.EVENT_BUS.addListener(PerceptionSystem::onEntityTick);
        NeoForge.EVENT_BUS.addListener(DeathDropSystem::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(DVItems::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(DVItems::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(ContainerAnimator::onLevelTick);
        NeoForge.EVENT_BUS.addListener(VillageEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(VillageEvents::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(VillageEvents::onFinalizeSpawn);
        NeoForge.EVENT_BUS.addListener(VillageEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(DVCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.AddReloadListenerEvent event) -> {
            Blueprints.clearCache();
            event.addListener(BuildingCatalog.INSTANCE);
        });

        LOGGER.info("Dynamic Villagers initializing");
    }
}
