package com.dynamicvillagers.registry;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.VillagerEssence;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class DVAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DynamicVillagers.MOD_ID);

    public static final Supplier<AttachmentType<VillagerEssence>> VILLAGER_ESSENCE =
            ATTACHMENT_TYPES.register("villager_essence",
                    () -> AttachmentType.serializable(VillagerEssence::new).build());

    private DVAttachments() {
    }
}
