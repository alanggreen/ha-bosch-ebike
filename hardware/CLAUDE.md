# Enclosure guide

Read `../CLAUDE.md` first.

`enclosure/ebike_logger_box.scad` is a parametric OpenSCAD model of a box for the ESP32 DevKit V1 and the microSD module. The README in `enclosure/` explains the design, the shopping list and the assumptions.

## Rules
- Every real-world number is a parameter at the top of the file. **Do not guess measurements**: ask the owner for caliper values and say which numbers are still estimates.
  Open items: the true hole spacing and diameter of the ESP32 board (46 x 24 mm and 2.7 mm are unverified), header pin length (assumed 8.5 mm), SD module height (assumed 5 mm).
- The ESP32's pins point **down**; the board sits on 20 mm pillars with the female connectors below it. Screws come from underneath, flat head countersunk (M2.5, length includes the head).
  The model prints the minimum screw length; keep that formula in step with the stack of parts.
- Before committing a change: build both STLs with `scripts/enclosure_build.ps1`, check there are no OpenSCAD warnings and "Simple: yes", and look at the preview images (a section view shows pillars and holes).
- Anything added after the cavity is cut (pillars, rims, bosses) must be added **outside** the `difference()` that cuts the cavity, or it disappears.
- The enclosure agent never touches electronics or firmware. It is splash resistant, not waterproof; do not claim otherwise.
- Say clearly that the design is **not test printed** until the owner reports a print.
