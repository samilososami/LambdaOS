#!/usr/bin/env python3
"""LambdaOS LEX bottom dock: GTK3 on X11, no compositor (XShape corners)."""

import math
import os
import signal
import sys

HEIGHT = 76
RADIUS = 18
PADDING = 14
ICON = 48
GAP = 10
MARGIN = 12
DOT = 4
HOVER = 56
ORDER = ("terminal", "files", "editor", "browser",
         "screenshot", "settings", "wireshark", "nmap")

BG = (0x17 / 255.0, 0x19 / 255.0, 0x1B / 255.0)
HOVER_BG = (0x1F / 255.0, 0x22 / 255.0, 0x25 / 255.0)
FG = (0xE9 / 255.0, 0xE9 / 255.0, 0xE9 / 255.0)
BORDER = (1.0, 1.0, 1.0, 0.06)


def fail(message):
    """One line on stderr, clean exit; the session must survive us."""
    print("dock: " + message, file=sys.stderr)
    return 0


try:
    import cairo
    import gi
    gi.require_version("Gdk", "3.0")
    gi.require_version("Gtk", "3.0")
    gi.require_version("Wnck", "3.0")
    from gi.repository import Gdk, Gio, GLib, Gtk, Wnck
except Exception as exc:
    sys.exit(fail("GTK3, cairo or Wnck unavailable: %s" % exc))


def round_rect(cr, x, y, width, height, radius):
    cr.new_sub_path()
    cr.arc(x + width - radius, y + radius, radius, -math.pi / 2, 0)
    cr.arc(x + width - radius, y + height - radius, radius, 0, math.pi / 2)
    cr.arc(x + radius, y + height - radius, radius, math.pi / 2, math.pi)
    cr.arc(x + radius, y + radius, radius, math.pi, 3 * math.pi / 2)
    cr.close_path()


def draw_pixbuf(cr, pixbuf, x, y, size):
    pw, ph = pixbuf.get_width(), pixbuf.get_height()
    if pw <= 0 or ph <= 0:
        return
    cr.save()
    cr.translate(x, y)
    cr.scale(float(size) / pw, float(size) / ph)
    Gdk.cairo_set_source_pixbuf(cr, pixbuf, 0, 0)
    cr.rectangle(0, 0, pw, ph)
    cr.fill()
    cr.restore()


def launcher_specs(apps_dir):
    """Fixed order; each entry is (Gio app, set of lowercase WM_CLASS keys)."""
    specs = []
    for key in ORDER:
        path = os.path.join(apps_dir, "lambdaos-%s.desktop" % key)
        if not os.path.isfile(path):
            continue
        info = Gio.DesktopAppInfo.new_from_filename(path)
        if info is None:
            continue
        keys = {"lambdaos-" + key}
        app_id = info.get_id()
        if app_id:
            keys.add(os.path.splitext(os.path.basename(app_id))[0].lower())
        wm_class = (info.get_string("StartupWMClass") or "").strip().lower()
        if wm_class:
            keys.add(wm_class)
        command = (info.get_string("Exec") or "").strip()
        words = command.split()
        # A shell -e wrapper runs in a terminal whose WM_CLASS is the terminal,
        # so only a plain executable name is a safe match. shortcut: launchers
        # that need an explicit StartupWMClass otherwise show no active dot.
        if words and not any(c in command for c in "\"'|&;<>$`"):
            keys.add(os.path.basename(words[0]).lower())
        specs.append((info, keys))
    return specs


class Dock(Gtk.Window):
    def __init__(self, launchers, wnck_screen):
        super().__init__(title="LambdaOS Dock")
        self.launchers = launchers
        self.wnck = wnck_screen
        self.hover = -1
        self.active = set()
        self.width = (PADDING * 2 + len(launchers) * ICON
                      + max(0, len(launchers) - 1) * GAP)
        self.theme = Gtk.IconTheme.get_default()
        self.icons = [self.load_icon(info) for info, _ in launchers]

        self.set_decorated(False)
        self.set_type_hint(Gdk.WindowTypeHint.DOCK)
        self.set_skip_taskbar_hint(True)
        self.set_skip_pager_hint(True)
        self.set_keep_above(True)
        self.stick()
        self.set_accept_focus(False)
        self.set_focus_on_map(False)
        self.set_resizable(False)
        self.set_default_size(self.width, HEIGHT)
        self.set_size_request(self.width, HEIGHT)
        self.set_app_paintable(True)
        self.add_events(Gdk.EventMask.BUTTON_PRESS_MASK
                        | Gdk.EventMask.POINTER_MOTION_MASK
                        | Gdk.EventMask.LEAVE_NOTIFY_MASK)
        self.connect("draw", self.on_draw)
        self.connect("realize", self.on_realize)
        self.connect("map-event", self.reposition)
        self.connect("button-press-event", self.on_button_press)
        self.connect("motion-notify-event", self.on_motion)
        self.connect("leave-notify-event", self.on_leave)

        self.wnck.connect("window-opened", self.refresh)
        self.wnck.connect("window-closed", self.refresh)
        self.wnck.connect("active-window-changed", self.refresh)
        screen = self.get_screen()
        if screen is not None:
            screen.connect("size-changed", self.reposition)
            screen.connect("monitors-changed", self.reposition)

        GLib.timeout_add_seconds(2, self.poll)
        self.show_all()
        GLib.idle_add(self.reposition)

    def load_icon(self, info):
        icon = info.get_icon()
        names = icon.get_names() if isinstance(icon, Gio.ThemedIcon) else []
        if not names:
            icon_name = info.get_string("Icon")
            names = [icon_name] if icon_name else []
        for name in names:
            if not name or "/" in name:
                continue
            try:
                pixbuf = self.theme.load_icon_for_scale(
                    name, ICON, self.get_scale_factor(),
                    Gtk.IconLookupFlags.FORCE_SIZE)
            except GLib.Error:
                continue
            if pixbuf is not None:
                return pixbuf
        return None

    def tile_x(self, index):
        return PADDING + index * (ICON + GAP)

    def hit(self, x, y):
        if y < 0 or y > HEIGHT:
            return -1
        for index in range(len(self.launchers)):
            if self.tile_x(index) <= x < self.tile_x(index) + ICON:
                return index
        return -1

    def on_draw(self, _widget, cr):
        round_rect(cr, 0, 0, self.width, HEIGHT, RADIUS)
        cr.set_source_rgb(*BG)
        cr.fill()

        if 0 <= self.hover < len(self.launchers):
            x = self.tile_x(self.hover) + (ICON - HOVER) / 2.0
            y = (HEIGHT - HOVER) / 2.0
            round_rect(cr, x, y, HOVER, HOVER, 12)
            cr.set_source_rgb(*HOVER_BG)
            cr.fill()

        top = (HEIGHT - ICON) / 2.0
        for index, pixbuf in enumerate(self.icons):
            x = self.tile_x(index)
            if pixbuf is not None:
                draw_pixbuf(cr, pixbuf, x, top, ICON)
            else:
                cr.set_source_rgb(*FG)
                cr.set_line_width(1.6)
                cr.arc(x + ICON / 2.0, top + ICON / 2.0, 10, 0, 2 * math.pi)
                cr.stroke()

        cr.set_source_rgb(*FG)
        for index in self.active:
            cr.arc(self.tile_x(index) + ICON / 2.0, HEIGHT - 8,
                   DOT / 2.0, 0, 2 * math.pi)
            cr.fill()

        round_rect(cr, 0.5, 0.5, self.width - 1, HEIGHT - 1, RADIUS - 0.5)
        cr.set_source_rgba(*BORDER)
        cr.set_line_width(1)
        cr.stroke()
        return False

    def on_realize(self, _widget):
        self.apply_shape()

    def apply_shape(self):
        gdk_window = self.get_window()
        if gdk_window is None:
            return
        surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, self.width, HEIGHT)
        cr = cairo.Context(surface)
        round_rect(cr, 0, 0, self.width, HEIGHT, RADIUS)
        cr.set_source_rgba(1, 1, 1, 1)
        cr.fill()
        surface.flush()
        try:
            region = Gdk.cairo_region_create_from_surface(surface)
            gdk_window.shape_combine_region(region, 0, 0)
        except Exception as exc:
            print("dock: XShape unavailable: %s" % exc, file=sys.stderr)
            return
        # Make the input region explicit so it can never drift from the visual
        # shape; otherwise a stale/empty input shape makes the dock click-through.
        try:
            gdk_window.input_shape_combine_region(region, 0, 0)
        except Exception as exc:
            print("dock: XShape input unavailable: %s" % exc, file=sys.stderr)

    def reposition(self, *_args):
        display = self.get_display()
        if display is None:
            return False
        monitor = display.get_primary_monitor() or display.get_monitor(0)
        if monitor is None:
            return False
        geometry = monitor.get_geometry()
        self.move(geometry.x + (geometry.width - self.width) // 2,
                  geometry.y + geometry.height - HEIGHT - MARGIN)
        return False

    def window_matches(self, app_keys, win):
        names = ((win.get_class_group_name() or "").lower(),
                 (win.get_class_instance_name() or "").lower())
        return any(name and name in app_keys for name in names)

    def refresh(self, *_args):
        active = set()
        try:
            for win in self.wnck.get_windows():
                if win.get_window_type() not in (Wnck.WindowType.NORMAL,
                                                 Wnck.WindowType.DIALOG):
                    continue
                for index, (_info, app_keys) in enumerate(self.launchers):
                    if self.window_matches(app_keys, win):
                        active.add(index)
        except Exception:
            pass
        if active != self.active:
            self.active = active
            self.queue_draw()

    def poll(self):
        self.refresh()
        return True

    def activate(self, index, timestamp):
        info, app_keys = self.launchers[index]
        try:
            for win in self.wnck.get_windows():
                if win.get_window_type() not in (Wnck.WindowType.NORMAL,
                                                 Wnck.WindowType.DIALOG):
                    continue
                if self.window_matches(app_keys, win):
                    if win.is_minimized():
                        win.unminimize(timestamp)
                    win.activate(timestamp)
                    return
        except Exception:
            pass
        try:
            context = Gdk.Display.get_default().get_app_launch_context()
            context.set_timestamp(timestamp)
            info.launch([], context)
        except Exception as exc:
            print("dock: launch failed: %s" % exc, file=sys.stderr)

    def on_button_press(self, _widget, event):
        if event.button != 1:
            return False
        index = self.hit(event.x, event.y)
        if index < 0:
            return False
        self.activate(index, event.time)
        return True

    def on_motion(self, _widget, event):
        index = self.hit(event.x, event.y)
        if index != self.hover:
            self.hover = index
            self.queue_draw()
        return False

    def on_leave(self, _widget, _event):
        if self.hover != -1:
            self.hover = -1
            self.queue_draw()
        return False


def quit_main(*_args):
    Gtk.main_quit()
    return False


def main():
    try:
        initialized, _argv = Gtk.init_check(None)
    except Exception as exc:
        return fail("cannot open display: %s" % exc)
    if not initialized:
        return fail("cannot open display")
    try:
        settings = Gtk.Settings.get_default()
        if settings is not None:
            settings.set_property("gtk-icon-theme-name", "LambdaOS")
        theme = Gtk.IconTheme.get_default()
        if theme is None or not theme.has_icon("lambdaos-menu"):
            return fail("LambdaOS icon theme is not installed")
        Wnck.set_client_type(Wnck.ClientType.PAGER)
        wnck_screen = Wnck.Screen.get_default()
        if wnck_screen is None:
            return fail("Wnck is unavailable")
        launchers = launcher_specs(
            os.path.join(GLib.get_user_data_dir(), "applications"))
        if not launchers:
            return fail("no lambdaos-*.desktop launchers installed")
        dock = Dock(launchers, wnck_screen)
    except Exception as exc:
        return fail("startup failed: %s" % exc)
    _keep = dock
    GLib.unix_signal_add(GLib.PRIORITY_HIGH, signal.SIGTERM, quit_main)
    Gtk.main()
    return 0


if __name__ == "__main__":
    sys.exit(main())
