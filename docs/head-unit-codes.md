# Codes for hidden menus on head units

Most Android head units keep ADB over Wi-Fi in a hidden developer or factory menu. The auto-start check (#1122) needs root or ADB to read why the unit closes the app and to apply the fixes, so it opens a guide with the codes below. The same list ships inside the app, because many head units have no internet. The app links here with a QR code.

## How to open the menu

1. Open the unit's Settings and go to About.
2. Tap Build number quickly, 7 to 10 times. On some units it is MCU version. A keypad asks for a code.
3. Type the code for your unit (see the table).
4. Turn on Developer and then ADB over Wi-Fi. Makers name it differently: "Enable WIFI Adb", "ADB over network" or "USB debugging".
5. Go back to the app and tap Check again.

Change only ADB in that menu. The rest (CAN, screen, amplifier) changes how the unit starts and can leave it unable to turn on.

On some units ADB over Wi-Fi gives full access without asking. Turn it off again if the unit joins Wi-Fi networks you do not trust.

## Codes

The code depends on the firmware, not on the brand on the box, so a code that works on one unit may not work on another one of the same brand. Only "Tested" codes were confirmed on a real unit.

| Code | Reported on | Opens | Status |
|------|-------------|-------|--------|
| 9569 | Reglink (CMZX65) | Hidden menu with Developer and ADB over Wi-Fi | Tested |
| 3368 | Pumpkin, UIS7862 | Developer options | Reported |
| 7890 | Several generic units | Developer options | Reported |
| 126 | Joying, Xtrons, Pumpkin | Factory settings | Reported |
| 8888 | Topway TS10 / TS18, 8227L | Factory settings | Reported |
| 123456 | Topway TS10, Pumpkin | Factory settings | Reported |
| 1617 | UIS7862 / UMS512 | Factory settings | Reported |
| 000000 | FYT UIS8581A | Factory settings | Reported |
| 1234 | Several generic units | Factory settings | Reported |

## Adding your code

If a code opened the menu on your unit, open an issue or a pull request with:

- the code,
- the brand and model, and the platform and firmware from the auto-start check report (Copy report),
- what it opened (a hidden menu with ADB, developer options or factory settings),
- whether ADB over Wi-Fi was there and worked.

New codes also go into `HiddenMenuCodes.kt`, so the next version of the app has them without internet.
