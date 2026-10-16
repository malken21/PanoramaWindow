package marumasa.panorama_window.core.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/**
 * ラジオボタン。同じ group のうち 1 つだけが選ばれる。
 */
public class RadioButton extends Widget {

    private static final Color DOT = new Color(0x21A121);

    private final String text;
    private final List<RadioButton> group;
    private boolean selected;
    private Runnable onSelect;
    private boolean pressed;

    public RadioButton(String text, List<RadioButton> group) {
        this.text = text;
        this.group = group;
        group.add(this);
    }

    public RadioButton onSelect(Runnable onSelect) {
        this.onSelect = onSelect;
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value) {
        if (value) {
            for (RadioButton other : group) {
                if (other != this && other.selected) {
                    other.selected = false;
                    other.repaint();
                }
            }
        }
        if (selected == value) return;
        selected = value;
        repaint();
    }

    @Override
    public boolean focusable() {
        return enabled;
    }

    private void choose() {
        if (selected) return;
        setSelected(true);
        if (onSelect != null) onSelect.run();
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
        if (fire) choose();
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (key == Key.SPACE) {
            choose();
            return true;
        }
        return false;
    }

    @Override
    public void paint(Graphics2D g, boolean hover, boolean focused) {
        final int size = CheckBox.BOX;
        final int bx = x;
        final int by = y + (h - size) / 2;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(pressed ? new GradientPaint(0, by, new Color(0xB0B0A7), 0, by + size, new Color(0xE3E1D2))
                : new GradientPaint(0, by, new Color(0xDCDCD7), 0, by + size, Color.WHITE));
        g.fillOval(bx, by, size - 1, size - 1);
        g.setColor(hover && enabled ? new Color(0xF8B636) : enabled ? Theme.SELECTION.darker() : Theme.TEXT_DISABLED);
        g.drawOval(bx, by, size - 1, size - 1);
        if (selected) {
            g.setColor(enabled ? DOT : Theme.TEXT_DISABLED);
            g.fillOval(bx + 3, by + 3, size - 7, size - 7);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        Theme.drawText(g, Theme.ellipsize(g, text, w - size - 6), x + size + 6, y, h, enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (focused) {
            g.setColor(Theme.TEXT);
            g.drawRect(x + size + 4, y + 1, Math.min(w - size - 5, Theme.textWidth(g, text) + 3), h - 3);
        }
    }
}
