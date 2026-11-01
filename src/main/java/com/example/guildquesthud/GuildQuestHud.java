package com.example.guildquesthud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuildQuestHud implements ClientModInitializer {

    // ===== Settings you can change =====
    private static final String COMMAND = "guild quests";        // command to run (no slash)
    private static final String SCREEN_TITLE = "guild quests";   // menu title to look for
    private static final int AUTO_REFRESH_SECONDS = 300;         // 0 = never auto-refresh
    private static final int HUD_X = 6;                          // distance from left edge
    private static final int HUD_Y = 6;                          // distance from top edge
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

    private static boolean showHud = true;
    private static boolean waiting = false;
    private static boolean autoEnabled = false;
    private static int waitTicks = 0;
    private static int screenSeenTicks = 0;
    private static int sinceRefresh = 0;
    private static List<Quest> quests = new ArrayList<>();
    private static long resetAtMillis = 0;      // when new quests become available
    private static boolean expiredHandled = false;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("guildquesthud", "main"));
        refreshKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.guildquesthud.refresh", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.guildquesthud.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

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
        });
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

    private static void requestRefresh(MinecraftClient mc, boolean manual) {
        if (waiting || mc.player == null) {
            return;
        }
        if (!manual && mc.currentScreen != null) {
            return;
        }
        mc.player.networkHandler.sendChatCommand(COMMAND);
        waiting = true;
        waitTicks = 0;
        screenSeenTicks = 0;
        sinceRefresh = 0;
    }

    private static void handleWaiting(MinecraftClient mc) {
        waitTicks++;
        Screen screen = mc.currentScreen;

        if (screen instanceof GenericContainerScreen gcs
                && screen.getTitle().getString().toLowerCase().contains(SCREEN_TITLE)) {
            screenSeenTicks++;
            if (screenSeenTicks >= 2) {
                Inventory inv = gcs.getScreenHandler().getInventory();
                List<Quest> found = readQuests(inv);
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
                waiting = false;
            }
        } else if (waitTicks > 60) {
            waiting = false; // command didn't open the menu, give up
        }
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

        TextRenderer tr = mc.textRenderer;
        List<String> lines = new ArrayList<>();
        for (Quest q : quests) {
            lines.add(q.task() + "  " + String.format("%,d/%,d", q.current(), q.total()));
        }

        String timerLine = null;
        if (resetAtMillis > 0) {
            long remaining = (resetAtMillis - System.currentTimeMillis()) / 1000;
            timerLine = remaining > 0 ? "New quests in " + formatDuration(remaining) : "New quests available!";
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

        ctx.fill(HUD_X - 4, HUD_Y - 4, HUD_X + width + 4, HUD_Y + height + 2, 0x90000000);
        ctx.drawText(tr, "Guild Quests", HUD_X, HUD_Y, 0xFFFFAA00, true);

        int y = HUD_Y + lineHeight;
        for (int i = 0; i < lines.size(); i++) {
            int color = quests.get(i).done() ? 0xFF55FF55 : 0xFFFFFFFF;
            ctx.drawText(tr, lines.get(i), HUD_X, y, color, true);
            y += lineHeight;
        }
        if (timerLine != null) {
            ctx.drawText(tr, timerLine, HUD_X, y, 0xFF55FFFF, true);
        }
    }
}
