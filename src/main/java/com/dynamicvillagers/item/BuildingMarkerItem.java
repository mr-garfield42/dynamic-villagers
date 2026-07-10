package com.dynamicvillagers.item;

import com.dynamicvillagers.construction.Blueprint;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.construction.SiteValidator;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Rotation;

/**
 * Click the ground to post a construction site and put the bound villager on it: the site
 * origin is the block face you clicked (like placing a block there), so clicking the top of
 * flat ground starts the build sitting on it. Sneak-click cycles the rotation for the next
 * site. The template comes from the stack's custom data ({@code dv_template}) and defaults
 * to the starter shelter; `/dv build add` covers arbitrary templates. See SiteMarkerItem
 * for the villager-binding flow.
 */
public class BuildingMarkerItem extends SiteMarkerItem {
    private static final String TEMPLATE_KEY = "dv_template";
    private static final String ROTATION_KEY = "dv_rotation";
    /** Owner directive: villages build the vanilla village structures, so the marker does too. */
    public static final ResourceLocation DEFAULT_TEMPLATE =
            ResourceLocation.withDefaultNamespace("village/plains/houses/plains_small_house_1");

    public BuildingMarkerItem(Properties properties) {
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
        ItemStack stack = context.getItemInHand();
        if (context.isSecondaryUseActive()) {
            Rotation next = rotation(stack).getRotated(Rotation.CLOCKWISE_90);
            CustomData.update(DataComponents.CUSTOM_DATA, stack,
                    tag -> tag.putString(ROTATION_KEY, next.name()));
            player.displayClientMessage(Component.literal(
                    "Marker rotation: " + next.name().toLowerCase(java.util.Locale.ROOT)), true);
            return InteractionResult.CONSUME;
        }
        Villager villager = boundVillager(level, stack);
        if (villager == null) {
            player.displayClientMessage(
                    Component.literal("Right-click a villager first to bind this marker"), true);
            return InteractionResult.FAIL;
        }

        ResourceLocation templateId = templateId(stack);
        Blueprint blueprint = Blueprints.load(level, templateId);
        if (blueprint == null) {
            player.displayClientMessage(
                    Component.literal("No structure template '" + templateId + "'"), true);
            return InteractionResult.FAIL;
        }
        BlockPos origin = context.getClickedPos().relative(context.getClickedFace());
        Rotation rotation = rotation(stack);
        String error = SiteValidator.validate(level, ConstructionLedger.get(level),
                blueprint, origin, rotation);
        if (error != null) {
            player.displayClientMessage(Component.literal(
                    "Site refused: " + error + " (/dv build add ... force overrides)"), true);
            return InteractionResult.FAIL;
        }
        ConstructionLedger.ConstructionSite site = ConstructionLedger.get(level)
                .addSite(templateId, origin, rotation, level.getGameTime());
        VillagerEssence essence = VillagerEssence.get(villager);
        if (essence.getRole() != VillagerRole.BUILDER) {
            essence.setRole(VillagerRole.BUILDER);
        }
        essence.setAssignedSiteId(site.id());
        player.displayClientMessage(Component.literal(
                "Site #%d: %s at %s (%s) — %s will build it".formatted(
                        site.id(), templateId, origin.toShortString(),
                        rotation.name().toLowerCase(java.util.Locale.ROOT),
                        villager.getName().getString())), true);
        return InteractionResult.CONSUME;
    }

    private static ResourceLocation templateId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            ResourceLocation stored = ResourceLocation.tryParse(tag.getString(TEMPLATE_KEY));
            if (stored != null && !tag.getString(TEMPLATE_KEY).isEmpty()) {
                return stored;
            }
        }
        return DEFAULT_TEMPLATE;
    }

    private static Rotation rotation(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            try {
                return Rotation.valueOf(data.copyTag().getString(ROTATION_KEY));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Rotation.NONE;
    }
}
