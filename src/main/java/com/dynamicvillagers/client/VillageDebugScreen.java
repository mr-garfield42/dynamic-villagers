package com.dynamicvillagers.client;

import com.dynamicvillagers.network.VillageDebugStatePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class VillageDebugScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 190;
    private final VillageDebugStatePayload state;

    public VillageDebugScreen(VillageDebugStatePayload state) {
        super(Component.literal(state.name()));
        this.state = state;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101018);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3C3C50);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x = (width - PANEL_WIDTH) / 2 + 10;
        int y = (height - PANEL_HEIGHT) / 2 + 10;
        graphics.drawString(font, state.name() + "  (#" + state.villageId() + ")", x, y, 0xFFFFFFFF);
        graphics.drawString(font, "Center " + state.center() + "  radius " + state.radius(), x, y + 14, 0xFFA0A0B0);
        graphics.drawString(font, "Population %d — %d adults, %d children".formatted(
                state.population(), state.adults(), state.children()), x, y + 30, 0xFFFFFFFF);
        graphics.drawString(font, "Beds %d (%d free)  houses %d".formatted(
                state.beds(), state.freeBeds(), state.houses()), x, y + 44, 0xFFFFFFFF);
        graphics.drawString(font, "Guards %d  public storage %d  open sites %d".formatted(
                state.guards(), state.publicStorage(), state.openSites()), x, y + 58, 0xFFFFFFFF);
        graphics.drawString(font, "Roles: " + (state.roles().isEmpty() ? "none" : String.join(", ", state.roles())),
                x, y + 76, 0xFFA0A0B0);
        graphics.drawString(font, "Autostaff " + (state.autoStaff() ? "on" : "off"), x, y + 90, 0xFFA0A0B0);
        graphics.drawString(font, "Construction", x, y + 106, 0xFFFFFFFF);
        int line = y + 120;
        if (state.siteProgress().isEmpty()) {
            graphics.drawString(font, "No open sites", x, line, 0xFFA0A0B0);
        } else {
            for (String site : state.siteProgress()) {
                graphics.drawString(font, font.plainSubstrByWidth(site, PANEL_WIDTH - 20), x, line, 0xFFA0A0B0);
                line += 12;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
