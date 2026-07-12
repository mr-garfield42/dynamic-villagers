package com.dynamicvillagers.gametest;

import com.dynamicvillagers.DynamicVillagers;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.RoleProfessions;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.CookAtCampfireTask;
import com.dynamicvillagers.villager.task.KillAnimalTask;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Hunter role (owner request; wears the vanilla butcher skin). Covers the role↔profession
 * mapping, the physical kill, an end-to-end planner-driven hunt, and cooking raw meat on a
 * campfire — the kill → cook → deposit loop the roadmap slates before Phase 5.
 */
@GameTestHolder(DynamicVillagers.MOD_ID)
@PrefixGameTestTemplate(false)
public class HunterTests {

    @GameTest(template = "empty5x5")
    public static void hunter_maps_to_butcher(GameTestHelper helper) {
        helper.assertTrue(RoleProfessions.professionFor(VillagerRole.HUNTER) == VillagerProfession.BUTCHER,
                "hunter role → butcher profession (butcher skin)");
        helper.assertTrue(RoleProfessions.roleFor(VillagerProfession.BUTCHER) == VillagerRole.HUNTER,
                "butcher profession → hunter role");
        helper.succeed();
    }

    /** The kill task alone: an armed villager runs down and kills a cow. */
    @GameTest(template = "empty11x11", timeoutTicks = 600, batch = "dvHunterKill")
    public static void kill_animal_task_kills_a_cow(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 3));
        VillagerEssence.get(villager).getExtraInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(4, 2, 3));
        VillagerEssence.get(villager).getTaskQueue().enqueue(new KillAnimalTask(cow.getUUID()));
        helper.succeedWhen(() -> helper.assertTrue(!cow.isAlive(),
                "the hunter should run the cow down and kill it"));
    }

    /** End-to-end: a HUNTER-role villager plans the hunt itself and kills nearby game. */
    @GameTest(template = "empty11x11", timeoutTicks = 800, batch = "dvHunterPlan")
    public static void hunter_role_plans_and_kills(GameTestHelper helper) {
        perpetualDay(helper);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 3));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        essence.setRole(VillagerRole.HUNTER);
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(4, 2, 3));
        helper.succeedWhen(() -> helper.assertTrue(!cow.isAlive(),
                "the hunter should find and kill the cow on its own"));
    }

    /** Cooking: raw beef laid on a lit campfire comes back as cooked beef. */
    @GameTest(template = "empty11x11", timeoutTicks = 1000, batch = "dvHunterCook")
    public static void cooks_raw_beef_on_a_campfire(GameTestHelper helper) {
        perpetualDay(helper);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.CAMPFIRE); // default state is lit
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.BEEF, 2));
        essence.getTaskQueue().enqueue(new CookAtCampfireTask(helper.absolutePos(new BlockPos(3, 2, 3))));
        helper.succeedWhen(() -> helper.assertTrue(
                essence.countItems(villager, stack -> stack.is(Items.COOKED_BEEF)) >= 1,
                "the hunter should cook its raw beef into cooked beef on the campfire"));
    }

    /** End-to-end cook: a HUNTER carrying raw beef finds the campfire itself and cooks on it. */
    @GameTest(template = "empty11x11", timeoutTicks = 1000, batch = "dvHunterPlanCook")
    public static void hunter_role_cooks_on_a_found_campfire(GameTestHelper helper) {
        perpetualDay(helper);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.CAMPFIRE);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.getExtraInventory().setItem(0, new ItemStack(Items.BEEF, 2));
        essence.setRole(VillagerRole.HUNTER);
        helper.succeedWhen(() -> helper.assertTrue(
                essence.countItems(villager, stack -> stack.is(Items.COOKED_BEEF)) >= 1,
                "the hunter should locate the campfire and cook its beef without being told"));
    }

    private static void perpetualDay(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.setDayTime(6000);
    }
}
