package com.dynamicvillagers.command;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.GoToTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.Task;
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
                                        .executes(ctx -> enqueue(ctx, new BreakBlockTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))))
                .then(Commands.literal("place")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> enqueue(ctx, new PlaceBlockTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))))
                .then(Commands.literal("goto")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> enqueue(ctx, new GoToTask(BlockPosArgument.getLoadedBlockPos(ctx, "pos"), 2))))))
                .then(Commands.literal("deposit")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> enqueue(ctx, new DepositToContainerTask()))))
                .then(Commands.literal("pickup")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> enqueue(ctx, new PickUpItemsTask(requireVillager(ctx).blockPosition(), 8.0)))))
                .then(Commands.literal("tasks")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(DVCommands::listTasks)))
                .then(Commands.literal("cleartasks")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(DVCommands::clearTasks))));
    }

    private static int enqueue(CommandContext<CommandSourceStack> ctx, Task task) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence.get(villager).getTaskQueue().enqueue(task);
        ctx.getSource().sendSuccess(() -> Component.literal("queued " + task.typeId()), false);
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence essence = VillagerEssence.get(villager);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "%s | hunger %d/%d | %d tasks | knows %d containers".formatted(
                        villager.getName().getString(), essence.getHunger(), VillagerEssence.MAX_HUNGER,
                        essence.getTaskQueue().size(), essence.getMemory().knownContainers().size())), false);
        sendInventory(ctx, "vanilla", villager.getInventory());
        sendInventory(ctx, "extra", essence.getExtraInventory());
        return 1;
    }

    private static int listTasks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence essence = VillagerEssence.get(villager);
        StringBuilder line = new StringBuilder("tasks:");
        for (Task task : essence.getTaskQueue().tasks()) {
            line.append(' ').append(task.typeId());
        }
        String text = essence.getTaskQueue().isEmpty() ? "tasks: none" : line.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int clearTasks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence.get(villager).getTaskQueue().clear();
        ctx.getSource().sendSuccess(() -> Component.literal("task queue cleared"), false);
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
