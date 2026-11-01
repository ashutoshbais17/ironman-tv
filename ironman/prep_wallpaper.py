#!/usr/bin/env python3
"""Turn a wallpaper into the 1920x1080 picture the launcher shows behind the repulsor beam.

    prep_wallpaper.py <in.jpg> <out.jpg> [--no-darken] [--cutout <foreground.png>]

Centre-crops to 16:9 (the same crop calibrate.html shows, so palm positions measured there still match),
scales to 1920x1080, and by default darkens the background: near-grey pixels drop to charcoal while
saturated ones (red and gold armour, blue repulsor glow) keep their colour, so the white clock stays
readable over a light picture. --no-darken keeps the colours as they are, for a picture that is already dark.

--cutout also writes the figure on its own, cut out of the finished picture, so the launcher's ambient_fx
particles can pass behind it, and prints "foreground_origin x,y", where its top-left corner sits. The figure
is what stands out from a plain, unsaturated background; on a busy picture no cut-out is written.
"""
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

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


def saturation(im):
    rgb = np.asarray(im).astype(np.float64) / 255.0
    mx, mn = rgb.max(axis=2), rgb.min(axis=2)
    return np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)


def darken(im):
    rgb = np.asarray(im).astype(np.float64) / 255.0
    sat = saturation(im)
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


def figure_mask(im):
    """The figure as a soft-edged mask: saturated pixels, gaps closed, and anything the background can't reach
    from the picture's edges (the figure's grey and dark parts) filled in."""
    solid = Image.fromarray(((saturation(im) > 0.16) * 255).astype(np.uint8))
    solid = solid.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5)).filter(ImageFilter.MedianFilter(5))
    for seed in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1), (W // 2, 0), (10, H // 2), (W - 10, H // 2)]:
        if solid.getpixel(seed) == 0:
            ImageDraw.floodfill(solid, seed, 128)
    solid = Image.fromarray(np.where(np.asarray(solid) == 128, 0, 255).astype(np.uint8))
    return solid.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(1.5))


def main(argv):
    cutout = None
    if "--cutout" in argv:
        i = argv.index("--cutout")
        if i + 1 >= len(argv):
            print("--cutout needs a file", file=sys.stderr)
            return 2
        cutout = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    args = [a for a in argv if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    src, dst = args
    im = Image.open(src).convert("RGB")
    print(f"source {im.size[0]}x{im.size[1]}")
    im = crop_16_9(im).resize((W, H), Image.LANCZOS)
    out = im if "--no-darken" in argv else darken(im)
    out.save(dst, quality=93)
    print(f"wrote {dst} ({W}x{H})")
    if cutout:
        mask = figure_mask(im)
        share = (np.asarray(mask) > 128).mean()
        box = mask.getbbox()
        if box is None or not 0.01 < share < 0.5:
            print(f"no cut-out: the figure doesn't stand out from the background ({share:.0%} of the picture)")
        else:
            # From the saved JPEG, so the cut-out matches what the TV decodes pixel for pixel.
            fg = Image.open(dst).convert("RGBA")
            fg.putalpha(mask)
            fg.crop(box).save(cutout, optimize=True)
            print(f"wrote {cutout} ({box[2] - box[0]}x{box[3] - box[1]})")
            print(f"foreground_origin {box[0]},{box[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
