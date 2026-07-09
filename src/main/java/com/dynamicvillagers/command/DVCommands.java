package com.dynamicvillagers.command;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.work.BreakBlockOrder;
import com.dynamicvillagers.villager.work.PlaceBlockOrder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class DVCommands {

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dv")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(DVCommands::inspect)))
                .then(Commands.literal("hunger")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, VillagerEssence.MAX_HUNGER))
                                        .executes(DVCommands::setHunger))))
                .then(Commands.literal("break")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(DVCommands::orderBreak))))
                .then(Commands.literal("place")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(DVCommands::orderPlace)))));
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence essence = VillagerEssence.get(villager);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "%s | hunger %d/%d".formatted(villager.getName().getString(), essence.getHunger(), VillagerEssence.MAX_HUNGER)), false);
        sendInventory(ctx, "vanilla", villager.getInventory());
        sendInventory(ctx, "extra", essence.getExtraInventory());
        return 1;
    }

    private static void sendInventory(CommandContext<CommandSourceStack> ctx, String label, SimpleContainer container) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                items.append(" [%d] %dx %s".formatted(i, stack.getCount(), stack.getItem()));
            }
        }
        String line = label + " (" + container.getContainerSize() + " slots):" + (items.isEmpty() ? " empty" : items);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
    }

    private static int setHunger(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        int value = IntegerArgumentType.getInteger(ctx, "value");
        VillagerEssence.get(villager).setHunger(value);
        ctx.getSource().sendSuccess(() -> Component.literal("hunger set to " + value), false);
        return 1;
    }

    private static int orderBreak(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        VillagerEssence.get(villager).setCurrentWork(new BreakBlockOrder(pos));
        ctx.getSource().sendSuccess(() -> Component.literal("break ordered at " + pos.toShortString()), false);
        return 1;
    }

    private static int orderPlace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        VillagerEssence.get(villager).setCurrentWork(new PlaceBlockOrder(pos));
        ctx.getSource().sendSuccess(() -> Component.literal("place ordered at " + pos.toShortString()), false);
        return 1;
    }

    private static Villager requireVillager(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        if (entity instanceof Villager villager) {
            return villager;
        }
        throw new IllegalArgumentException("target is not a villager");
    }

    private DVCommands() {
    }
}
