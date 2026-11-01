package com.example.guildquesthud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuildQuestHud implements ClientModInitializer {

    // ===== Settings you can change =====
    private static final String COMMAND = "guild quests";        // command to run (no slash)
    private static final String SCREEN_TITLE = "guild quests";   // menu title to look for
    private static final int AUTO_REFRESH_SECONDS = 60;          // 0 = never auto-refresh
    private static final boolean REFRESH_ON_QUEST_MESSAGES = true; // refresh when the server announces a quest finished / new quests
    private static final boolean HIDE_QUEST_MENU = true;         // true = read the menu invisibly (no flash, no interruption)
    private static final int DEFAULT_X = 6;                      // default distance from left edge
    private static final int DEFAULT_Y = 6;                      // default distance from top edge
    // ===================================

    private static final Pattern PROGRESS =
            Pattern.compile("Progress:\\s*([\\d,]+)\\s*/\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    private record Quest(String task, int current, int total) {
        boolean done() {
            return total > 0 && current >= total;
        }
    }

    private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*([dhms])");

    private static KeyBinding refreshKey;
    private static KeyBinding toggleKey;
    private static KeyBinding editKey;

    private static int hudX = DEFAULT_X;
    private static int hudY = DEFAULT_Y;
    private static int panelW = 100;
    private static int panelH = 40;

    private static final List<Quest> SAMPLE = List.of(
            new Quest("Farm 3,000 Glowstone", 1200, 3000),
            new Quest("Kill 500 Slimes", 500, 500),
            new Quest("Play for 30 minutes", 11, 30));

    private static boolean showHud = true;
    private static boolean waiting = false;
    private static boolean autoEnabled = false;
    private static int waitTicks = 0;
    private static int screenSeenTicks = 0;
    private static int sinceRefresh = 0;
    private static List<Quest> quests = new ArrayList<>();
    private static long resetAtMillis = 0;      // when new quests become available
    private static boolean expiredHandled = false;
    private static GenericContainerScreenHandler hiddenHandler = null; // quest menu opened invisibly
    private static int pendingRefreshTicks = -1;   // countdown to a refresh triggered by a chat message

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("guildquesthud", "main"));
        refreshKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.guildquesthud.refresh", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.guildquesthud.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
        editKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.guildquesthud.edit", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(GuildQuestHud::onTick);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.of("guildquesthud", "quest_hud"),
                (ctx, tickCounter) -> renderHud(ctx));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            waiting = false;
            autoEnabled = false;
            sinceRefresh = 0;
            quests = new ArrayList<>();
            resetAtMillis = 0;
            expiredHandled = false;
            hiddenHandler = null;
            pendingRefreshTicks = -1;
        });

        ClientReceiveMessageEvents.GAME.register(GuildQuestHud::onGameMessage);
    }

    private static void onTick(MinecraftClient mc) {
        if (mc.player == null) {
            return;
        }

        while (toggleKey.wasPressed()) {
            showHud = !showHud;
        }
        while (refreshKey.wasPressed()) {
            requestRefresh(mc, true);
        }
        while (editKey.wasPressed()) {
            mc.setScreen(new PositionScreen());
        }

        if (pendingRefreshTicks > 0) {
            pendingRefreshTicks--;
        }
        if (pendingRefreshTicks == 0 && !waiting && mc.currentScreen == null) {
            pendingRefreshTicks = -1;
            requestRefresh(mc, false);
        }

        if (waiting) {
            handleWaiting(mc);
            return;
        }

        if (autoEnabled && AUTO_REFRESH_SECONDS > 0) {
            // when the "new quests" timer runs out, refresh once to pick up the new quests
            if (resetAtMillis > 0 && !expiredHandled && mc.currentScreen == null
                    && System.currentTimeMillis() >= resetAtMillis + 3000) {
                expiredHandled = true;
                requestRefresh(mc, false);
                return;
            }
            sinceRefresh++;
            if (sinceRefresh >= AUTO_REFRESH_SECONDS * 20 && mc.currentScreen == null) {
                requestRefresh(mc, false);
            }
        }
    }

    private static void onGameMessage(Text message, boolean overlay) {
        if (overlay || !REFRESH_ON_QUEST_MESSAGES || !autoEnabled) {
            return;
        }
        String text = message.getString().trim();
        String lower = text.toLowerCase();
        // only react to the server's own announcements ("GUILDS • ..."), not to players typing similar things
        if (!lower.startsWith("guilds")) {
            return;
        }

        String doneMarker = "guild quest complete:";
        int idx = lower.indexOf(doneMarker);
        if (idx >= 0) {
            markDone(text.substring(idx + doneMarker.length()));
            scheduleRefresh();
        } else if (lower.contains("new guild quests have become available")) {
            scheduleRefresh();
        }
    }

    private static void scheduleRefresh() {
        if (pendingRefreshTicks < 0) {
            pendingRefreshTicks = 60; // wait 3 seconds so the server has updated first
        }
    }

    private static void markDone(String name) {
        String target = name.replaceAll("[.!\\s]+$", "").trim();
        List<Quest> updated = new ArrayList<>();
        for (Quest q : quests) {
            if (q.task().equalsIgnoreCase(target)) {
                updated.add(new Quest(q.task(), q.total(), q.total()));
            } else {
                updated.add(q);
            }
        }
        quests = updated;
    }

    private static void requestRefresh(MinecraftClient mc, boolean manual) {
        if (waiting || mc.player == null) {
            return;
        }
        if (!manual && mc.currentScreen != null) {
            return;
        }
        mc.player.networkHandler.sendChatCommand(COMMAND);
        waiting = true;
        hiddenHandler = null;
        waitTicks = 0;
        screenSeenTicks = 0;
        sinceRefresh = 0;
    }

    /**
     * Called (from the mixin) every time the game is about to open a screen.
     * Returns true if we want to swallow the quest menu so it never appears.
     */
    public static boolean interceptScreen(Screen screen) {
        if (!HIDE_QUEST_MENU || !waiting || hiddenHandler != null) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) {
            return false; // you have another screen open, show things normally
        }
        if (screen instanceof GenericContainerScreen gcs
                && screen.getTitle().getString().toLowerCase().contains(SCREEN_TITLE)) {
            hiddenHandler = gcs.getScreenHandler();
            return true;
        }
        return false;
    }

    private static void handleWaiting(MinecraftClient mc) {
        waitTicks++;

        // invisible mode: the menu was swallowed, read it straight from the handler
        if (hiddenHandler != null) {
            screenSeenTicks++;
            if (screenSeenTicks >= 2) {
                tryFinish(mc, hiddenHandler.getInventory());
            }
            return;
        }

        // visible mode (fallback): the menu is on screen
        Screen screen = mc.currentScreen;
        if (screen instanceof GenericContainerScreen gcs
                && screen.getTitle().getString().toLowerCase().contains(SCREEN_TITLE)) {
            screenSeenTicks++;
            if (screenSeenTicks >= 2) {
                tryFinish(mc, gcs.getScreenHandler().getInventory());
            }
        } else if (waitTicks > 60) {
            waiting = false; // command didn't open the menu, give up
        }
    }

    private static void tryFinish(MinecraftClient mc, Inventory inv) {
        List<Quest> found = readQuests(inv);
        if (found.isEmpty() && waitTicks < 30) {
            return; // items haven't arrived yet, keep waiting
        }
        if (!found.isEmpty()) {
            quests = found;
            autoEnabled = true;
            long secs = readTimerSeconds(inv);
            if (secs >= 0) {
                resetAtMillis = System.currentTimeMillis() + secs * 1000L;
                expiredHandled = false;
            }
        }
        mc.player.closeHandledScreen();
        hiddenHandler = null;
        waiting = false;
    }

    private static List<Quest> readQuests(Inventory inv) {
        List<Quest> list = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            Quest q = parse(inv.getStack(i));
            if (q != null) {
                list.add(q);
            }
        }
        return list;
    }

    private static long readTimerSeconds(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) {
                continue;
            }
            List<Text> lines = lore.lines();
            for (int j = 0; j < lines.size(); j++) {
                String line = lines.get(j).getString();
                int idx = line.toLowerCase().indexOf("available in");
                if (idx >= 0) {
                    long secs = parseDuration(line.substring(idx));
                    if (secs < 0 && j + 1 < lines.size()) {
                        secs = parseDuration(lines.get(j + 1).getString());
                    }
                    if (secs >= 0) {
                        return secs;
                    }
                }
            }
        }
        return -1;
    }

    private static long parseDuration(String s) {
        Matcher m = TIME_PART.matcher(s);
        long total = 0;
        boolean found = false;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d" -> total += n * 86400;
                case "h" -> total += n * 3600;
                case "m" -> total += n * 60;
                default -> total += n;
            }
            found = true;
        }
        return found ? total : -1;
    }

    private static String formatDuration(long secs) {
        long d = secs / 86400;
        long h = (secs % 86400) / 3600;
        long m = (secs % 3600) / 60;
        long sec = secs % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("d ");
        }
        if (d > 0 || h > 0) {
            sb.append(h).append("h ");
        }
        if (d > 0 || h > 0 || m > 0) {
            sb.append(m).append("m ");
        }
        sb.append(sec).append("s");
        return sb.toString();
    }

    private static String clean(String s) {
        // strip leading symbols like "|", "(●)" etc.
        return s.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
    }

    private static Quest parse(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String name = clean(stack.getName().getString());
        if (!name.toLowerCase().contains("quest")) {
            return null;
        }
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        for (Text t : lore.lines()) {
            lines.add(t.getString());
        }

        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PROGRESS.matcher(lines.get(i));
            if (m.find()) {
                int current;
                int total;
                try {
                    current = Integer.parseInt(m.group(1).replace(",", ""));
                    total = Integer.parseInt(m.group(2).replace(",", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
                String task = i > 0 ? clean(lines.get(i - 1)) : name;
                if (task.isEmpty()) {
                    task = name;
                }
                return new Quest(task, current, total);
            }
        }
        return null;
    }

    private static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!showHud || quests.isEmpty() || mc.options.hudHidden) {
            return;
        }
        // keep the box on screen even if the window got smaller
        int x = clamp(hudX, 4, Math.max(4, mc.getWindow().getScaledWidth() - panelW + 4));
        int y = clamp(hudY, 4, Math.max(4, mc.getWindow().getScaledHeight() - panelH + 4));
        drawPanel(ctx, x, y, false);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawPanel(DrawContext ctx, int x, int y, boolean preview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;

        boolean useSample = preview && quests.isEmpty();
        List<Quest> list = useSample ? SAMPLE : quests;

        List<String> lines = new ArrayList<>();
        for (Quest q : list) {
            lines.add(q.task() + "  " + String.format("%,d/%,d", q.current(), q.total()));
        }

        String timerLine = null;
        if (resetAtMillis > 0) {
            long remaining = (resetAtMillis - System.currentTimeMillis()) / 1000;
            timerLine = remaining > 0 ? "New quests in " + formatDuration(remaining) : "New quests available!";
        } else if (useSample) {
            timerLine = "New quests in 38m 11s";
        }

        int width = tr.getWidth("Guild Quests");
        for (String line : lines) {
            width = Math.max(width, tr.getWidth(line));
        }
        if (timerLine != null) {
            width = Math.max(width, tr.getWidth(timerLine));
        }
        int lineHeight = 11;
        int height = lineHeight * (lines.size() + 1 + (timerLine != null ? 1 : 0));

        panelW = width + 8;
        panelH = height + 6;

        ctx.fill(x - 4, y - 4, x + width + 4, y + height + 2, 0x90000000);
        ctx.drawText(tr, "Guild Quests", x, y, 0xFFFFAA00, true);

        int ty = y + lineHeight;
        for (int i = 0; i < lines.size(); i++) {
            int color = list.get(i).done() ? 0xFF55FF55 : 0xFFFFFFFF;
            ctx.drawText(tr, lines.get(i), x, ty, color, true);
            ty += lineHeight;
        }
        if (timerLine != null) {
            ctx.drawText(tr, timerLine, x, ty, 0xFF55FFFF, true);
        }
    }

    // ---------- saving the HUD position ----------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("guildquesthud.properties");
    }

    private static void loadConfig() {
        Path path = configPath();
        if (!Files.exists(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            Properties props = new Properties();
            props.load(in);
            hudX = Integer.parseInt(props.getProperty("x", String.valueOf(DEFAULT_X)).trim());
            hudY = Integer.parseInt(props.getProperty("y", String.valueOf(DEFAULT_Y)).trim());
        } catch (IOException | NumberFormatException e) {
            hudX = DEFAULT_X;
            hudY = DEFAULT_Y;
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("x", String.valueOf(hudX));
        props.setProperty("y", String.valueOf(hudY));
        try (OutputStream out = Files.newOutputStream(configPath())) {
            props.store(out, "Guild Quest HUD position");
        } catch (IOException ignored) {
            // not a big deal, the position just won't be remembered
        }
    }

    // ---------- the drag editor screen ----------

    private static class PositionScreen extends Screen {
        private boolean dragging = false;
        private int grabDx = 0;
        private int grabDy = 0;

        PositionScreen() {
            super(Text.literal("Move Quest HUD"));
        }

        @Override
        protected void init() {
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                    .dimensions(width / 2 - 105, height - 30, 100, 20)
                    .build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Reset position"), button -> {
                        hudX = DEFAULT_X;
                        hudY = DEFAULT_Y;
                        saveConfig();
                    })
                    .dimensions(width / 2 + 5, height - 30, 100, 20)
                    .build());
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);

            drawPanel(ctx, hudX, hudY, true);

            // yellow outline so you can see what you are dragging
            int x1 = hudX - 4;
            int y1 = hudY - 4;
            int x2 = x1 + panelW;
            int y2 = y1 + panelH;
            int outline = 0xFFFFFF55;
            ctx.fill(x1, y1, x2, y1 + 1, outline);
            ctx.fill(x1, y2 - 1, x2, y2, outline);
            ctx.fill(x1, y1, x1 + 1, y2, outline);
            ctx.fill(x2 - 1, y1, x2, y2, outline);

            String hint = "Drag the box to move it. Click Done or press Esc when finished.";
            ctx.drawText(textRenderer, hint, (width - textRenderer.getWidth(hint)) / 2, 12, 0xFFFFFFFF, true);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            if (super.mouseClicked(click, doubled)) {
                return true;
            }
            if (click.button() == 0) {
                int mx = (int) click.x();
                int my = (int) click.y();
                int x1 = hudX - 4;
                int y1 = hudY - 4;
                if (mx >= x1 && mx <= x1 + panelW && my >= y1 && my <= y1 + panelH) {
                    dragging = true;
                    grabDx = mx - hudX;
                    grabDy = my - hudY;
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseDragged(Click click, double offsetX, double offsetY) {
            if (dragging) {
                hudX = clamp((int) click.x() - grabDx, 4, Math.max(4, width - panelW + 4));
                hudY = clamp((int) click.y() - grabDy, 4, Math.max(4, height - panelH + 4));
                return true;
            }
            return super.mouseDragged(click, offsetX, offsetY);
        }

        @Override
        public boolean mouseReleased(Click click) {
            if (dragging) {
                dragging = false;
                saveConfig();
                return true;
            }
            return super.mouseReleased(click);
        }
    }
}
