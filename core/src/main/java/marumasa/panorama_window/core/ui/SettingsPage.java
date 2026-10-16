package marumasa.panorama_window.core.ui;

import marumasa.panorama_window.core.Config;
import marumasa.panorama_window.core.Lang;
import marumasa.panorama_window.core.Panorama;

import java.util.ArrayList;
import java.util.List;

/**
 * 設定のページ。変えた値はすぐ保存され、パノラマウィンドウや Spout2 にもすぐ反映される。
 */
public final class SettingsPage extends Page {

    private final Panorama panorama;
    private final Config config;

    private final CheckBox showWindow;
    private final CheckBox borderless;
    private final CheckBox pauseWhenHidden;
    private final List<RadioButton> modes = new ArrayList<RadioButton>();
    private final RadioButton modeAuto;
    private final RadioButton modeCrop;
    private final RadioButton modeResize;
    private final NumberField interval;
    private final NumberField faces;
    private final NumberField overscan;
    private final CheckBox spout;
    private final TextField spoutName;
    private final NumberField spoutWidth;
    private final Label spoutStatus;
    private final Label status;

    public SettingsPage(UiRoot root, final Panorama panorama) {
        super(root);
        this.panorama = panorama;
        this.config = panorama.config();

        place(new GroupBox(Lang.get("ui.group.window")), 10, 36, 440, 66);
        showWindow = place(new CheckBox(Lang.get("ui.show_window"), config.isShowWindow()), 20, 52, 210, 18);
        showWindow.onChange(new Runnable() {
            public void run() {
                config.setShowWindow(showWindow.isChecked());
                panorama.applyWindowSettings();
            }
        });
        borderless = place(new CheckBox(Lang.get("ui.borderless"), config.isBorderless()), 240, 52, 200, 18);
        borderless.onChange(new Runnable() {
            public void run() {
                config.setBorderless(borderless.isChecked());
                panorama.applyWindowSettings();
            }
        });
        pauseWhenHidden = place(new CheckBox(Lang.get("ui.pause_when_hidden"), config.isPauseWhenHidden()), 20, 76, 420, 18);
        pauseWhenHidden.onChange(new Runnable() {
            public void run() {
                config.setPauseWhenHidden(pauseWhenHidden.isChecked());
            }
        });

        place(new GroupBox(Lang.get("ui.group.capture")), 10, 108, 440, 120);
        place(new Label(Lang.get("ui.mode")), 20, 124, 60, 18);
        modeAuto = place(new RadioButton(Lang.get("ui.mode.auto"), modes), 80, 124, 100, 18);
        modeCrop = place(new RadioButton(Lang.get("ui.mode.crop"), modes), 180, 124, 130, 18);
        modeResize = place(new RadioButton(Lang.get("ui.mode.resize"), modes), 310, 124, 135, 18);
        modeAuto.onSelect(modeSetter(Config.Mode.AUTO));
        modeCrop.onSelect(modeSetter(Config.Mode.CROP));
        modeResize.onSelect(modeSetter(Config.Mode.RESIZE));

        place(new Label(Lang.get("ui.interval")), 20, 148, 250, 20);
        interval = place(new NumberField(config.getCaptureInterval(), 1, 40, 1, true), 280, 148, 70, 20);
        interval.onChange(new Runnable() {
            public void run() {
                config.setCaptureInterval((int) interval.getValue());
            }
        });
        place(new Label(Lang.get("ui.faces")), 20, 172, 250, 20);
        faces = place(new NumberField(config.getFacesPerCapture(), 1, 6, 1, true), 280, 172, 70, 20);
        faces.onChange(new Runnable() {
            public void run() {
                config.setFacesPerCapture((int) faces.getValue());
            }
        });
        place(new Label(Lang.get("ui.overscan")), 20, 196, 250, 20);
        overscan = place(new NumberField((float) config.getOverscanSetting(), 0, 3, 0.1f, false), 280, 196, 70, 20);
        overscan.onChange(new Runnable() {
            public void run() {
                final float value = overscan.getValue();
                // 0 と 1 の間は「自動」と「広げない」のどちらかに寄せる
                config.setOverscan(value < 0.5f ? 0.0 : Math.max(1.0, value));
            }
        });
        place(new Label(""), 355, 196, 90, 20).setText(Lang.get("ui.overscan_hint"), Theme.TEXT_DISABLED);

        place(new GroupBox(Lang.get("ui.group.spout")), 10, 234, 440, 70);
        spout = place(new CheckBox(Lang.get("ui.spout"), config.isSpoutEnabled()), 20, 250, 150, 18);
        spout.onChange(new Runnable() {
            public void run() {
                config.setSpoutEnabled(spout.isChecked());
                panorama.applySpoutSettings();
            }
        });
        place(new Label(Lang.get("ui.spout_name")), 180, 249, 50, 20);
        spoutName = place(new TextField(), 230, 249, 210, 20);
        spoutName.maxLength(64).setText(config.getSpoutName());
        spoutName.onChange(new Runnable() {
            public void run() {
                config.setSpoutName(spoutName.getText());
                panorama.applySpoutSettings();
            }
        });
        place(new Label(Lang.get("ui.spout_width")), 20, 275, 150, 20);
        spoutWidth = place(new NumberField(config.getSpoutWidth(), 256, 8192, 256, true), 180, 275, 80, 20);
        spoutWidth.onChange(new Runnable() {
            public void run() {
                config.setSpoutWidth((int) spoutWidth.getValue());
                panorama.applySpoutSettings();
            }
        });
        spoutStatus = place(new Label(""), 270, 275, 175, 20);

        status = place(new Label(""), 12, 310, 436, 16);

        final Button reset = place(new Button(Lang.get("ui.reset"), new Runnable() {
            public void run() {
                config.resetToDefaults();
                panorama.applyWindowSettings();
                panorama.applySpoutSettings();
                load();
            }
        }), 240, 330, 100, 23);
        final Button close = place(new Button(Lang.get("ui.close"), new Runnable() {
            public void run() {
                SettingsPage.this.root.close();
            }
        }), 350, 330, 100, 23);
        defaultButton = close;
        cancelButton = close;
        load();
    }

    private <T extends Widget> T place(T widget, int x, int y, int w, int h) {
        add(widget);
        widget.bounds(x, y, w, h);
        return widget;
    }

    private Runnable modeSetter(final Config.Mode mode) {
        return new Runnable() {
            public void run() {
                config.setCaptureMode(mode);
            }
        };
    }

    // 設定の値を部品に写す
    private void load() {
        showWindow.setChecked(config.isShowWindow());
        borderless.setChecked(config.isBorderless());
        pauseWhenHidden.setChecked(config.isPauseWhenHidden());
        switch (config.getCaptureMode()) {
            case CROP:
                modeCrop.setSelected(true);
                break;
            case RESIZE:
                modeResize.setSelected(true);
                break;
            default:
                modeAuto.setSelected(true);
                break;
        }
        interval.setValue(config.getCaptureInterval());
        faces.setValue(config.getFacesPerCapture());
        overscan.setValue((float) config.getOverscanSetting());
        spout.setChecked(config.isSpoutEnabled());
        spoutName.setText(config.getSpoutName());
        spoutWidth.setValue(config.getSpoutWidth());
        tick();
    }

    @Override
    public String title() {
        return Lang.get("ui.title");
    }

    @Override
    public void tick() {
        status.setText(panorama.statusText());
        // 先頭が "!" の文字列は、うまく行っていないことを示す
        final String spoutText = panorama.spoutStatusText();
        final boolean error = spoutText.startsWith("!");
        spoutStatus.setText(error ? spoutText.substring(1) : spoutText, error ? Theme.ERROR_TEXT : Theme.TEXT);
    }
}
