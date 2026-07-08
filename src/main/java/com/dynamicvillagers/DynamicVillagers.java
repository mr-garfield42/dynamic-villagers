package com.dynamicvillagers;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(DynamicVillagers.MOD_ID)
public class DynamicVillagers {
    public static final String MOD_ID = "dynamicvillagers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicVillagers(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Dynamic Villagers initializing");
    }
}
