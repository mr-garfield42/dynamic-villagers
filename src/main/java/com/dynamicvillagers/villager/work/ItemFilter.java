package com.dynamicvillagers.villager.work;

import com.dynamicvillagers.DynamicVillagers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.neoforged.neoforge.common.Tags;

import java.util.List;
import java.util.function.Predicate;

/**
 * Tiny string DSL for selecting items in task parameters, so tasks stay NBT-serializable:
 * "any", "food", "axe", "sapling", "item:&lt;id&gt;", "tag:&lt;id&gt;". Unknown specs match
 * nothing (and warn) rather than grabbing arbitrary items.
 */
public final class ItemFilter {

    public static Predicate<ItemStack> parse(String spec) {
        if (spec.isEmpty() || spec.equals("any")) {
            return stack -> !stack.isEmpty();
        }
        if (spec.equals("food")) {
            return stack -> stack.has(DataComponents.FOOD);
        }
        if (spec.equals("axe")) {
            return stack -> stack.getItem() instanceof AxeItem;
        }
        if (spec.equals("hoe")) {
            return stack -> stack.getItem() instanceof HoeItem;
        }
        if (spec.equals("pickaxe")) {
            return stack -> stack.getItem() instanceof PickaxeItem;
        }
        if (spec.equals("shovel")) {
            return stack -> stack.getItem() instanceof ShovelItem;
        }
        if (spec.equals("sapling")) {
            return stack -> stack.is(ItemTags.SAPLINGS);
        }
        if (spec.equals("seeds")) {
            return stack -> stack.is(Tags.Items.SEEDS);
        }
        if (spec.equals("scaffold")) { // throwaway building blocks a builder may climb on
            return stack -> stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE);
        }
        if (spec.startsWith("item:")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring("item:".length()));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                Item item = BuiltInRegistries.ITEM.get(id);
                return stack -> stack.is(item);
            }
        } else if (spec.startsWith("tag:")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring("tag:".length()));
            if (id != null) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
                return stack -> stack.is(tag);
            }
        }
        DynamicVillagers.LOGGER.warn("Unknown item filter '{}' matches nothing", spec);
        return stack -> false;
    }

    /** OR of several specs; an empty list matches nothing. */
    public static Predicate<ItemStack> parseAny(List<String> specs) {
        List<Predicate<ItemStack>> parsed = specs.stream().map(ItemFilter::parse).toList();
        return stack -> parsed.stream().anyMatch(p -> p.test(stack));
    }

    private ItemFilter() {
    }
}
