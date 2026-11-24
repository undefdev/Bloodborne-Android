# Xbox-style button prompts

Bloodborne shows PlayStation symbols. With an Xbox-layout pad (the Razer Kishi V3 Pro, or any pad the app maps
A B X Y to cross circle square triangle), `tools/android/button_icons.py` makes a mod that shows the Xbox buttons
instead, in the game's own badge style:

| The game shows | The mod shows |
| --- | --- |
| cross, circle, square, triangle | A, B, X, Y (green, red, blue, yellow) |
| L1 R1 L2 R2 | LB RB LT RT |
| L3 R3 (stick clicks) | LS RS |
| Options | Menu (three lines) |
| touchpad | View (two squares): the app maps View/Select to the touchpad |
| the sticks (L, R), the d-pad, the trademark signs | unchanged |

## Make and install it

On a computer with Python 3 (standard library only), from a copy of the game's file:

```sh
adb pull /sdcard/Bloodborne/CUSA03173/dvdroot_ps4/menu/common.tpf.dcx
python3 tools/android/button_icons.py common.tpf.dcx out/common.tpf.dcx
adb shell mkdir -p "'/sdcard/Bloodborne/mods/Xbox button icons/menu'"
adb push out/common.tpf.dcx "/sdcard/Bloodborne/mods/Xbox button icons/menu/common.tpf.dcx"
```

The game picks it up at its next start (the log says `Mods: 1 applied: Xbox button icons`); the app's Mods menu turns
it off again. The output is made from your game files: do not share it. Only CUSA03173 v1.09 is checked; the tool
refuses a file whose textures are not the ones it expects.

## Where the symbols are

All in `menu/common.tpf.dcx` (a DCX: zlib, "DFLT"; inside a PS4 TPF, textures tiled for the GPU):

- **Key guides and texts**: 22 textures `KG_*`, 32x32 BC7. The menus import them by name (`DefineExternalImage2` in
  the `.gfx` files) and the texts embed them (`<img src="KG_R1" vspace='-8'>` in `msg/*/menu.msgbnd.dcx`). The names
  follow the Japanese layout: `KG_OK` is the circle and `KG_Cancel` the cross; the face buttons are `KG_R_L` (square)
  and `KG_R_U` (triangle), the touchpad halves `KG_TP_L`/`KG_TP_R`.
- **Prompts in the world** ("View Notes", "Open"): the HUD (`menu/fe.gfx`, `Fade/ActionButton1/Button_0`) cuts three
  28x28 areas out of the sheet `MENU_Common_00091` (1024x512 BC7): circle at (660,122), cross at (693,122), L3 at
  (725,122). The tool redraws only the 4x4 blocks that cover them (the pixels around them are transparent; a checksum
  of those blocks guards against another file).

Not changed: `MENU_Common_00110` (the gesture menu's DualShock pictograms for tilting or shaking the pad, which the
Kishi does not have) and a glossy triangle and cross on `MENU_Common_00092` that no menu uses.

## The texture format

- PS4 tiling of block-compressed textures: 8x8-block tiles in row order, Morton (Z) order inside a tile. That holds
  for every size seen (32x32 to 2048x2048).
- The icons are BC7: per 4x4 block the better of mode 6 (colour and alpha on one line, 4-bit indices) and mode 5
  (colour and alpha on lines of their own, 2-bit indices). The badges' edges need mode 5: transparent black outside,
  a dark shadow, the lit rim and the fill do not lie on one line. Blocks are compared by the error that shows
  (alpha-premultiplied colour plus alpha), so the colour of a transparent pixel is free.
- The textures keep their size and format, so the TPF's layout does not change; only the DCX's compressed size does.

The icons (`tools/android/button_icons/*.png`) are drawn by `make_icons.py` (Pillow, only to remake them): badges to
the measured look of the originals (a disc of radius 13, a rounded rect, fill 46/46/46, lit at the top, dark at the
bottom, a soft shadow) with no pixel taken from the game. Tests: `python3 -I tests/test_button_icons.py`.
