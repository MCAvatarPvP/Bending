package com.projectkorra.projectkorra.fabric.client.config;

import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Small native screen exposed by the optional Mod Menu API integration. */
public final class ProjectKorraConfigScreen extends Screen {
    private static final int TEXT_WHITE = 0xFFFFFF;
    private static final int TEXT_DIM = 0xA0A0A0;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;

    private final Screen parent;
    private ButtonWidget visualsButton;
    private int titleY;
    private int bendingDescriptionY;
    private int visualsDescriptionY;

    public ProjectKorraConfigScreen(final Screen parent) {
        super(Text.translatable("projectkorra.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Title, then two sections of [two description lines, toggle button], then Done. Gaps shrink
        // on short GUI heights so every element stays visible without overlapping.
        final int sectionHeight = LINE_HEIGHT * 2 + 4 + BUTTON_HEIGHT;
        final int contentHeight = LINE_HEIGHT + sectionHeight * 2 + BUTTON_HEIGHT;
        final int gap = Math.max(4, Math.min(16, (height - 8 - contentHeight) / 4));
        int y = Math.max(4, (height - contentHeight - gap * 3) / 2);

        titleY = y;
        y += LINE_HEIGHT + gap;
        bendingDescriptionY = y;
        y += LINE_HEIGHT * 2 + 4;
        final int bendingButtonY = y;
        y += BUTTON_HEIGHT + gap;
        visualsDescriptionY = y;
        y += LINE_HEIGHT * 2 + 4;
        final int visualsButtonY = y;
        y += BUTTON_HEIGHT + gap;
        final int doneY = y;

        final int buttonX = width / 2 - BUTTON_WIDTH / 2;
        addDrawableChild(ButtonWidget.builder(bendingText(), button -> {
            final boolean enabled = !ClientBendingConfig.isEnabled();
            ClientBendingConfig.setEnabled(enabled);
            PredictionClient.onClientSideBendingSettingChanged(enabled);
            button.setMessage(bendingText());
            refreshVisualsButton();
        }).dimensions(buttonX, bendingButtonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        visualsButton = addDrawableChild(ButtonWidget.builder(visualsText(), button -> {
            final boolean enabled = !ClientBendingConfig.isVisualsEnabled();
            ClientBendingConfig.setVisualsEnabled(enabled);
            PredictionClient.onClientSideVisualsSettingChanged(enabled);
            button.setMessage(visualsText());
        }).dimensions(buttonX, visualsButtonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        refreshVisualsButton();
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(buttonX, doneY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /** Visuals only matter while client-side bending is on. */
    private void refreshVisualsButton() {
        if (visualsButton == null) return;
        visualsButton.active = ClientBendingConfig.isEnabled();
        visualsButton.setMessage(visualsText());
    }

    private static Text bendingText() {
        return toggleText("projectkorra.config.client_side_bending", ClientBendingConfig.isEnabled());
    }

    private static Text visualsText() {
        return toggleText("projectkorra.config.client_side_visuals", ClientBendingConfig.isVisualsEnabled());
    }

    private static Text toggleText(final String key, final boolean on) {
        return Text.translatable(key, Text.translatable(on ? "options.on" : "options.off"));
    }

    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        super.render(context, mouseX, mouseY, delta);
        final int centerX = width / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, centerX, titleY, TEXT_WHITE);
        drawDescription(context, "projectkorra.config.client_side_bending", bendingDescriptionY);
        drawDescription(context, "projectkorra.config.client_side_visuals", visualsDescriptionY);
    }

    private void drawDescription(final DrawContext context, final String key, final int y) {
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(key + ".description.1"),
                width / 2, y, TEXT_DIM);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable(key + ".description.2"),
                width / 2, y + LINE_HEIGHT, TEXT_DIM);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
