# Building a second screen for Open Headunit

Android Auto can send a head unit a second picture beside the main one: a navigation map or turn
card for an auxiliary display, or its own instrument-cluster view. Open Headunit asks for it when
**Settings → Second Android Auto screen** is set, and can put it in four places:

| Output | What you need | Who decodes | Cost |
|---|---|---|---|
| Another Android display | a head unit with HDMI out or USB-C DisplayPort | the head unit | nothing extra |
| Network stream | any computer, or a Pi, on the same network (or over adb) | the receiver | nothing |
| USB HDMI adapter (MacroSilicon) | an MS9120/MS912x/MS9132 adapter and an HDMI panel | the head unit | about $10-20 |
| Open Headunit USB display | a Raspberry Pi Zero 2 W (or anything that follows the protocol) and a panel | the receiver | about $15 plus the panel |

Whichever you choose, the second screen is sized once, when the phone connects. Change a setting,
then reconnect the phone.

**Role** (second setting): *Auxiliary display* lets you choose the map or the turn card. *Instrument
cluster* announces a real cluster, and Android Auto decides what it shows.

## 1. Network stream: see it working with no hardware

The quickest way to check the whole path. It is also a good debugging tool for the other outputs.

1. Settings → Second Android Auto screen → **Network stream**. Keep the stream size at 800x480 to
   start with.
2. On a computer with ffmpeg, either on the same network as the head unit or over USB debugging:

   ```bash
   adb forward tcp:5000 tcp:5000          # only over USB debugging
   ffplay -f h264 -fflags nobuffer -flags low_delay -framedrop tcp://127.0.0.1:5000
   ```

   Over the network, use the head unit's address instead of 127.0.0.1; the settings screen prints
   the exact command. VLC opens `http://<head unit>:5000/` too.
3. Connect the phone and start navigation. The picture appears within a few seconds, because
   joining asks the phone for a fresh keyframe.

In the app's log: `SecondScreen: network stream listening on port 5000`, then
`SecondScreen: network client connected from ...`. The `inbound rate over` line gains
`auxVideo=`, the bytes the phone sent for the second screen.

A Raspberry Pi on the car's WiFi works the same way: run the same `ffplay`, or
`gst-launch-1.0 tcpclientsrc host=<head unit> port=5000 ! h264parse ! v4l2h264dec ! kmssink sync=false`.

## 2. USB HDMI adapter (MacroSilicon)

Cheap "USB to HDMI" adapters built on MacroSilicon chips can be driven directly from Android with
no root and no kernel driver. The driver is adapted from
[moriceh/open-headunit](https://github.com/moriceh/open-headunit), whose author runs it in a car.

**Buying one.** Retail names say nothing reliable about the chip. Plug it into a Linux computer
first and run `lsusb`. These three work:

```
534d:6021   MacroSilicon MS9120 / MS912x
534d:0821   MacroSilicon MS912x
345f:9132   MacroSilicon MS9132
```

Anything else (DisplayLink, Fresco FL2000, other chips) will not work: those protocols are either
closed or need a kernel driver.

**Wiring.** Head unit USB port → adapter → HDMI panel. Wireless Android Auto keeps the head unit's
USB port free; with a wired phone you need a powered USB hub.

**Setup.**

1. Settings → Second Android Auto screen → **USB HDMI adapter (MacroSilicon)**. Needs Android 5.0 or
   later on the head unit.
2. **Adapter output mode**: pick the mode your panel runs at. The chip does not scale, so an 800x600
   panel needs the 800x600 mode.
3. **Adapter pixel format**: YUV is faster. RGB is sharper, but the chip cannot hold a 1920x1080 RGB
   frame, so 1080p always uses YUV.
4. When Android asks whether Open Headunit may open the adapter, tick **always** so you are not asked
   on every start.

The head unit decodes and converts every frame on its CPU, and USB 2.0 caps the adapter at roughly
20 frames a second at 1280x720. When USB cannot keep up, frames are skipped rather than delayed. A
map or a turn card needs no more than that.

In the log: `SecondScreen: MS912x mode 1280x720 (VIC 79), YUV422`, and every 300 frames
`SecondScreen: MS912x sent N frames, skipped M`.

## 3. Open Headunit USB display (Raspberry Pi Zero 2 W)

This is the open route: the head unit only forwards the H.264, and the Pi decodes it in hardware. The
protocol is short and documented in [USB-DISPLAY-PROTOCOL.md](USB-DISPLAY-PROTOCOL.md). Anything
that implements it works, and the app finds it by its USB interface, not by brand.

**Parts.**

- Raspberry Pi Zero 2 W (hardware H.264 decode up to 1080p30, and a USB port that can be a device).
- A panel: mini-HDMI, or a DSI or DPI panel. **800x480, 1280x720 or 1920x1080 panels are
  easiest**: Android Auto encodes those sizes exactly, so nothing has to be cropped.
- A micro-USB **data** cable from the Pi's port labelled `USB` (not `PWR`) to the head unit. That one
  cable powers the Pi, too, if the head unit's port gives 500 mA or more. Otherwise, power the Pi
  from `PWR` as well.
- A micro-SD card with Raspberry Pi OS Lite (Bookworm).

**Set up the Pi.**

1. Put the Pi's USB port in device mode. Add this line to `/boot/firmware/config.txt`, then reboot:

   ```
   dtoverlay=dwc2,dr_mode=peripheral
   ```

2. Install the decoder and copy the receiver:

   ```bash
   sudo apt install -y python3 gstreamer1.0-tools gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
   sudo mkdir -p /opt/ohu-display && sudo cp pi-receiver/* /opt/ohu-display/
   ```

3. Try it by hand, with your panel's size:

   ```bash
   sudo /opt/ohu-display/setup-gadget.sh --width 1024 --height 600 --dpi 160
   ```

   It prints `bound to ...` and `waiting for the head unit`.
4. Make it start at boot: edit the size on the `ExecStart` line of `ohu-display.service`, then

   ```bash
   sudo cp /opt/ohu-display/ohu-display.service /etc/systemd/system/
   sudo systemctl enable --now ohu-display
   ```

**Set up the app.** Settings → Second Android Auto screen → **Open Headunit USB display**. Plug the
Pi in, tap **Allow access and read the USB display**, accept the prompt (tick *always*), and the row
shows the panel's size. Connect the phone.

On the Pi: `head unit read the display info`, `session started`, `starting decoder`, then
`N frames shown` every 300 frames. In the app's log: `SecondScreen: USB display 1024x600 attached`.

**Density.** `--dpi` sets how large Android Auto draws. Start with 160 for a 7-inch 1024x600 panel,
213 for 1280x720 at the same size, and raise it if the text is too small.

**Another decoder.** `--sink` takes any command that reads H.264 on its standard input, for example
`--sink "ffplay -f h264 -fflags nobuffer -flags low_delay -framedrop -"` on a Pi with a desktop.

**Writing your own device.** Read [USB-DISPLAY-PROTOCOL.md](USB-DISPLAY-PROTOCOL.md). Run
`python3 -m unittest test_ohud_protocol` in `pi-receiver/` to check your framing against the same
byte vectors the app is tested with.

## Troubleshooting

| What you see | Where to look |
|---|---|
| Nothing on any output | `grep "SecondScreen:" log`: `output is not available, so one display is announced` means the adapter or display was not found when the phone connected |
| The phone never sends the second picture | `grep "VIDEO_AUX" log`: look for a `Media Sink Setup Request` and `Granting video focus on VIDEO_AUX`. Navigation must be running for a map or turn card |
| The session drops when the second screen is on | `grep "Critical error" log`, and report it with the log attached |
| Picture freezes, then recovers after a while | the receiver fell behind: look for `fell behind; resuming at the next keyframe` |
| MS912x panel stays black | `sees no HDMI display attached` means the adapter read no panel on its HDMI side: check the cable and that the panel is on, then reconnect the phone |
