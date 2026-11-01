#!/usr/bin/env python3
"""Turn a wallpaper into the 1920x1080 picture the launcher shows behind the repulsor beam.

    prep_wallpaper.py <in.jpg> <out.jpg> [--no-darken]

Centre-crops to 16:9 (the same crop calibrate.html shows, so palm positions measured there still match),
scales to 1920x1080, and by default darkens the background: near-grey pixels drop to charcoal while
saturated ones (red and gold armour, blue repulsor glow) keep their colour, so the white clock stays
readable over a light picture. --no-darken keeps the colours as they are, for a picture that is already dark.
"""
import sys

import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080


def crop_16_9(im):
    w, h = im.size
    tw = round(h * 16 / 9)
    if tw <= w:
        left = (w - tw) // 2
        return im.crop((left, 0, left + tw, h))
    th = round(w * 9 / 16)
    top = (h - th) // 2
    return im.crop((0, top, w, top + th))


def darken(im):
    rgb = np.asarray(im).astype(np.float64) / 255.0
    mx, mn = rgb.max(axis=2), rgb.min(axis=2)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    mask = np.clip((sat - 0.10) / 0.18, 0, 1)
    mask = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)))
    mask = mask.astype(np.float64) / 255.0
    factor = 0.22 + 0.78 * mask
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((yy - H * 0.45) / (H / 2)) ** 2)
    vignette = np.clip(1.15 - 0.55 * d, 0.45, 1.0)
    out = rgb * factor[:, :, None] * vignette[:, :, None]
    out[:, :, 2] = np.clip(out[:, :, 2] + 0.012 * (1 - mask), 0, 1)
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8))


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    src, dst = args
    im = Image.open(src).convert("RGB")
    print(f"source {im.size[0]}x{im.size[1]}")
    im = crop_16_9(im).resize((W, H), Image.LANCZOS)
    if "--no-darken" not in argv:
        im = darken(im)
    im.save(dst, quality=93)
    print(f"wrote {dst} ({W}x{H})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
