package com.dynamicvillagers.client;

import com.dynamicvillagers.network.VillagerDebugActionPayload;
import com.dynamicvillagers.network.VillagerDebugRequestPayload;
import com.dynamicvillagers.network.VillagerDebugStatePayload;
import com.dynamicvillagers.villager.VillagerEssence;
import com.dynamicvillagers.villager.role.VillagerRole;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Debug panel for one villager: hunger, role, task queue, memory counts, and the combined
 * inventory, plus buttons for the common debug actions. Read-only view fed by server
 * snapshots (polled every second while open); buttons send action payloads.
 */
public class VillagerDebugScreen extends Screen {
    private static final int PANEL_WIDTH = 252;
    private static final int PANEL_HEIGHT = 196;
    private static final int SLOT_SIZE = 18;
    private static final int SLOTS_PER_ROW = 9;
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private VillagerDebugStatePayload state;
    private Button roleButton;
    private int ticksSinceRefresh;

    public VillagerDebugScreen(VillagerDebugStatePayload state) {
        super(Component.literal("Villager Debug"));
        this.state = state;
    }

    public int entityId() {
        return state.entityId();
    }

    public void update(VillagerDebugStatePayload payload) {
        this.state = payload;
        if (roleButton != null) {
            roleButton.setMessage(roleLabel());
        }
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int top = panelTop();
        int row1 = top + PANEL_HEIGHT - 76;
        int row2 = top + PANEL_HEIGHT - 52;
        int row3 = top + PANEL_HEIGHT - 28;

        roleButton = addRenderableWidget(Button.builder(roleLabel(), button -> cycleRole())
                .bounds(left + 8, row1, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear Tasks"), button -> sendAction("clear_tasks", ""))
                .bounds(left + 152, row1, 92, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Deposit"), button -> sendAction("deposit", ""))
                .bounds(left + 8, row2, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Pick Up"), button -> sendAction("pickup", ""))
                .bounds(left + 88, row2, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Replan"), button -> sendAction("replan", ""))
                .bounds(left + 168, row2, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Hunger -5"), button -> sendAction("hunger", "-5"))
                .bounds(left + 8, row3, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Hunger +5"), button -> sendAction("hunger", "5"))
                .bounds(left + 88, row3, 76, 20).build());
    }

    @Override
    public void tick() {
        if (++ticksSinceRefresh >= REFRESH_INTERVAL_TICKS) {
            ticksSinceRefresh = 0;
            PacketDistributor.sendToServer(new VillagerDebugRequestPayload(state.entityId()));
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = panelLeft();
        int top = panelTop();
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101018);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3C3C50);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = panelLeft();
        int top = panelTop();

        graphics.drawString(font, "Villager #%d   hunger %d/%d".formatted(
                state.entityId(), state.hunger(), VillagerEssence.MAX_HUNGER), left + 8, top + 8, 0xFFFFFFFF);
        graphics.drawString(font, "Knows %d containers, %d trees".formatted(
                state.knownContainers(), state.knownTrees()), left + 8, top + 20, 0xFFA0A0B0);
        graphics.drawString(font, taskLine(), left + 8, top + 32, 0xFFA0A0B0);

        renderInventory(graphics, left + 8, top + 46);
    }

    private void renderInventory(GuiGraphics graphics, int x, int y) {
        int index = 0;
        for (List<ItemStack> inventory : List.of(state.vanillaInventory(), state.extraInventory())) {
            for (ItemStack stack : inventory) {
                int slotX = x + (index % SLOTS_PER_ROW) * SLOT_SIZE;
                int slotY = y + (index / SLOTS_PER_ROW) * SLOT_SIZE;
                graphics.fill(slotX, slotY, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, 0x50FFFFFF);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, slotX, slotY);
                    graphics.renderItemDecorations(font, stack, slotX, slotY);
                }
                index++;
            }
        }
    }

    private String taskLine() {
        if (state.tasks().isEmpty()) {
            return "Tasks: none";
        }
        String line = "Tasks: " + String.join(", ", state.tasks());
        return font.width(line) > PANEL_WIDTH - 16
                ? font.plainSubstrByWidth(line, PANEL_WIDTH - 24) + "…"
                : line;
    }

    private Component roleLabel() {
        return Component.literal("Role: " + state.role());
    }

    private void cycleRole() {
        VillagerRole current = VillagerRole.byName(state.role());
        VillagerRole[] values = VillagerRole.values();
        int next = current == null ? 0 : (current.ordinal() + 1) % values.length;
        sendAction("set_role", values[next].lowerName());
    }

    private void sendAction(String action, String arg) {
        PacketDistributor.sendToServer(new VillagerDebugActionPayload(state.entityId(), action, arg));
    }

    private int panelLeft() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int panelTop() {
        return (height - PANEL_HEIGHT) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the villager must keep acting while we watch it
    }
}
