package com.dynamicvillagers.command;

import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import com.dynamicvillagers.villager.task.BreakBlockTask;
import com.dynamicvillagers.villager.task.DepositToContainerTask;
import com.dynamicvillagers.villager.task.GoToTask;
import com.dynamicvillagers.villager.task.PickUpItemsTask;
import com.dynamicvillagers.villager.task.PlaceBlockTask;
import com.dynamicvillagers.villager.task.TakeItemsTask;
import com.dynamicvillagers.villager.task.Task;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
                .then(Commands.literal("role")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.Arrays.stream(VillagerRole.values()).map(VillagerRole::lowerName), builder))
                                        .executes(DVCommands::setRole))))
                .then(Commands.literal("minesite")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.literal("clear")
                                        .executes(DVCommands::clearMineSite))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("facing", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        java.util.List.of("north", "south", "east", "west"), builder))
                                                .executes(DVCommands::setMineSite)))))
                .then(Commands.literal("quarry")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.literal("clear")
                                        .executes(DVCommands::clearQuarry))
                                .then(Commands.argument("corner1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("corner2", BlockPosArgument.blockPos())
                                                .executes(DVCommands::setQuarry)))))
                .then(Commands.literal("take")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .then(Commands.argument("filter", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(ctx -> enqueue(ctx, new TakeItemsTask(
                                                        StringArgumentType.getString(ctx, "filter"),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))))
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
                "%s | role %s | hunger %d/%d | %d tasks | knows %d containers, %d trees%s%s".formatted(
                        villager.getName().getString(), essence.getRole().lowerName(),
                        essence.getHunger(), VillagerEssence.MAX_HUNGER,
                        essence.getTaskQueue().size(), essence.getMemory().knownContainers().size(),
                        essence.getMemory().knownSpots("tree").size(),
                        essence.getMineSite() != null ? " | mine site" : "",
                        essence.getQuarrySite() != null ? " | quarry" : "")), false);
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

    private static int setRole(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        String name = StringArgumentType.getString(ctx, "role");
        VillagerRole role = VillagerRole.byName(name);
        if (role == null) {
            ctx.getSource().sendFailure(Component.literal("unknown role '" + name + "'"));
            return 0;
        }
        VillagerEssence.get(villager).setRole(role);
        ctx.getSource().sendSuccess(() -> Component.literal("role set to " + role.lowerName()), false);
        return 1;
    }

    private static int setMineSite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        String name = StringArgumentType.getString(ctx, "facing");
        Direction direction = Direction.byName(name);
        if (direction == null || direction.getAxis().isVertical()) {
            ctx.getSource().sendFailure(Component.literal("facing must be north/south/east/west"));
            return 0;
        }
        VillagerEssence.get(villager).setMineSite(new VillagerEssence.MineSite(pos, direction));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "mine site set: tunnel from %s heading %s".formatted(pos.toShortString(), name)), false);
        return 1;
    }

    private static int clearMineSite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence.get(villager).setMineSite(null);
        ctx.getSource().sendSuccess(() -> Component.literal("mine site cleared"), false);
        return 1;
    }

    private static int setQuarry(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        BlockPos a = BlockPosArgument.getLoadedBlockPos(ctx, "corner1");
        BlockPos b = BlockPosArgument.getLoadedBlockPos(ctx, "corner2");
        if (Math.abs(a.getX() - b.getX()) > 32 || Math.abs(a.getZ() - b.getZ()) > 32) {
            ctx.getSource().sendFailure(Component.literal("quarry sides are limited to 32 blocks"));
            return 0;
        }
        VillagerEssence.get(villager).setQuarrySite(new VillagerEssence.QuarrySite(a, b));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "quarry set: %s to %s (depth is capped by the ramp wall length)".formatted(
                        a.toShortString(), b.toShortString())), false);
        return 1;
    }

    private static int clearQuarry(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        VillagerEssence.get(villager).setQuarrySite(null);
        ctx.getSource().sendSuccess(() -> Component.literal("quarry cleared"), false);
        return 1;
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
