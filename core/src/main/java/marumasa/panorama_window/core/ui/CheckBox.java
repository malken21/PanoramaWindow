package marumasa.panorama_window.core.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * チェックボックス。左に箱、右に文字を描く。
 */
public class CheckBox extends Widget {

    static final int BOX = 13;
    private static final Color CHECK = new Color(0x21A121);
    private static final Color BOX_HOVER = new Color(0xF8B636);

    private final String text;
    private boolean checked;
    private Runnable onChange;
    private boolean pressed;

    public CheckBox(String text, boolean checked) {
        this.text = text;
        this.checked = checked;
    }

    public CheckBox onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        if (value == checked) return;
        checked = value;
        repaint();
    }

    @Override
    public boolean focusable() {
        return enabled;
    }

    private void toggle() {
        checked = !checked;
        repaint();
        if (onChange != null) onChange.run();
    }

    @Override
    public void mousePressed(int mx, int my, boolean doubleClick) {
        pressed = true;
        repaint();
    }

    @Override
    public void mouseReleased(int mx, int my) {
        final boolean fire = pressed && contains(mx, my);
        pressed = false;
        repaint();
        if (fire) toggle();
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (key == Key.SPACE) {
            toggle();
            return true;
        }
        return false;
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        final int bx = x;
        final int by = y + (h - BOX) / 2;
        paintBox(g, bx, by, hover, pressed, enabled);
        if (checked) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(enabled ? CHECK : Theme.TEXT_DISABLED);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(bx + 3, by + 6, bx + 5, by + 9);
            g.drawLine(bx + 5, by + 9, bx + 10, by + 3);
            g.setStroke(new BasicStroke(1f));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }
        Theme.drawText(g, Theme.ellipsize(g, text, w - BOX - 6), x + BOX + 6, y, h, enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (focused) {
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{1f, 1f}, 0f));
            g.drawRect(x + BOX + 4, y + 1, Math.min(w - BOX - 5, Theme.textWidth(g, text) + 3), h - 3);
            g.setStroke(new BasicStroke(1f));
        }
    }

    static void paintBox(Graphics2D g, int x, int y, boolean hover, boolean pressed, boolean enabled) {
        g.setPaint(pressed ? new GradientPaint(0, y, new Color(0xB0B0A7), 0, y + BOX, new Color(0xE3E1D2))
                : new GradientPaint(0, y, new Color(0xDCDCD7), 0, y + BOX, Color.WHITE));
        g.fillRect(x, y, BOX, BOX);
        if (hover && enabled) {
            g.setColor(BOX_HOVER);
            g.drawRect(x + 1, y + 1, BOX - 3, BOX - 3);
        }
        g.setColor(enabled ? Theme.SELECTION.darker() : Theme.TEXT_DISABLED);
        g.drawRect(x, y, BOX - 1, BOX - 1);
    }
}
