#!/usr/bin/env python3
"""Generate launcher PNG icons for each density from the vector design."""

import os
import math

# Try to import PIL/Pillow
try:
    from PIL import Image, ImageDraw, ImageFont, ImageFilter
except ImportError:
    print("Pillow not found, installing...")
    import subprocess
    subprocess.run(["pip3", "install", "Pillow", "--break-system-packages"], check=True)
    from PIL import Image, ImageDraw, ImageFont, ImageFilter

# Design is based on 108x108 viewport
# New modern flat design for commercial software:
#   Background: Dark elegant slate/blue gradient (simulated) or sleek solid: #1E293B (Slate 800)
#   Main icon: A dynamic stylized double forward chevron or interconnected nodes.
#              Using a bright accent color: Cyan #06B6D4 with bright Blue #3B82F6

BG_COLOR = (30, 41, 59)          # #1E293B
ACCENT1 = (59, 130, 246)         # #3B82F6
ACCENT2 = (6, 182, 212)          # #06B6D4
WHITE = (255, 255, 255)

# Densities: (folder_suffix, icon_size_px)
DENSITIES = [
    ("mdpi",    48),
    ("hdpi",    72),
    ("xhdpi",   96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RES_DIR = os.path.join(BASE_DIR, "app", "src", "main", "res")

VIEWPORT = 108.0


def scaled(value, size):
    """Scale a coordinate from 108-viewport to target size."""
    return value / VIEWPORT * size


def draw_rounded_rect(draw, x0, y0, x1, y1, radius, fill):
    """Draw a filled rounded rectangle."""
    r = radius
    draw.rectangle([x0 + r, y0, x1 - r, y1], fill=fill)
    draw.rectangle([x0, y0 + r, x1, y1 - r], fill=fill)
    draw.ellipse([x0, y0, x0 + 2*r, y0 + 2*r], fill=fill)
    draw.ellipse([x1 - 2*r, y0, x1, y0 + 2*r], fill=fill)
    draw.ellipse([x0, y1 - 2*r, x0 + 2*r, y1], fill=fill)
    draw.ellipse([x1 - 2*r, y1 - 2*r, x1, y1], fill=fill)


def generate_icon(size):
    # Draw Background
    img = Image.new("RGBA", (size, size), BG_COLOR + (255,))
    draw = ImageDraw.Draw(img)

    def s(v):
        return scaled(v, size)

    # Modern flat design: Interlocking abstract shapes or double chevrons to symbolize "Port" and "Forward".
    
    # 1. First chevron (Left) - Accent 1 (Blue)
    # 2. Second chevron (Right) - Accent 2 (Cyan)
    
    # Let's draw stylized overlapping arrows.
    # We will use polygons to draw thick chevron-like structures.
    # Chevron consists of 3 points for inner bend, 3 for outer bend. Or just a thick line.
    
    # To get nice anti-aliasing on thick lines, we scale up by an antialias factor.
    factor = 4
    canvas_size = size * factor
    temp_img = Image.new("RGBA", (canvas_size, canvas_size), BG_COLOR + (255,))
    tdraw = ImageDraw.Draw(temp_img)
    
    def ts(v):
        return scaled(v, canvas_size)

    # Left chevron points (Outer: top left, mid right, bottom left. Inner: ...)
    # Let's use simple thick line drawing which might look better.
    lw = ts(12)  # Line width
    
    # Chevron 1 (Blue)
    c1_p1 = (ts(34), ts(28))
    c1_p2 = (ts(54), ts(54))
    c1_p3 = (ts(34), ts(80))
    tdraw.line([c1_p1, c1_p2, c1_p3], fill=ACCENT1, width=int(lw), joint="curve")

    # Chevron 2 (Cyan)
    c2_p1 = (ts(54), ts(28))
    c2_p2 = (ts(74), ts(54))
    c2_p3 = (ts(54), ts(80))
    tdraw.line([c2_p1, c2_p2, c2_p3], fill=ACCENT2, width=int(lw), joint="curve")

    # Add a glowing or connecting dot (representing a "Port")
    dot_r = ts(8)
    tdraw.ellipse([c1_p1[0] - dot_r, c1_p1[1] - dot_r, c1_p1[0] + dot_r, c1_p1[1] + dot_r], fill=WHITE)
    tdraw.ellipse([c2_p3[0] - dot_r, c2_p3[1] - dot_r, c2_p3[0] + dot_r, c2_p3[1] + dot_r], fill=WHITE)

    # Downsample for anti-aliasing
    temp_img = temp_img.resize((size, size), Image.Resampling.LANCZOS)
    img.paste(temp_img, (0, 0))

    return img


def main():
    for density, size in DENSITIES:
        folder = os.path.join(RES_DIR, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)

        img = generate_icon(size)
        out_path = os.path.join(folder, "ic_launcher.png")
        img.save(out_path, "PNG")
        print(f"Saved {density} ({size}x{size}): {out_path}")

        # Also save ic_launcher_round.png
        out_round_png = os.path.join(folder, "ic_launcher_round.png")
        img.save(out_round_png, "PNG")
        print(f"Saved {density} round ({size}x{size}): {out_round_png}")
        
        # Remove old webp files to avoid duplicate resources build error
        out_webp = os.path.join(folder, "ic_launcher.webp")
        out_round_webp = os.path.join(folder, "ic_launcher_round.webp")
        if os.path.exists(out_webp):
            os.remove(out_webp)
        if os.path.exists(out_round_webp):
            os.remove(out_round_webp)

    print("\nDone! All icons generated.")


if __name__ == "__main__":
    main()
