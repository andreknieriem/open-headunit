#!/bin/sh
# Turns this board's USB port into an Open Headunit USB display and starts the receiver.
# Run as root. Needs the port in device mode: on a Raspberry Pi, `dtoverlay=dwc2,dr_mode=peripheral`
# in /boot/firmware/config.txt, then a reboot. Extra arguments go to ohu_display_gadget.py, e.g.
#   sudo ./setup-gadget.sh --width 1280 --height 720 --dpi 213
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
G=/sys/kernel/config/usb_gadget/ohud
FFS=/dev/ffs-ohud

modprobe libcomposite
mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config

# Unbind and tear down what a previous run left, so this can be run again.
if [ -d "$G" ]; then
    [ -n "$(cat "$G/UDC" 2>/dev/null)" ] && echo "" > "$G/UDC"
    umount "$FFS" 2>/dev/null || true
    rm -f "$G/configs/c.1/ffs.ohud"
    rmdir "$G/configs/c.1/strings/0x409" "$G/configs/c.1" "$G/functions/ffs.ohud" "$G/strings/0x409" "$G" 2>/dev/null || true
fi

mkdir -p "$G"
cd "$G"
# The Linux Foundation's multifunction composite gadget id, the usual choice for DIY gadgets.
# The app finds the display by its interface (FF/4F/44), never by these ids.
echo 0x1d6b > idVendor
echo 0x0104 > idProduct
echo 0x0100 > bcdDevice
echo 0x0200 > bcdUSB
mkdir -p strings/0x409
echo "ohud-$(cat /etc/machine-id 2>/dev/null | cut -c1-8)" > strings/0x409/serialnumber
echo "Open Headunit DIY" > strings/0x409/manufacturer
echo "Open Headunit Display" > strings/0x409/product
mkdir -p configs/c.1/strings/0x409
echo "display" > configs/c.1/strings/0x409/configuration
echo 250 > configs/c.1/MaxPower
mkdir -p functions/ffs.ohud
ln -s functions/ffs.ohud configs/c.1/

mkdir -p "$FFS"
mount -t functionfs ohud "$FFS"

# The receiver writes the descriptors, then binds the gadget itself: binding first would fail.
exec python3 "$HERE/ohu_display_gadget.py" --ffs "$FFS" --udc-path "$G/UDC" "$@"
