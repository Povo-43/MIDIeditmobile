#!/usr/bin/env python3
"""Verify the editor top bar clears the status bar and display cutout.

Parses the uiautomator dumps produced by capture-top-bar.sh, asserts that the
top-bar controls of the new build start below the system bars, and renders a
wireframe SVG of each capture. The emulator's software renderer often records
black PNGs, so these geometry checks and SVGs are the actual visual evidence.

Exits non-zero when the top bar would be hidden under the system UI.
"""

import html
import re
import sys

FAILURES = []


def load(name):
    try:
        with open(name, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return ""


def find_label(xml, label):
    """All bounds of nodes whose text or content-desc matches label."""
    out = []
    for match in re.finditer(r"<node[^>]*>", xml):
        node = match.group(0)

        def attr(name, node=node):
            m = re.search(name + r'="([^"]*)"', node)
            return html.unescape(m.group(1)) if m else ""

        if label in (attr("text"), attr("content-desc")):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if b:
                x1, y1, x2, y2 = (int(v) for v in b.groups())
                out.append((x1, y1, x2, y2))
    return out


def top_area_bounds(xml, label, limit=200):
    """Bounds of label occurrences near the top of the screen."""
    return [b for b in find_label(xml, label) if b[1] < limit]


def density():
    txt = load("wm-density.txt")
    m = re.search(r"Override density: (\d+)", txt) or re.search(
        r"Physical density: (\d+)", txt
    )
    return int(m.group(1)) if m else 180


def display_size():
    txt = load("wm-size.txt")
    m = re.search(r"Override size: (\d+)x(\d+)", txt) or re.search(
        r"Physical size: (\d+)x(\d+)", txt
    )
    if m:
        return int(m.group(1)), int(m.group(2))
    return 1600, 1000


def parse_insets(prefix):
    """(status_bar_top, cutout(l, t, r, b)) measured from dumpsys, best effort."""
    txt = load(f"{prefix}-displays.txt")
    cut = (0, 0, 0, 0)
    m = re.search(
        r"mDisplayCutout=DisplayCutout\{insets=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)",
        txt,
    )
    if m:
        cut = tuple(max(0, int(v)) for v in m.groups())
    # Status bar: the largest top stable/status-bar inset reported for a window.
    sb = 0
    for m in re.finditer(r"[sS]tableInsets=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", txt):
        sb = max(sb, max(0, int(m.group(2))))
    if sb == 0:
        # AOSP default: 24dp status bar scaled by the display density.
        sb = round(24 * density() / 160)
    return sb, cut


def write_svg(path, xml, sb, cut, size, note):
    w, h = size
    boxes = []
    for label in ("再生", "メニュー", "新しい曲"):
        boxes.extend((label,) + b for b in top_area_bounds(xml, label))
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}">',
        f'<rect width="{w}" height="{h}" fill="#101216"/>',
    ]
    if sb > 0:
        parts.append(
            f'<rect x="0" y="0" width="{w}" height="{sb}" fill="#3a4655" opacity="0.85"/>'
        )
        parts.append(
            f'<text x="8" y="{max(16, sb - 6)}" fill="#e6edf3" font-size="15" '
            f'font-family="sans-serif">status bar ({sb}px)</text>'
        )
    if cut[1] > 0:
        parts.append(
            f'<rect x="0" y="0" width="{w}" height="{cut[1]}" fill="#8f3232" opacity="0.5"/>'
        )
    if cut[0] > 0:
        parts.append(
            f'<rect x="0" y="0" width="{cut[0]}" height="{h}" fill="#8f3232" opacity="0.5"/>'
        )
        parts.append(
            f'<text x="{cut[0] + 6}" y="{h - 12}" fill="#ffd9d9" font-size="14" '
            f'font-family="sans-serif">cutout L={cut[0]}px</text>'
        )
    for label, x1, y1, x2, y2 in boxes:
        parts.append(
            f'<rect x="{x1}" y="{y1}" width="{x2 - x1}" height="{y2 - y1}" '
            f'fill="none" stroke="#4fd08a" stroke-width="2"/>'
        )
        parts.append(
            f'<text x="{x1}" y="{max(16, y1 - 5)}" fill="#4fd08a" font-size="15" '
            f'font-family="sans-serif">{label} ({x1},{y1})</text>'
        )
    parts.append(
        f'<text x="{w - 10}" y="{h - 10}" text-anchor="end" fill="#9aa4b2" '
        f'font-size="17" font-family="sans-serif">{html.escape(note)}</text>'
    )
    parts.append("</svg>")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("".join(parts))


def summarize(tag, sb, cut):
    xml = load(f"{tag}-editor-window.xml")
    icons = []
    for label in ("再生", "メニュー"):
        for b in top_area_bounds(xml, label):
            icons.append((label,) + b)
    titles = top_area_bounds(xml, "新しい曲")
    icon_y1 = min((b[2] for b in icons), default=None)
    title_x1 = min((b[1] for b in titles), default=None)

    print(f"=== {tag} ===")
    print(f"  status bar top inset: {sb}px")
    print(f"  cutout insets (l,t,r,b): {cut}")
    for label, x1, y1, x2, y2 in icons:
        print(f"  {label}: bounds=({x1},{y1})-({x2},{y2})")
    for x1, y1, x2, y2 in titles:
        print(f"  新しい曲: bounds=({x1},{y1})-({x2},{y2})")
    return icon_y1, title_x1


def main():
    size = display_size()
    before_sb, before_cut = parse_insets("before")
    after_sb, after_cut = parse_insets("after")

    before_y1, before_x1 = summarize("before", before_sb, before_cut)
    after_y1, after_x1 = summarize("after", after_sb, after_cut)

    write_svg(
        "before-editor-layout.svg",
        load("before-editor-window.xml"),
        before_sb,
        before_cut,
        size,
        "v1.1.3 (previous release)",
    )
    write_svg(
        "after-editor-layout.svg",
        load("after-editor-window.xml"),
        after_sb,
        after_cut,
        size,
        "current build (safeDrawingPadding fix)",
    )

    if after_y1 is None:
        FAILURES.append("top bar icons (再生/メニュー) not found in the after dump")

    # The top bar must start below the status bar (and a top cutout, if the
    # emulated one is active in this orientation). 2px tolerance.
    barrier = max(after_sb, after_cut[1])
    if after_y1 is not None and after_y1 < barrier - 2:
        FAILURES.append(
            f"top bar icon top edge y={after_y1} is above the system bar barrier "
            f"y={barrier} (status bar {after_sb}px, top cutout {after_cut[1]}px)"
        )

    # In landscape the tall cutout moves to the left edge; the title must clear it.
    if after_x1 is not None and after_cut[0] > 0 and after_x1 < after_cut[0] - 2:
        FAILURES.append(
            f"title left edge x={after_x1} overlaps the left cutout "
            f"({after_cut[0]}px wide)"
        )

    print("=== comparison ===")
    if before_y1 is not None and after_y1 is not None:
        print(f"  top bar icon y1: before={before_y1} after={after_y1} (status bar {after_sb}px)")
    if before_x1 is not None and after_x1 is not None:
        print(f"  title x1: before={before_x1} after={after_x1}")

    if FAILURES:
        for failure in FAILURES:
            print(f"FAIL: {failure}")
        sys.exit(1)
    print("PASS: top bar clears the status bar and the display cutout")


if __name__ == "__main__":
    main()
