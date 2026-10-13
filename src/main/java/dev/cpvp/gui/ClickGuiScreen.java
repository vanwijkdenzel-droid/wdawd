package dev.cpvp.gui;

import dev.cpvp.ProfileManager;
import dev.cpvp.module.Category;
import dev.cpvp.module.Module;
import dev.cpvp.module.ModuleManager;
import dev.cpvp.module.impl.Modules.FpsBoostModule;
import dev.cpvp.module.impl.Modules.InterfaceModule;
import dev.cpvp.setting.BooleanSetting;
import dev.cpvp.setting.NumberSetting;
import dev.cpvp.setting.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.IntConsumer;

import static dev.cpvp.gui.RenderUtil.*;

/**
 * Phantom Client menu. Top-left: crown + four round buttons (Mods, Layout, Profiles, Settings).
 * Below it a small category list switches the category windows on and off. Windows are moved in the
 * layout editor only. Settings contains real settings only; HUD mods live in the Mods window as tiles.
 */
public final class ClickGuiScreen extends Screen {
    private static final int NAV_W = 138, CAT_H = 32, ROW_H = 30, HEADER_H = 34, TITLE_H = 30, RAD = 9;
    private static final int NO_MIN = Integer.MIN_VALUE, NO_MAX = Integer.MAX_VALUE;
    private static final List<Category> COLS = List.of(Category.COMBAT, Category.RENDER, Category.UTILITY,
            Category.WORLD, Category.INVENTORY, Category.NETWORK);

    private static final Map<Object, float[]> SCROLL = new HashMap<>();
    private static final Map<Module, Float> EXPAND = new HashMap<>();
    private static final Set<Module> OPEN = new HashSet<>();
    private static final Set<MenuWindow> COLLAPSED = new HashSet<>();
    private static final Map<MenuWindow, Float> COLLAPSE = new HashMap<>();

    private record Hit(int x, int y, int w, int h, int clipTop, int clipBottom, IntConsumer click) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record Region(int x, int y, int w, int h, float[] sc) {}

    private final List<Hit> hits = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private Module listening;
    private NumberSetting dragging;
    private int dragX, dragW;
    private double clickX, clickY;
    private float kAnim, scale = 0.85f;
    private int lw, lh;
    private boolean toEditor;
    private long lastFrame = System.nanoTime();

    public ClickGuiScreen() {
        super(Text.literal("Phantom Client"));
        MenuWindow.init();
    }

    @Override public boolean shouldPause() { return false; }

    private InterfaceModule ui() { return ModuleManager.INSTANCE.get(InterfaceModule.class); }

    @Override public void removed() {
        ProfileManager.saveCurrent();
        if (toEditor) return;
        OPEN.clear(); EXPAND.clear();
        for (MenuWindow w : MenuWindow.ALL) if (w.cat == null && w != MenuWindow.NAV) w.open = false;
        listening = null;
    }

    private void scissor(DrawContext c, int x1, int y1, int x2, int y2) {
        c.enableScissor(Math.round(x1 * scale), Math.round(y1 * scale), Math.round(x2 * scale), Math.round(y2 * scale));
    }

    // =============================================================== render
    @Override
    public void render(DrawContext c, int mx, int my, float tickDelta) {
        ACCENT = ui().accent();
        scale = (float) ui().guiScale.get();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        kAnim = 1f - (float) Math.exp(-dt * ui().animSpeed.get());
        float kScroll = 1f - (float) Math.exp(-dt * 16f);
        hits.clear();
        regions.clear();

        if (ui().blur.get() && client != null && client.world != null) applyBlur(tickDelta);
        c.fill(0, 0, width, height, 0x30000000);

        lw = Math.round(width / scale); lh = Math.round(height / scale);
        int smx = Math.round(mx / scale), smy = Math.round(my / scale);
        var ms = c.getMatrices();
        ms.push();
        ms.scale(scale, scale, 1f);
        drawTopBar(c, smx, smy);
        drawNav(c, smx, smy);
        for (MenuWindow w : new ArrayList<>(MenuWindow.ALL)) {
            if (w == MenuWindow.NAV || !w.open) continue;
            w.x = MathHelper.clamp(w.x, 0, Math.max(0, lw - w.w));      // never off-screen
            w.y = MathHelper.clamp(w.y, 0, Math.max(0, lh - 40));
            if (w.cat != null) drawCategory(c, w, smx, smy, kScroll);
            else drawPopup(c, w, smx, smy, kScroll);
        }
        ms.pop();
    }

    // ---------------------------------------------------------------- top-left bar
    private void drawTopBar(DrawContext c, int mx, int my) {
        crown(c, 10, 9, 20, CROWN);
        int x = 42;
        x = topButton(c, x, 6, 7, MenuWindow.MODS.open, mx, my, b -> toggleWin(MenuWindow.MODS));
        x = topButton(c, x, 6, ICON_LAYOUT, false, mx, my, b -> { toEditor = true; client.setScreen(new HudEditorScreen(this)); });
        x = topButton(c, x, 6, ICON_PROFILES, MenuWindow.PROFILES.open, mx, my, b -> toggleWin(MenuWindow.PROFILES));
        x = topButton(c, x, 6, Category.SETTINGS.ordinal(), MenuWindow.SETTINGS.open, mx, my, b -> toggleWin(MenuWindow.SETTINGS));
        text(c, "Phantom Client 3.0.0", x + 6, 15, DIM, 0.75f);   // so you can see which jar is running
    }

    private int topButton(DrawContext c, int x, int y, int icon, boolean on, int mx, int my, IntConsumer click) {
        boolean hov = mx >= x && mx < x + 26 && my >= y && my < y + 26;
        roundRect(c, x, y, x + 26, y + 26, 13, on ? alpha(ACCENT, 0.25f) : (hov ? ROW_HOV : NAV));
        outline(c, x, y, x + 26, y + 26, on ? alpha(ACCENT, 0.7f) : BORDER);
        icon(c, icon, x + 6, y + 6, on ? ACCENT : ASH);
        hits.add(new Hit(x, y, 26, 26, NO_MIN, NO_MAX, click));
        return x + 32;
    }

    // ---------------------------------------------------------------- category list
    private void drawNav(DrawContext c, int mx, int my) {
        MenuWindow n = MenuWindow.NAV;
        n.w = NAV_W; n.h = 10 + COLS.size() * CAT_H;
        n.x = 8; n.y = 40;
        roundRect(c, n.x, n.y, n.x + NAV_W, n.y + n.h, RAD, NAV);
        outline(c, n.x, n.y, n.x + NAV_W, n.y + n.h, BORDER);
        int ry = n.y + 5;
        for (Category cat : COLS) {
            final MenuWindow w = MenuWindow.of(cat);
            boolean on = w != null && w.open;
            boolean hov = mx >= n.x && mx < n.x + NAV_W && my >= ry && my < ry + CAT_H;
            if (hov) roundRect(c, n.x + 5, ry, n.x + NAV_W - 5, ry + CAT_H - 2, 7, ROW_HOV);
            icon(c, cat.ordinal(), n.x + 14, ry + 8, on ? ACCENT : ASH);
            text(c, cat.label, n.x + 40, ry + 9, on ? TEXT : ASH, 0.95f);
            toggle(c, n.x + NAV_W - 40, ry + 8, on);
            hits.add(new Hit(n.x, ry, NAV_W, CAT_H, NO_MIN, NO_MAX, b -> toggleWin(w)));
            ry += CAT_H;
        }
    }

    private void toggleWin(MenuWindow w) {
        if (w == null) return;
        w.open = !w.open;
        if (w.open) w.front();
        ProfileManager.markDirty();
    }

    // ---------------------------------------------------------------- category window
    private void drawCategory(DrawContext c, MenuWindow w, int mx, int my, float kScroll) {
        List<Module> mods = ModuleManager.INSTANCE.byCategory(w.cat);
        float content = listContent(mods);

        float ca = COLLAPSE.getOrDefault(w, 1f), goal = COLLAPSED.contains(w) ? 0f : 1f;
        ca += (goal - ca) * kAnim;
        if (Math.abs(goal - ca) < 0.01f) ca = goal;
        COLLAPSE.put(w, ca);

        int maxBody = Math.max(60, lh - w.y - HEADER_H - 10);
        int bodyH = Math.round(Math.min(content, maxBody) * ca);
        w.h = HEADER_H + bodyH;

        roundRect(c, w.x, w.y, w.x + w.w, w.y + w.h, RAD, PANEL);
        roundRect(c, w.x, w.y, w.x + w.w, w.y + HEADER_H, RAD, HEADER);
        if (bodyH > 0) c.fill(w.x, w.y + HEADER_H - RAD, w.x + w.w, w.y + HEADER_H, HEADER);
        icon(c, w.cat.ordinal(), w.x + 12, w.y + 10, ACCENT);
        text(c, w.title, w.x + 38, w.y + 11, TEXT, 1.0f);
        if (COLLAPSED.contains(w)) chevronDown(c, w.x + w.w - 22, w.y + 15, DIM);
        else chevronUp(c, w.x + w.w - 22, w.y + 15, DIM);
        if (bodyH > 0) c.fill(w.x + 1, w.y + HEADER_H - 1, w.x + w.w - 1, w.y + HEADER_H, BORDER);

        hits.add(new Hit(w.x, w.y, w.w, w.h, NO_MIN, NO_MAX, b -> w.front()));
        hits.add(new Hit(w.x + w.w - 30, w.y, 30, HEADER_H, NO_MIN, NO_MAX, b -> {
            if (!COLLAPSED.remove(w)) COLLAPSED.add(w);
        }));

        drawListBody(c, mods, w, w.x, w.y + HEADER_H, w.w, bodyH, content, mx, my, kScroll);
        outline(c, w.x, w.y, w.x + w.w, w.y + w.h, BORDER);
        regions.add(new Region(w.x, w.y, w.w, w.h, SCROLL.computeIfAbsent(w, k -> new float[2])));
    }

    // ---------------------------------------------------------------- module list (rows with switches)
    private float listContent(List<Module> mods) {
        float content = 4;
        for (Module m : mods) {
            float e = EXPAND.getOrDefault(m, 0f), g = OPEN.contains(m) ? 1f : 0f;
            e += (g - e) * kAnim;
            if (Math.abs(g - e) < 0.01f) e = g;
            EXPAND.put(m, e);
            content += ROW_H + Math.round(settingsHeight(m, true) * e) + 2;
        }
        return content + 4;
    }

    private void drawListBody(DrawContext c, List<Module> mods, Object key, int x, int y, int w, int bodyH,
                              float content, int mx, int my, float kScroll) {
        if (bodyH <= 0) return;
        float[] sc = SCROLL.computeIfAbsent(key, k -> new float[2]);
        float max = Math.max(0, content - bodyH);
        sc[0] = MathHelper.clamp(sc[0], 0, max);
        sc[1] += (sc[0] - sc[1]) * kScroll;
        if (Math.abs(sc[0] - sc[1]) < 0.05f) sc[1] = sc[0];

        final int clipTop = y, clipBottom = y + bodyH;
        scissor(c, x + 1, clipTop, x + w - 1, clipBottom);
        float yy = clipTop + 4 - sc[1];
        for (Module m : mods) {
            float e = EXPAND.getOrDefault(m, 0f);
            final int rx = x + 6, rw = w - 12, top = Math.round(yy);
            int setH = Math.round(settingsHeight(m, true) * e);
            if (top + ROW_H + setH > clipTop && top < clipBottom) {
                boolean on = m.isEnabled();
                boolean hv = mx >= rx && mx < rx + rw && my >= top && my < top + ROW_H && my >= clipTop && my <= clipBottom;
                if (on) roundRect(c, rx, top, rx + rw, top + ROW_H, 8, alpha(ACCENT, 0.12f));
                else if (hv) roundRect(c, rx, top, rx + rw, top + ROW_H, 8, ROW_HOV);
                text(c, m.getName(), rx + 12, top + 9, on ? TEXT : (hv ? TEXT : ASH), 0.95f);
                if (m.getKey() >= 0) {
                    String kn = keyName(m.getKey());
                    text(c, kn, rx + rw - 76 - width(kn, 0.7f), top + 11, DIM, 0.7f);
                }
                toggle(c, rx + rw - 64, top + 8, on);
                dots(c, rx + rw - 18, top + 8, hv || OPEN.contains(m) ? TEXT : DIM);
                hits.add(new Hit(rx, top, rw - 28, ROW_H, clipTop, clipBottom, b -> {
                    if (b == 0) m.toggle(); else if (b == 1) toggleOpen(m);
                }));
                hits.add(new Hit(rx + rw - 28, top, 28, ROW_H, clipTop, clipBottom, b -> toggleOpen(m)));
                if (setH > 1) drawSettings(c, m, rx, rw, top + ROW_H, setH, e > 0.95f, clipTop, clipBottom, true);
            }
            yy += ROW_H + setH + 2;
        }
        c.disableScissor();
        if (max > 0) {
            int barH = Math.max(14, (int) (bodyH * (bodyH / content)));
            int barY = clipTop + (int) ((bodyH - barH) * (sc[1] / max));
            roundRect(c, x + w - 5, barY, x + w - 2, barY + barH, 1, 0xFF4A4A4A);
        }
    }

    private void toggleOpen(Module m) { if (!OPEN.remove(m)) OPEN.add(m); }

    // ---------------------------------------------------------------- popups: Mods (tiles), Profiles, Settings
    private static final int TILE_W = 104, TILE_H = 88, TILE_GAP = 8, TILE_COLS = 3;

    private int tilesContent() {
        int n = ModuleManager.INSTANCE.byCategory(Category.MODS).size();
        int rows = (n + TILE_COLS - 1) / TILE_COLS;
        return 10 + rows * (TILE_H + TILE_GAP);
    }

    private int settingsContent() { return 10 + 18 + settingsHeight(ui(), false) + 10 + 36; }

    private int popupBodyH(MenuWindow w) {
        if (w == MenuWindow.PROFILES) return 8 + 28 + 8 + Math.min(ProfileManager.names().size(), 12) * 26 + 8;
        if (w == MenuWindow.MODS) return Math.min(tilesContent(), 320);
        return Math.min(settingsContent(), 360);
    }

    private void drawPopup(DrawContext c, MenuWindow win, int mx, int my, float kScroll) {
        int bodyH = popupBodyH(win);
        int x = win.x, y = win.y, w = win.w, h = TITLE_H + bodyH;
        win.h = h;

        roundRect(c, x, y, x + w, y + h, RAD, PANEL);
        roundRect(c, x, y, x + w, y + TITLE_H, RAD, HEADER);
        c.fill(x, y + TITLE_H - RAD, x + w, y + TITLE_H, HEADER);
        text(c, win.title, x + 14, y + 9, TEXT, 1.0f);
        boolean xh = mx >= x + w - 28 && mx < x + w && my >= y && my < y + TITLE_H;
        roundRect(c, x + w - 26, y + 5, x + w - 6, y + TITLE_H - 5, 10, xh ? ROW_HOV : HEADER);
        cross(c, x + w - 19, y + 12, xh ? TEXT : ASH);
        outline(c, x, y, x + w, y + h, BORDER);

        hits.add(new Hit(x, y, w, h, NO_MIN, NO_MAX, b -> win.front()));
        hits.add(new Hit(x + w - 28, y, 28, TITLE_H, NO_MIN, NO_MAX, b -> { win.open = false; ProfileManager.markDirty(); }));

        int by = y + TITLE_H;
        if (win == MenuWindow.PROFILES) drawProfiles(c, x, by, w);
        else if (win == MenuWindow.MODS) { drawTiles(c, win, x, by, w, bodyH); regions.add(new Region(x, y, w, h, win.sc)); }
        else { drawSettingsWindow(c, win, x, by, w, bodyH); regions.add(new Region(x, y, w, h, win.sc)); }
    }

    private void drawTiles(DrawContext c, MenuWindow win, int x, int by, int w, int viewH) {
        List<Module> mods = ModuleManager.INSTANCE.byCategory(Category.MODS);
        float max = Math.max(0, tilesContent() - viewH);
        win.sc[0] = MathHelper.clamp(win.sc[0], 0, max);
        win.sc[1] += (win.sc[0] - win.sc[1]) * 0.35f;
        final int clipTop = by, clipBottom = by + viewH;
        scissor(c, x + 1, clipTop, x + w - 1, clipBottom);
        for (int i = 0; i < mods.size(); i++) {
            final Module m = mods.get(i);
            int tx = x + 10 + (i % TILE_COLS) * (TILE_W + TILE_GAP);
            int ty = Math.round(by + 10 + (i / TILE_COLS) * (TILE_H + TILE_GAP) - win.sc[1]);
            boolean on = m.isEnabled();
            roundRect(c, tx, ty, tx + TILE_W, ty + TILE_H, 12, on ? alpha(ACCENT, 0.20f) : 0xFF1C1C1C);
            outline(c, tx, ty, tx + TILE_W, ty + TILE_H, on ? alpha(ACCENT, 0.8f) : BORDER);
            roundRect(c, tx + 10, ty + 10, tx + 34, ty + 34, 8, on ? alpha(ACCENT, 0.35f) : TRACK);
            centerText(c, m.getName().substring(0, 1), tx + 22, ty + 15, on ? ACCENT : ASH, 1.0f);
            toggle(c, tx + TILE_W - 38, ty + 14, on);
            text(c, m.getName(), tx + 10, ty + TILE_H - 36, TEXT, 0.88f);
            text(c, "HUD", tx + 10, ty + TILE_H - 20, DIM, 0.7f);
            hits.add(new Hit(tx, ty, TILE_W, TILE_H, clipTop, clipBottom, b -> m.toggle()));
        }
        c.disableScissor();
    }

    private void drawProfiles(DrawContext c, int x, int by, int w) {
        List<String> names = ProfileManager.names();
        int shown = Math.min(names.size(), 12);
        roundRect(c, x + 10, by + 8, x + w - 10, by + 36, 9, TRACK);
        centerText(c, "+  New profile", x + w / 2, by + 16, TEXT, 0.85f);
        hits.add(new Hit(x + 10, by + 8, w - 20, 28, NO_MIN, NO_MAX, b -> ProfileManager.create()));
        int ry = by + 44;
        for (int i = 0; i < shown; i++) {
            final String name = names.get(i);
            boolean sel = name.equals(ProfileManager.current);
            if (sel) roundRect(c, x + 10, ry, x + w - 10, ry + 24, 8, ACCENT);
            text(c, name, x + 20, ry + 7, sel ? BLACK : TEXT, 0.9f);
            hits.add(new Hit(x + 10, ry, w - 20, 24, NO_MIN, NO_MAX, b -> {
                if (b == 0) ProfileManager.load(name); else if (b == 1) ProfileManager.delete(name);
            }));
            ry += 26;
        }
    }

    /** Real settings only: theme, scale, blur, speeds - plus the FPS boost switch. */
    private void drawSettingsWindow(DrawContext c, MenuWindow win, int x, int by, int w, int viewH) {
        int content = settingsContent();
        float max = Math.max(0, content - viewH);
        win.sc[0] = MathHelper.clamp(win.sc[0], 0, max);
        win.sc[1] += (win.sc[0] - win.sc[1]) * 0.35f;
        final int clipTop = by, clipBottom = by + viewH;
        scissor(c, x + 1, clipTop, x + w - 1, clipBottom);

        int y = Math.round(by + 10 - win.sc[1]);
        text(c, "INTERFACE", x + 14, y, DIM, 0.7f);
        y += 18;
        int ifcH = settingsHeight(ui(), false);
        drawSettings(c, ui(), x + 6, w - 12, y, ifcH, true, clipTop, clipBottom, false);
        y += ifcH + 10;

        FpsBoostModule fps = ModuleManager.INSTANCE.get(FpsBoostModule.class);
        if (fps != null) {
            boolean on = fps.isEnabled();
            roundRect(c, x + 10, y, x + w - 10, y + 32, 9, on ? alpha(ACCENT, 0.15f) : 0xFF1C1C1C);
            text(c, "FPS Boost", x + 22, y + 6, TEXT, 0.9f);
            text(c, "lower particles, clouds, shadows, distance", x + 22, y + 19, DIM, 0.6f);
            toggle(c, x + w - 48, y + 9, on);
            hits.add(new Hit(x + 10, y, w - 20, 32, clipTop, clipBottom, b -> fps.toggle()));
        }
        c.disableScissor();
    }

    // ---------------------------------------------------------------- module settings
    private void drawSettings(DrawContext c, Module m, int rx, int rw, int startY, int setH, boolean interact,
                              int clipTop, int clipBottom, boolean keybind) {
        scissor(c, rx, startY, rx + rw, startY + setH);
        roundRect(c, rx + 2, startY, rx + rw - 2, startY + setH - 2, 8, INNER);
        int sy = startY + 3;
        final int ix = rx + 12, iw = rw - 24;

        if (keybind) {
            text(c, "Keybind", ix, sy + 6, DIM, 0.8f);
            String kt = listening == m ? "press a key..." : (m.getKey() < 0 ? "NONE" : keyName(m.getKey()));
            int kw = width(kt, 0.75f) + 14, kx = ix + iw - kw;
            roundRect(c, kx, sy + 2, kx + kw, sy + 19, 7, TRACK);
            text(c, kt, kx + 7, sy + 6, listening == m ? ACCENT : TEXT, 0.75f);
            if (interact) hits.add(new Hit(kx, sy + 2, kw, 17, clipTop, clipBottom, b -> listening = (listening == m) ? null : m));
            sy += 24;
        }
        for (Setting s : m.getSettings()) {
            if (s instanceof BooleanSetting bs) {
                text(c, s.getName(), ix, sy + 6, TEXT, 0.8f);
                toggle(c, ix + iw - 28, sy + 3, bs.get());
                if (interact) hits.add(new Hit(ix, sy, iw, 22, clipTop, clipBottom, b -> bs.toggle()));
                sy += 24;
            } else if (s instanceof NumberSetting ns) {
                text(c, s.getName(), ix, sy + 3, TEXT, 0.8f);
                String v = ns.display();
                text(c, v, ix + iw - width(v, 0.8f), sy + 3, ACCENT, 0.8f);
                final int tx = ix, tw = iw, ty = sy + 19;
                double f = (ns.get() - ns.min()) / (ns.max() - ns.min());
                int fw = (int) (tw * f);
                roundRect(c, tx, ty, tx + tw, ty + 6, 3, TRACK);
                if (fw > 0) roundRect(c, tx, ty, tx + Math.max(fw, 6), ty + 6, 3, ACCENT);
                roundRect(c, tx + fw - 5, ty - 2, tx + fw + 5, ty + 8, 5, 0xFFFFFFFF);
                if (interact) hits.add(new Hit(tx - 4, ty - 6, tw + 8, 20, clipTop, clipBottom, b -> {
                    dragging = ns; dragX = tx; dragW = tw; updateSlider(clickX);
                }));
                sy += 30;
            }
        }
        c.disableScissor();
    }

    private int settingsHeight(Module m, boolean keybind) {
        int h = keybind ? 28 : 6;
        for (Setting s : m.getSettings()) h += (s instanceof NumberSetting) ? 30 : 24;
        return h + 6;
    }

    private void updateSlider(double mouseX) {
        if (dragging == null) return;
        double f = MathHelper.clamp((mouseX - dragX) / (double) dragW, 0, 1);
        dragging.set(dragging.min() + f * (dragging.max() - dragging.min()));
    }

    private static String keyName(int key) {
        return InputUtil.fromKeyCode(key, -1).getLocalizedText().getString().toUpperCase(Locale.ROOT);
    }

    // =============================================================== input
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx /= scale; my /= scale;
        clickX = mx; clickY = my;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (my < h.clipTop() || my > h.clipBottom()) continue;
            if (h.contains(mx, my)) { h.click().accept(button); return true; }
        }
        listening = null;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        mx /= scale;
        if (dragging != null) { updateSlider(mx); return true; }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging != null) ProfileManager.markDirty();
        dragging = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        mx /= scale; my /= scale;
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (mx < r.x() || mx >= r.x() + r.w() || my < r.y() || my >= r.y() + r.h()) continue;
            r.sc()[0] -= (float) (vertical * ui().scrollSpeed.get());
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
            listening.setKey(clear ? -1 : key);
            listening = null;
            ProfileManager.markDirty();
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) { close(); return true; }
        return super.keyPressed(key, scancode, mods);
    }
}
