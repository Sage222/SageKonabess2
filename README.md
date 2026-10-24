# SageKonaBess — Modernized Qualcomm Snapdragon Adreno GPU Frequency & Voltage Table Editor

**SageKonaBess** (`com.aistudio.sagekonabess.rxvq`) is a modernized Qualcomm Snapdragon Adreno GPU Frequency & Voltage Table Editor for Android, built with **Jetpack Compose** in a **Precision Dark Hardware Tuner** interface while retaining 100% of core KonaBess functionality.

---

## Pre-Compiled APK Download

The compiled, signed installable APK is tracked directly in this repository under [`releases/SageKonaBess-v1.0.apk`](releases/SageKonaBess-v1.0.apk):

| Artifact | Path | Application ID | Size | SHA-256 Checksum |
| :--- | :--- | :--- | :--- | :--- |
| **SageKonaBess v1.0 APK** | [`releases/SageKonaBess-v1.0.apk`](releases/SageKonaBess-v1.0.apk) | `com.aistudio.sagekonabess.rxvq` | ~18.0 MB | `b72e1b5d72cbffd46d6791caec4c34dea16776187f5a36ae2395d15979d1c1fb` |

### Quick Installation
```bash
adb install -r releases/SageKonaBess-v1.0.apk
```
Or download [`releases/SageKonaBess-v1.0.apk`](releases/SageKonaBess-v1.0.apk) directly onto your Android device and install via Package Installer.

---

## What's New in SageKonaBess

### 1. One-Tap Voltage Step Drop (`-1 Step`) with Bottom-2 Protection
Instead of manually editing every single GPU clock step one by one, SageKonaBess features a dedicated **Drop Voltage Steps (-1 Step)** action bar:
- **Cascade Shift to Next Lower Step's Existing Level:** Pressing **Drop Voltage Steps (-1 Step)** shifts every frequency row's voltage level (`qcom,level` or `qcom,cx-ipeak`) down to match the voltage level of the next lower frequency step in the table.
  - *Example:* If `719 MHz` is mapped to `SVS_L2` and the next step down `680 MHz` is mapped to `SVS_L1`, pressing **Drop Voltage Steps (-1 Step)** drops `719 MHz` to `SVS_L1`, drops `680 MHz` to the next lower frequency's voltage level, and cascades down the table accordingly.
- **Bottom 2 Frequencies Locked (`BOTTOM 2 LOCKED`):** The 2 lowest frequencies at the bottom of the GPU table are automatically locked and left **unchanged** to preserve idle stability and prevent low-power state (`MIN_SVS` / `LOW_SVS` / `RETENTION`) crashes.
- **Companion Quick Actions:**
  - **Step Up (+1):** Reverses a voltage step drop from the undo/step history stack or steps frequencies up one existing table level while keeping the bottom 2 frequencies unchanged.
  - **Reset Stock:** Instantly restores the active GPU bin to its unpacked stock baseline.
- **Inline Visual Diff Badges:** Every modified row displays a real-time diff badge (e.g., `DIFF: SVS_L2 → SVS_L1`) comparing the active level against the unpacked stock table.

### 2. Dynamic `ChipDefinition` Read Strictly from Existing Values
- **Zero Assumed Voltage Tiers:** SageKonaBess **never assumes** a fixed global list of voltage bins (for instance, devices that do not have or use `NOM` or `TURBO_L1` will never have those bins assumed or injected).
- All available voltage levels, `qcom,level` / `qcom,cx-ipeak` keys, speed bins, and `qcom,gpu-opp-table` curves are derived **strictly from reading the existing values** in the unpacked DTB/DTS or imported configuration.

### 3. Retained Core KonaBess Capabilities & Safe Flash / Restore Pipeline
- **Boot / Vendor_Boot Unpack & Repack (`magiskboot` + `dtc`):** Unpack the active device `boot` or `vendor_boot` partition via root shell (`su`), backup the untouched stock partition image, decompile DTB to DTS, and repack/flash modified tables using the original `KonaBessCore` pipeline (`dtc -I dts -O dtb -f`, multi-DTB linking, `magiskboot repack boot.img boot_new.img`, and `dd` to `/dev/block/bootdevice/by-name/<boot_name><slot>`).
- **Pre-Flash Confirmation, SHA-256 Readback Verification & Reboot Prompt:** Before flashing, a confirmation dialog summarizes all modified GPU steps; after flashing, the partition is read back and verified via SHA-256 checksum before offering a one-tap **Reboot System Now** confirmation.
- **One-Tap Restore to Defaults (Flash Backed-Up Boot Image):** Tap **Restore to Defaults (Flash Backed-Up Boot Image)** to flash the original backed-up boot image (`/sdcard/<boot_name>.img` or automatic pristine `<filesDir>/backup_<boot_name>.img`) directly back to your partition via `dd`, verify the partition SHA-256 readback, reset the GPU table workspace to stock defaults, and prompt for reboot.
- **Full Multi-Bin GPU Frequency Table Editor (`qcom,gpu-pwrlevels`):**
  - Edit individual clock speeds (`qcom,gpu-freq`), voltage levels (`qcom,level` / `qcom,cx-ipeak`), DDR bus frequencies (`qcom,bus-freq`, `qcom,bus-min`, `qcom,bus-max`), ACD levels (`qcom,acd-level`), and initial boot power level (`qcom,initial-pwrlevel`).
  - Insert new frequency steps, clone/duplicate existing steps, or delete steps.
- **GPU Voltage OPP Table Editor (`qcom,gpu-opp-table`):**
  - View and edit `opp-hz`, `opp-microvolt`, and `opp-peak-kBps` entries with synchronized `-1 Step` cascade support.
- **Import / Export & Raw DTS Console:**
  - Import or export shareable KonaBess table configs and full `.dts` source files via clipboard or file.

---

## Building from Source

```bash
# Run unit and Robolectric tests
gradle :app:testDebugUnitTest

# Build debug APK
gradle :app:assembleDebug
```
