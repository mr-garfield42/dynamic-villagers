package com.dynamicvillagers.villager.role;

import com.dynamicvillagers.village.StorageLedger;
import com.dynamicvillagers.village.VillageAnchor;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.CraftTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.work.ContainerUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Villager crafting via the vanilla {@link RecipeType#CRAFTING} recipes (owner request): a
 * villager may craft small recipes right in its inventory, but anything that needs a full 3×3
 * grid requires a crafting table — exactly the player's 2×2 hand-crafting vs. table split.
 *
 * <p>This holds the shared machinery: finding recipes that produce an item, deciding whether a
 * recipe needs a table, and consuming ingredients / producing the result against a villager's
 * combined inventory (used by {@link CraftTask}). It also plans multi-step supply chains for
 * planners ({@link #ensureItem}) — fetching raw inputs from storage and crafting intermediates
 * bottom-up (logs → planks → door), so a builder can make its own materials instead of only
 * drawing finished goods from a stocked warehouse.
 */
public final class Crafting {
    private static final int FETCH_CAP = 64;
    private static final int MAX_CHAIN_DEPTH = 4; // logs → planks → sticks → … is plenty

    /** Outcome of trying to make an item available for a planner. */
    public enum Provision {
        /** The villager already carries enough. */
        HAVE,
        /** Fetch and/or craft tasks were enqueued; check again next cycle. */
        ENQUEUED,
        /** Cannot be crafted from obtainable inputs, nor fetched — the caller should request it. */
        UNAVAILABLE
    }

    /** Every crafting recipe whose result is {@code item}. */
    public static List<RecipeHolder<CraftingRecipe>> recipesFor(ServerLevel level, Item item) {
        return level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(holder -> holder.value().getResultItem(level.registryAccess()).is(item))
                .toList();
    }

    /** True when the recipe spans more than a 2×2 grid and so needs a crafting table. */
    public static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        if (recipe instanceof ShapelessRecipe) {
            return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).count() > 4;
        }
        return true; // unknown/special recipe — be conservative and use a table
    }

    /** The first recipe for {@code item} the villager can craft right now, or null. */
    @Nullable
    public static RecipeHolder<CraftingRecipe> pickAffordableRecipe(ServerLevel level, Villager villager,
                                                                    VillagerEssence essence, Item item) {
        for (RecipeHolder<CraftingRecipe> holder : recipesFor(level, item)) {
            if (selectIngredients(villager, essence, holder.value()) != null) {
                return holder;
            }
        }
        return null;
    }

    /**
     * Consumes one set of ingredients from the villager's combined inventory and adds the
     * recipe's result. @return true if a craft happened (ingredients present and there was
     * room for the output), false otherwise — the caller loops until the wanted count is met.
     */
    public static boolean craftOnce(ServerLevel level, Villager villager, VillagerEssence essence,
                                    RecipeHolder<CraftingRecipe> holder) {
        Map<SlotKey, Integer> use = selectIngredients(villager, essence, holder.value());
        if (use == null) {
            return false;
        }
        ItemStack result = holder.value().getResultItem(level.registryAccess()).copy();
        if (!canHold(villager, essence, result)) {
            return false; // no room for the output — the planner deposits first, then retries
        }
        for (Map.Entry<SlotKey, Integer> entry : use.entrySet()) {
            entry.getKey().container.getItem(entry.getKey().slot).shrink(entry.getValue());
        }
        ItemStack remainder = ContainerUtil.insert(villager.getInventory(), result);
        remainder = ContainerUtil.insert(essence.getExtraInventory(), remainder);
        if (!remainder.isEmpty()) {
            villager.spawnAtLocation(remainder); // canHold said it fits; belt-and-braces against loss
        }
        return true;
    }

    /**
     * Plans a supply chain that ends with the villager carrying {@code count} of {@code item}:
     * fetch it (or its inputs) from storage, and craft intermediates bottom-up. Tasks are
     * enqueued in dependency order (inputs before the craft that consumes them).
     */
    public static Provision ensureItem(ServerLevel level, Villager villager, VillagerEssence essence,
                                       Item item, int count, int depth) {
        if (essence.countItems(villager, stack -> stack.is(item)) >= count) {
            return Provision.HAVE;
        }
        List<RecipeHolder<CraftingRecipe>> recipes = recipesFor(level, item);
        if (recipes.isEmpty() || depth <= 0) {
            return fetchFromNetwork(level, villager, essence, item, count) ? Provision.ENQUEUED : Provision.UNAVAILABLE;
        }
        RecipeHolder<CraftingRecipe> recipe = chooseRecipe(level, villager, essence, recipes);
        int have = essence.countItems(villager, stack -> stack.is(item));
        int perCraft = Math.max(1, recipe.value().getResultItem(level.registryAccess()).getCount());
        int crafts = (count - have + perCraft - 1) / perCraft;

        // group ingredient slots by their representative item, so shared slots (a door's six
        // plank cells) sum instead of each independently thinking one plank is enough
        Map<Item, Integer> required = new LinkedHashMap<>();
        Map<Item, Ingredient> byRep = new HashMap<>();
        for (Ingredient ingredient : recipe.value().getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            Item rep = representative(ingredient);
            if (rep == null) {
                return fetchFromNetwork(level, villager, essence, item, count)
                        ? Provision.ENQUEUED : Provision.UNAVAILABLE; // an ingredient with no obtainable item
            }
            required.merge(rep, crafts, Integer::sum);
            byRep.put(rep, ingredient);
        }

        boolean enqueuedSub = false;
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Ingredient ingredient = byRep.get(entry.getKey());
            int owned = essence.countItems(villager, ingredient);
            if (owned >= entry.getValue()) {
                continue; // already covered by what's carried
            }
            Provision sub = ensureItem(level, villager, essence, entry.getKey(), entry.getValue(), depth - 1);
            if (sub == Provision.UNAVAILABLE) {
                // can't obtain an ingredient — see if the finished item is just sitting in storage
                return fetchFromNetwork(level, villager, essence, item, count)
                        ? Provision.ENQUEUED : Provision.UNAVAILABLE;
            }
            if (sub == Provision.ENQUEUED) {
                enqueuedSub = true;
            }
        }
        // the craft that produces `item` runs after the ingredient tasks queued above
        essence.getTaskQueue().enqueue(new CraftTask(item, count, needsTable(recipe.value())));
        return Provision.ENQUEUED;
    }

    /** Enqueues a fetch if the storage network is known to hold the item; else false. */
    private static boolean fetchFromNetwork(ServerLevel level, Villager villager,
                                            VillagerEssence essence, Item item, int count) {
        if (essence.getMemory().knownContainers().isEmpty()) {
            return false;
        }
        StorageLedger ledger = StorageLedger.get(level);
        if (ledger.findSource(VillageAnchor.resolve(level, villager), villager.blockPosition(),
                villager.getUUID(), stack -> stack.is(item), level.getGameTime(), Set.of()) == null) {
            return false;
        }
        int have = essence.countItems(villager, stack -> stack.is(item));
        essence.getTaskQueue().enqueue(new TakeItemsTask(
                "item:" + BuiltInRegistries.ITEM.getKey(item), Math.min(FETCH_CAP, count - have)));
        return true;
    }

    /** Prefer a recipe craftable from what's carried now; otherwise the first candidate. */
    private static RecipeHolder<CraftingRecipe> chooseRecipe(ServerLevel level, Villager villager,
                                                             VillagerEssence essence,
                                                             List<RecipeHolder<CraftingRecipe>> recipes) {
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            if (selectIngredients(villager, essence, holder.value()) != null) {
                return holder;
            }
        }
        return recipes.getFirst();
    }

    @Nullable
    private static Item representative(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return items.length > 0 ? items[0].getItem() : null;
    }

    /**
     * Picks one inventory slot per non-empty ingredient without using the same item twice,
     * returning the per-slot consumption map — or null when the recipe can't be afforded.
     */
    @Nullable
    private static Map<SlotKey, Integer> selectIngredients(Villager villager, VillagerEssence essence,
                                                           CraftingRecipe recipe) {
        SimpleContainer[] containers = {villager.getInventory(), essence.getExtraInventory()};
        Map<SlotKey, Integer> used = new HashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            if (!claimOne(containers, ingredient, used)) {
                return null;
            }
        }
        return used;
    }

    private static boolean claimOne(SimpleContainer[] containers, Ingredient ingredient,
                                    Map<SlotKey, Integer> used) {
        for (SimpleContainer container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !ingredient.test(stack)) {
                    continue;
                }
                SlotKey key = new SlotKey(container, i);
                if (stack.getCount() - used.getOrDefault(key, 0) >= 1) {
                    used.merge(key, 1, Integer::sum);
                    return true;
                }
            }
        }
        return false;
    }

    /** Room enough in the combined inventory for the crafted result. */
    private static boolean canHold(Villager villager, VillagerEssence essence, ItemStack result) {
        int need = result.getCount();
        for (SimpleContainer container : new SimpleContainer[]{villager.getInventory(), essence.getExtraInventory()}) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    return true; // an empty slot holds a full craft result
                }
                if (ItemStack.isSameItemSameComponents(stack, result)) {
                    need -= stack.getMaxStackSize() - stack.getCount();
                    if (need <= 0) {
                        return true;
                    }
                }
            }
        }
        return need <= 0;
    }

    private record SlotKey(SimpleContainer container, int slot) {
    }

    private Crafting() {
    }
}
