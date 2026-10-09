// eBike logger box: ESP32 DevKit V1 (30 pin) + microSD SPI module, sealed lid, strap tabs.
// Parametric OpenSCAD (tested with OpenSCAD 2021.01). Change the numbers in "YOUR PARTS", render, print.
//
//   openscad -o ebike_logger_base.stl -D 'part="base"' ebike_logger_box.scad
//   openscad -o ebike_logger_lid.stl  -D 'part="lid"'  ebike_logger_box.scad
//
// part = "base" | "lid" | "print" (both side by side) | "assembly" (closed, cut open for a look)
part = "print";

/* ---------------- YOUR PARTS (measure with calipers) ---------------- */
esp = [52, 29, 13];      // ESP32 board: length, width, height from the lowest pin tip to the top of the USB socket
sd  = [48, 31, 5];       // microSD module: length, width, height (PCB + card slot)
card_overhang = 2.5;     // how far the microSD card sticks out of the module end when fully inserted
usb_plug_w = 14;         // width of the cable slot (micro-USB plug is ~12 mm)
usb_plug_h = 10;         // height of the cable slot (plug is ~8 mm)
usb_center_z = 11;       // height of the USB socket centre above the floor of the box (board sits on its pins)
esp_edge_z = 12.5;       // height above the floor of the highest point along the ESP32's long edges (header pin tips)
sd_edge_z = 5;           // same for the microSD module (its highest point along the edges)
foam_t = 2;              // thickness of the self-adhesive foam strip you stick on the rib tips

/* ---------------- MOUNTING HOLES IN THE FLOOR (measure the real ones and edit) ---------------- */
mount_holes = [46, 24];  // centre-to-centre spacing: along the box length, across its width (mm)
mount_d = 2.7;           // hole diameter: 2.7 suits M2.5 screws (use 3.4 for M3)
mount_csk = 5.4;         // countersink diameter on the inside, so a flat-head screw sits flush with the floor
mount_enable = true;
sd_pad = 1.5;            // the microSD module rests on small pads so a metal screw head cannot touch its solder joints

/* ---------------- DESIGN CHOICES ---------------- */
clr = 0.4;               // fit clearance around each board
gap = 9;                 // space between the two boards for the six wires
usb_zone = 18;           // room for the USB plug in front of the ESP32 board
wire_clear = 12;         // headroom above the ESP32: 12 for Dupont plugs, 6 if you solder the wires
card_stop = 0.6;         // gap between the card edge and the wall: the wall stops the card springing out
wall = 2.8;  floor_t = 2.4;  lid_t = 2.4;
r_out = 3;               // outer corner radius
screw_pilot = 2.8;       // hole in the base bosses for M3 self-tapping screws (use 4.2 for M3 heat-set inserts)
screw_clear = 3.4;       // hole in the lid for M3
screw_head = 6.4;        // lid recess for the screw head
boss_d = 6;
strap_w = 20;            // width of the strap or Velcro band
tab_out = 9;             // how far the strap tabs stick out
gasket = true;           // groove for a 2 mm round rubber cord or TPU gasket on the rim
$fn = 40;

/* ---------------- derived ---------------- */
ix = usb_zone + esp[0] + 2*clr + 2;        // inside length
iy = esp[1] + gap + sd[1] + 2*clr;         // inside width
ih = esp[2] + wire_clear;                  // inside height
ox = ix + 2*wall;  oy = iy + 2*wall;  oh = floor_t + ih;
esp_x = usb_zone + clr;  esp_y = clr;
sd_x1 = ix - card_overhang - card_stop;    // end of the SD module (its card slot end faces the far wall)
sd_x0 = sd_x1 - sd[0];  sd_y = clr + esp[1] + gap;
bosses = [[5, 5], [5, iy - 5], [ix - 4.5, clr + esp[1] + gap/2]];   // 3 screws; the third sits between the boards

module rrect(x, y, h, r) { hull() for (a = [r, x - r], b = [r, y - r]) translate([a, b, 0]) cylinder(r = r, h = h); }

module guide(x0, y0, l, w, h, t = 1.4) {   // low rim that keeps a board from sliding
  difference() {
    translate([x0 - t, y0 - t, 0]) cube([l + 2*t, w + 2*t, h]);
    translate([x0, y0, -0.1]) cube([l, w, h + 1]);
    for (yy = [y0 - t - 0.1, y0 + w - 0.1]) translate([x0 + l/2 - 10, yy, -0.1]) cube([20, t + 0.3, h + 1]);   // finger notches
  }
}

module base() {
  difference() {
    union() {
      rrect(ox, oy, oh, r_out);
      // strap tabs, one on each long side
      for (s = [0, 1]) translate([ox/2 - 15, s == 0 ? -tab_out : oy - 1, 0]) cube([30, tab_out + 1, 4]);
    }
    // cavity
    translate([wall, wall, floor_t]) rrect(ix, iy, ih + 1, 1.5);
    // strap slots
    for (s = [0, 1]) translate([ox/2 - strap_w/2 - 0.5, s == 0 ? -tab_out + 2.4 : oy + tab_out - 2.4 - 3.4, -1]) cube([strap_w + 1, 3.4, 6]);
    // cable slot in the end wall, in front of the USB socket
    translate([-1, wall + esp_y + esp[1]/2 - usb_plug_w/2, floor_t + usb_center_z - usb_plug_h/2]) cube([wall + 2, usb_plug_w, usb_plug_h]);
    // zip-tie slots through the floor: strain relief for the cable inside the box
    for (s = [-1, 1]) translate([wall + 7, wall + esp_y + esp[1]/2 + s*8.5 - 2, -1]) cube([1.8, 4, floor_t + 2]);
    // gasket groove on the rim
    if (gasket) translate([0, 0, oh - 1.3]) difference() {
      translate([(wall - 1.6)/2, (wall - 1.6)/2, 0]) rrect(ox - (wall - 1.6), oy - (wall - 1.6), 2, r_out - 0.6);
      translate([(wall + 1.6)/2, (wall + 1.6)/2, -0.1]) rrect(ox - (wall + 1.6), oy - (wall + 1.6), 3, r_out - 1.4);
    }
    // mounting holes: countersunk from the inside so the heads are flush with the floor
    if (mount_enable) for (sx = [-1, 1], sy = [-1, 1]) translate([ox/2 + sx*mount_holes[0]/2, oy/2 + sy*mount_holes[1]/2, -0.1]) {
      cylinder(d = mount_d, h = floor_t + 0.3);
      translate([0, 0, floor_t - (mount_csk - mount_d)/2 + 0.1]) cylinder(d1 = mount_d, d2 = mount_csk, h = (mount_csk - mount_d)/2 + 0.02);
    }
    // pilot holes
    for (b = bosses) translate([wall + b[0], wall + b[1], oh - 14]) cylinder(d = screw_pilot, h = 15);
  }
  // screw bosses
  for (b = bosses) translate([wall + b[0], wall + b[1], floor_t - 0.01]) difference() {
    cylinder(d = boss_d, h = ih - 1.3);
    translate([0, 0, ih - 1.3 - 14 + 0.02]) cylinder(d = screw_pilot, h = 15);
  }
  // pads under the microSD module (the ESP32 already rests on its pins)
  for (px = [sd_x0 + 3, sd_x1 - 9], py = [sd_y + 3, sd_y + sd[1] - 9]) translate([wall + px, wall + py, floor_t - 0.01]) cube([6, 6, sd_pad + 0.01]);
  // board guides
  translate([wall, wall, floor_t - 0.01]) {
    guide(esp_x, esp_y, esp[0], esp[1], 3.5);
    guide(sd_x0, sd_y, sd[0], sd[1], 3.5);
  }
}

module lid() {   // printed upside down: flat face on the bed, tongue and ribs pointing up
  tongue_h = 3;  tc = 0.35;
  difference() {
    union() {
      rrect(ox, oy, lid_t, r_out);
      // tongue that drops inside the walls
      translate([0, 0, lid_t - 0.01]) difference() {
        translate([wall + tc, wall + tc, 0]) rrect(ix - 2*tc, iy - 2*tc, tongue_h, 1.2);
        translate([wall + tc + 1.6, wall + tc + 1.6, -0.1]) rrect(ix - 2*tc - 3.2, iy - 2*tc - 3.2, tongue_h + 1, 0.8);
        for (b = bosses) translate([wall + b[0], wall + b[1], -0.1]) cylinder(d = boss_d + 1.6, h = tongue_h + 1);
      }
      // ribs that press the boards down when the lid is closed
      // the ribs end half a foam thickness above the board edges: the foam strip fills the rest and presses the boards down
      er = ih - (esp_edge_z + foam_t/2);  sr = ih - (sd_edge_z + foam_t/2);
      for (yy = [esp_y + 1.5, esp_y + esp[1] - 3.1]) translate([wall + esp_x + 6, wall + yy, lid_t - 0.01]) cube([esp[0] - 12, 1.6, er]);
      for (yy = [sd_y + 1.5, sd_y + sd[1] - 3.1]) translate([wall + sd_x0 + 6, wall + yy, lid_t - 0.01]) cube([sd[0] - 12, 1.6, sr]);
    }
    for (b = bosses) translate([wall + b[0], wall + b[1], -1]) {
      cylinder(d = screw_clear, h = lid_t + 2);
      translate([0, 0, lid_t - 1.6 + 1]) cylinder(d1 = screw_clear, d2 = screw_head, h = 1.7);   // countersink so the head sits flush
    }
  }
}

if (part == "base") base();
else if (part == "lid") lid();
else if (part == "assembly") {
  difference() {
    union() { base(); translate([0, oy, oh + lid_t]) rotate([180, 0, 0]) lid(); }
    translate([-1, oy/2, -1]) cube([ox + 2, oy, oh + lid_t + 5]);   // cut-away so you can see inside
  }
} else { base(); translate([0, oy + tab_out + 12, 0]) lid(); }
