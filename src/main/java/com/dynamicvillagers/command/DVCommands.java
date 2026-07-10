package com.dynamicvillagers.command;

import com.dynamicvillagers.construction.Blueprint;
import com.dynamicvillagers.construction.Blueprints;
import com.dynamicvillagers.registry.DVTags;
import com.dynamicvillagers.village.ConstructionLedger;
import com.dynamicvillagers.village.StorageLedger;
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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

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
                .then(Commands.literal("storage")
                        .then(Commands.literal("list")
                                .executes(DVCommands::listStorage))
                        .then(Commands.literal("public")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> designateStorage(ctx,
                                                StorageLedger.Designation.PUBLIC, null))))
                        .then(Commands.literal("private")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("target", EntityArgument.entity())
                                                .executes(ctx -> designateStorage(ctx,
                                                        StorageLedger.Designation.PRIVATE, requireVillager(ctx))))))
                        .then(Commands.literal("unclaim")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> designateStorage(ctx,
                                                StorageLedger.Designation.UNCLAIMED, null)))))
                .then(Commands.literal("request")
                        .then(Commands.literal("add")
                                .then(Commands.argument("filter", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("deliverTo", BlockPosArgument.blockPos())
                                                        .executes(DVCommands::addRequest)))))
                        .then(Commands.literal("list")
                                .executes(DVCommands::listRequests))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(DVCommands::cancelRequest))))
                .then(Commands.literal("build")
                        .then(Commands.literal("add")
                                .then(Commands.argument("template", ResourceLocationArgument.id())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                                ctx.getSource().getServer().getStructureManager().listTemplates(), builder))
                                        .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                                .executes(ctx -> addBuildSite(ctx, Rotation.NONE))
                                                .then(Commands.argument("rotation", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                java.util.List.of("none", "cw90", "cw180", "ccw90"), builder))
                                                        .executes(DVCommands::addBuildSiteRotated)))))
                        .then(Commands.literal("list")
                                .executes(DVCommands::listBuildSites))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(DVCommands::cancelBuildSite)))
                        .then(Commands.literal("info")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(DVCommands::buildSiteInfo)))
                        .then(Commands.literal("assign")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                                .executes(DVCommands::assignBuilder)))))
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
                "%s | role %s | hunger %d/%d | %d tasks | knows %d containers, %d trees%s%s%s".formatted(
                        villager.getName().getString(), essence.getRole().lowerName(),
                        essence.getHunger(), VillagerEssence.MAX_HUNGER,
                        essence.getTaskQueue().size(), essence.getMemory().knownContainers().size(),
                        essence.getMemory().knownSpots("tree").size(),
                        essence.getMineSite() != null ? " | mine site" : "",
                        essence.getQuarrySite() != null ? " | quarry" : "",
                        essence.getAssignedSiteId() != -1 ? " | build site #" + essence.getAssignedSiteId() : "")), false);
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

    private static int listStorage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos center = BlockPos.containing(ctx.getSource().getPosition());
        StorageLedger ledger = StorageLedger.get(level);
        var records = ledger.recordsNear(center, StorageLedger.NETWORK_RANGE);
        if (records.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "no storage records within " + StorageLedger.NETWORK_RANGE + " blocks"), false);
            return 0;
        }
        long now = level.getGameTime();
        for (var entry : records) {
            StorageLedger.ContainerRecord record = entry.getValue();
            StringBuilder contents = new StringBuilder();
            for (ItemStack stack : record.contents()) {
                contents.append(" %dx %s".formatted(stack.getCount(), stack.getItem()));
            }
            String line = "%s [%s%s] %s%s%s".formatted(
                    entry.getKey().toShortString(),
                    record.designation().name().toLowerCase(java.util.Locale.ROOT),
                    record.owner() != null ? " owner " + record.owner().toString().substring(0, 8) : "",
                    record.lastInspected() < 0 ? "never opened"
                            : record.contents().isEmpty() ? "empty" : contents.toString().trim(),
                    record.lastInspected() < 0 ? "" : " (seen %d ticks ago)".formatted(now - record.lastInspected()),
                    record.reservations().isEmpty() ? "" : " | %d reservation(s)".formatted(record.reservations().size()));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return records.size();
    }

    private static int designateStorage(CommandContext<CommandSourceStack> ctx,
                                        StorageLedger.Designation designation,
                                        @Nullable Villager owner) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        if (designation != StorageLedger.Designation.UNCLAIMED
                && !level.getBlockState(pos).is(DVTags.STORAGE_CONTAINERS)) {
            ctx.getSource().sendFailure(Component.literal(
                    pos.toShortString() + " is not a storage container (chest or barrel)"));
            return 0;
        }
        StorageLedger.get(level).setDesignation(pos, designation,
                owner != null ? owner.getUUID() : null);
        String label = switch (designation) {
            case PUBLIC -> "public village storage";
            case PRIVATE -> "private storage of " + owner.getName().getString();
            case UNCLAIMED -> "unclaimed";
        };
        ctx.getSource().sendSuccess(() -> Component.literal(
                pos.toShortString() + " is now " + label), false);
        return 1;
    }

    private static int addRequest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos deliverTo = BlockPosArgument.getLoadedBlockPos(ctx, "deliverTo");
        if (!level.getBlockState(deliverTo).is(DVTags.STORAGE_CONTAINERS)) {
            ctx.getSource().sendFailure(Component.literal(
                    deliverTo.toShortString() + " is not a storage container (chest or barrel)"));
            return 0;
        }
        String filter = StringArgumentType.getString(ctx, "filter");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        StorageLedger.MaterialRequest request = StorageLedger.get(level)
                .addRequest(filter, count, deliverTo, level.getGameTime());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "request #%d posted: %dx %s to %s".formatted(
                        request.id(), count, filter, deliverTo.toShortString())), false);
        return request.id();
    }

    private static int listRequests(CommandContext<CommandSourceStack> ctx) {
        StorageLedger ledger = StorageLedger.get(ctx.getSource().getLevel());
        var requests = ledger.allRequests();
        if (requests.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("no open requests"), false);
            return 0;
        }
        for (StorageLedger.MaterialRequest request : requests) {
            String line = "#%d: %dx %s to %s".formatted(
                    request.id(), request.remaining(), request.filter(),
                    request.deliverTo().toShortString());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return requests.size();
    }

    private static int cancelRequest(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (StorageLedger.get(ctx.getSource().getLevel()).cancelRequest(id)) {
            ctx.getSource().sendSuccess(() -> Component.literal("request #" + id + " cancelled"), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("no request #" + id));
        return 0;
    }

    private static int addBuildSiteRotated(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "rotation");
        Rotation rotation = switch (name) {
            case "none" -> Rotation.NONE;
            case "cw90" -> Rotation.CLOCKWISE_90;
            case "cw180" -> Rotation.CLOCKWISE_180;
            case "ccw90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> null;
        };
        if (rotation == null) {
            ctx.getSource().sendFailure(Component.literal("rotation must be none/cw90/cw180/ccw90"));
            return 0;
        }
        return addBuildSite(ctx, rotation);
    }

    private static int addBuildSite(CommandContext<CommandSourceStack> ctx, Rotation rotation) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        ResourceLocation templateId = ResourceLocationArgument.getId(ctx, "template");
        BlockPos origin = BlockPosArgument.getLoadedBlockPos(ctx, "origin");
        Blueprint blueprint = Blueprints.load(level, templateId);
        if (blueprint == null) {
            ctx.getSource().sendFailure(Component.literal("no structure template '" + templateId + "'"));
            return 0;
        }
        ConstructionLedger.ConstructionSite site = ConstructionLedger.get(level)
                .addSite(templateId, origin, rotation, level.getGameTime());
        var size = blueprint.size(rotation);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "site #%d: %s at %s (%s), %dx%dx%d, %d planned blocks%s".formatted(
                        site.id(), templateId, origin.toShortString(), rotation.name().toLowerCase(java.util.Locale.ROOT),
                        size.getX(), size.getY(), size.getZ(), blueprint.blocks().size(),
                        blueprint.unbuildableCount() > 0
                                ? " — WARNING: %d unbuildable".formatted(blueprint.unbuildableCount()) : "")), false);
        return site.id();
    }

    private static int listBuildSites(CommandContext<CommandSourceStack> ctx) {
        var sites = ConstructionLedger.get(ctx.getSource().getLevel()).allSites();
        if (sites.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("no construction sites"), false);
            return 0;
        }
        for (ConstructionLedger.ConstructionSite site : sites) {
            String line = "#%d: %s at %s (%s) — %s%s".formatted(
                    site.id(), site.templateId(), site.origin().toShortString(),
                    site.rotation().name().toLowerCase(java.util.Locale.ROOT),
                    site.status().name().toLowerCase(java.util.Locale.ROOT),
                    site.staging() != null ? ", staging " + site.staging().toShortString() : "");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return sites.size();
    }

    private static int cancelBuildSite(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (ConstructionLedger.get(ctx.getSource().getLevel()).cancelSite(id)) {
            ctx.getSource().sendSuccess(() -> Component.literal("site #" + id + " cancelled"), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("no site #" + id));
        return 0;
    }

    /** The shortfall report: what the blueprint needs vs. what the storage network knows of. */
    private static int buildSiteInfo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        ConstructionLedger.ConstructionSite site = ConstructionLedger.get(level).getSite(id);
        if (site == null) {
            ctx.getSource().sendFailure(Component.literal("no site #" + id));
            return 0;
        }
        Blueprint blueprint = Blueprints.load(level, site.templateId());
        if (blueprint == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "site #%d references missing template %s".formatted(id, site.templateId())));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "site #%d (%s, %s): materials vs. known storage within %d blocks".formatted(
                        id, site.templateId(), site.status().name().toLowerCase(java.util.Locale.ROOT),
                        StorageLedger.NETWORK_RANGE)), false);
        StorageLedger storage = StorageLedger.get(level);
        var records = storage.recordsNear(site.origin(), StorageLedger.NETWORK_RANGE);
        for (var entry : blueprint.requirements().entrySet()) {
            Item item = entry.getKey();
            int known = 0;
            for (var record : records) {
                known += record.getValue().count(stack -> stack.is(item));
            }
            int need = entry.getValue();
            String line = "  %s: need %d, known %d%s".formatted(
                    item, need, known, known < need ? " — SHORT " + (need - known) : "");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** The debug way to order a specific villager onto a specific site. */
    private static int assignBuilder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Villager villager = requireVillager(ctx);
        ServerLevel level = ctx.getSource().getLevel();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        ConstructionLedger.ConstructionSite site = ConstructionLedger.get(level).getSite(id);
        if (site == null) {
            ctx.getSource().sendFailure(Component.literal("no site #" + id));
            return 0;
        }
        if (site.status() == ConstructionLedger.Status.DONE) {
            ctx.getSource().sendFailure(Component.literal("site #" + id + " is already built"));
            return 0;
        }
        VillagerEssence essence = VillagerEssence.get(villager);
        essence.setRole(VillagerRole.BUILDER);
        essence.setAssignedSiteId(id);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "%s is now a builder assigned to site #%d (%s at %s)".formatted(
                        villager.getName().getString(), id, site.templateId(),
                        site.origin().toShortString())), false);
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
