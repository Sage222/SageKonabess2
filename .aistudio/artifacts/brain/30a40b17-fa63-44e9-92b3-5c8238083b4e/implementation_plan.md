# SageKonaBess — Modernized Adreno GPU Frequency & Voltage Tuner

SageKonaBess modernizes the complete KonaBess Android utility into a high-precision dark hardware tuning interface while preserving every core capability—boot/vendor_boot image unpacking, Device Tree (`dtb`/`dts`) parsing, multi-bin GPU frequency & voltage table editing, config import/export, image backup, and repacking/flashing—augmented with a one-tap voltage step shifter that drops frequency voltage bins to the next lower frequency step's existing voltage level while keeping the bottom two frequencies untouched.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and engineering decisions incorporate your confirmed preferences and your revision regarding dynamic `ChipDefinition` and voltage level reading:

- **Confirmed Decision 1 — Never Assume `ChipDefinition` or Voltage Levels (Read Strictly from Existing Values)**:
  - The app **never assumes** a universal voltage dictionary (for example, some devices do not have or use `NOM` or `TURBO_L1`, while others do).
  - Instead, `ChipDefinition` and the active voltage level mappings are derived strictly from the default reading of the unpacked DTB/DTS (`compatible` / `model` / `qcom,msm-id` detection and the actual `qcom,level`, `qcom,cx-ipeak`, and `qcom,gpu-opp-table` values present in the device's existing table), preserving KonaBess's exact per-chip `ChipInfo` behavior and existing table values.
- **Confirmed Decision 2 — One-Tap Voltage Step Drop Logic (`-1 Step` & Bottom 2 Guard)**:
  - Pressing **Drop Voltage Steps (-1 Step)** shifts the voltage level of each frequency row `i` (sorted descending from highest to lowest clock speed) to adopt the **existing voltage value** of the next lower frequency step `i + 1` (e.g., `719MHz @ SVS_L2` adopts `680MHz`'s `SVS_L1`), while strictly **keeping the 2 lowest frequencies at the bottom of the table unchanged**. Because it copies exclusively from the next lower frequency's existing value in the read table, it never introduces unsupported voltage bins that the device doesn't use.
- **Confirmed Decision 3 — Companion Quick Actions & Visual Diff Badges**:
  - Alongside the `-1 Step` drop action, the editor toolbar includes a **Step Up (+1 Step)** action (reversing the step shift or shifting exclusively using existing read table levels while respecting the bottom-2 lock) and a **Reset to Stock Table** action, paired with inline **visual diff badges** (`SVS_L2 → SVS_L1`) on every modified frequency row.
- **Confirmed Decision 4 — Precision Dark Hardware Tuner Aesthetic**:
  - The UI uses an obsidian-slate dark surface hierarchy with high-contrast telemetry accents and crisp monospace numeric readouts for frequencies (`MHz` / `Hz`), voltage bins, and bus parameters.

---

## 1. Overview & Core Concept

- **What It Does**: SageKonaBess is a Qualcomm Snapdragon Adreno GPU frequency and voltage table editor that retains 100% of KonaBess's core functionality. It unpacks device `boot.img` / `vendor_boot.img` partitions, decompiles Device Tree Blobs (`dtb`) into editable Device Tree Source (`dts`) files, reads the device's existing `qcom,gpu-pwrlevels`, `qcom,gpu-pwrlevel-bins`, and `qcom,gpu-opp-table` structures, and enables editing of frequencies, voltage levels, DDR bus frequencies (`qcom,bus-freq`, `qcom,bus-min`, `qcom,bus-max`), and ACD levels (`qcom,acd-level`), followed by repacking and flashing or exporting configuration backups.
- **Target Audience / Persona**: Android power users, kernel/ROM enthusiasts, and hardware tuners optimizing Qualcomm Snapdragon GPU thermal efficiency, battery life, and sustained performance.
- **Key Value**: Eliminates tedious, error-prone row-by-row manual undervolting by introducing a single button that cascades each frequency's voltage level down to the next lower frequency's existing voltage level while protecting the bottom 2 frequencies.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Device Detection & Image Acquisition Flow**:
   - On launch, KonaBess unpacks and inspects the device's active `boot` / `vendor_boot` DTB (or an imported boot image / `.dts` file) and reads the hardware identity (`model` / `compatible` / `qcom,msm-id`) and existing `qcom,gpu-pwrlevels` values without assuming any preset voltage bins not used by the target device.
   - A prominent **Backup Old Image** action allows saving an untouched copy of the partition image before any modifications.

2. **GPU Frequency & Voltage Table Editor + One-Tap Step Drop Flow**:
   - Selecting a GPU Bin (`Bin 0`, `Bin 1`, etc. read from `qcom,gpu-pwrlevels`) opens the modernized **Frequency & Voltage Table Editor**.
   - At the top of the table sits the **Voltage Step Quick-Tuner Bar** featuring:
     - **Drop Voltage (-1 Step)**: Cascades every frequency's voltage level down to the next lower frequency's existing voltage level (`row[i].level = row[i + 1].level`), leaving the bottom 2 frequencies locked and unchanged.
     - **Raise Voltage (+1 Step)**: Reverses the step drop from the step history stack (or shifts upward exclusively within the existing table's read voltage levels while keeping the bottom 2 frequencies locked).
     - **Reset to Stock**: Instantly restores all frequencies and voltage levels in the bin to the unpacked baseline snapshot read from the device.
   - Each frequency row displays:
     - **Clock Speed Badge** in `MHz` (and exact `Hz` sub-label), with a lock indicator on the bottom 2 rows (`LOCKED`).
     - **Voltage Level Pill** showing the exact voltage level label/value decoded from the device's existing table (e.g., `SVS_L2`, `SVS_L1`).
     - **Visual Diff Badge** whenever a row's voltage level or clock speed differs from the unpacked stock table (e.g., `SVS_L2 → SVS_L1`).
   - Tapping any individual frequency row opens the **Precision Level Inspector Sheet**, allowing granular editing of frequency (`qcom,gpu-freq`), voltage level (`qcom,level` / `qcom,cx-ipeak` scoped to the levels valid for the read chip/table), bus frequency (`qcom,bus-freq`), bus min/max (`qcom,bus-min`, `qcom,bus-max`), ACD level (`qcom,acd-level`), row duplication, new frequency insertion, reordering, or row deletion.

3. **GPU Voltage Table (`GpuVoltEditor`) & Repack/Flash Flow**:
   - For chips that define an explicit voltage-to-frequency table (`qcom,gpu-opp-table`), the **GPU Voltage Table Editor** reads and edits the exact `opp-hz` and `opp-microvolt` entries from the unpacked DTS.
   - The **Import / Export Config** workspace lets users import or export KonaBess configurations via clipboard or file.
   - Clicking **Repack & Flash Image** compiles the modified DTS back into DTB, repacks the boot/vendor_boot image, and flashes it to the target partition.

### Visual Identity & Theme

- **Aesthetic Direction**: *Precision Dark Hardware Tuner* — deep obsidian surfaces (`#0B0E14`), elevated slate cards (`#121722`), crisp 1dp structural borders (`#1E2638`), and high-contrast semantic status pills.
- **Color Palette & Mood**:
  - **Primary Accent**: Electric Telemetry Cyan (`#00E5FF`) for active controls and frequency highlights.
  - **Undervolt / Step-Drop Accent**: High-Efficiency Emerald (`#00E676`) for `-1 Step` undervolt badges and dropped voltage states.
  - **Elevated / Warning Accents**: Amber Gold (`#FFB300`) and Crimson Coral (`#FF5252`) for higher voltage states and flash confirmations.
- **Typography & Hierarchy**:
  - **Display & Section Headers**: `Space Grotesk` (bundled locally via `res/font`) for hardware headers and section titles.
  - **Telemetry & Numeric Readouts**: `JetBrains Mono` (bundled locally via `res/font`) for `MHz`/`Hz` clock values, DTS property keys, and voltage level diff badges.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Strictly Data-Driven `ChipDefinition` & Voltage Levels from Read DTS Values**
  - *Chosen Approach*: When unpacking a DTB/DTS image, the parser reads the device tree's actual `model`/`compatible` tokens alongside every `qcom,level` / `qcom,cx-ipeak` / `qcom,gpu-opp-table` value present in the unpacked file. The available voltage levels for that session are constructed from the default reading of the existing values and the matched KonaBess `ChipInfo` table—never assuming that tiers like `NOM` or `TURBO_L1` exist unless they are part of that chip's actual read definition.
  - *Why*: Different Snapdragon revisions and OEM device trees omit or subset specific RPMh voltage rails (e.g., skipping `NOM` or `TURBO_L1`). Reading directly from the existing values guarantees 100% hardware compatibility and prevents writing invalid level integers into the DTB.

- **Decision 2: Cascade Shift Algorithm with Bottom-2 Frequency Lock**
  - *Chosen Approach*: Given the read frequency table sorted from highest frequency (index `0`) to lowest frequency (index `N - 1`), pressing **Drop Voltage (-1 Step)** sets `row[i].voltageLevel = currentSnapshot[i + 1].voltageLevel` for all indices `0 <= i <= N - 3`, while keeping `row[N - 2]` and `row[N - 1]` unchanged.
  - *Why*: Directly implements your example (`719MHz @ SVS_L2` takes `680MHz`'s `SVS_L1`) using only the voltage levels that already exist in your device's frequency table, while leaving the bottom two frequencies untouched.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         SageKonaBess UI Layer (Compose)                   │
│                                                                           │
│  ┌─────────────────────────┐  ┌────────────────────────────────────────┐  │
│  │  Hardware & Boot Header │  │     Navigation Tabs & Bin Selector     │  │
│  │  • Detected DTB Identity│  │  • Freq Table Editor  • Volt Table     │  │
│  │  • Unpack / Backup /    │  │  • Import/Export Cfg  • Raw DTS / Logs │  │
│  │    Repack & Flash Bar   │  └───────────────────┬────────────────────┘  │
│  └────────────┬────────────┘                      │                       │
│               │                                   ▼                       │
│               │               ┌────────────────────────────────────────┐  │
│               │               │     Voltage Step Quick-Tuner Bar       │  │
│               │               │  [ -1 Step Drop ] [ +1 Step ] [Reset]  │  │
│               │               │  • Bottom 2 Frequencies Locked         │  │
│               │               └───────────────────┬────────────────────┘  │
│               │                                   ▼                       │
│               │               ┌────────────────────────────────────────┐  │
│               │               │   Frequency & Voltage Table List       │  │
│               │               │  • Row 0: 719 MHz | SVS_L2 → SVS_L1    │  │
│               │               │  • Row 1: 680 MHz | SVS_L1 → SVS       │  │
│               │               │  • ...                                 │  │
│               │               │  • Row N-2: [Existing Level] [LOCKED]  │  │
│               │               │  • Row N-1: [Existing Level] [LOCKED]  │  │
│               │               └───────────────────┬────────────────────┘  │
│               │                                   ▼                       │
│               │               ┌────────────────────────────────────────┐  │
│               │               │   Precision Level Inspector Modal      │  │
│               │               │  • Frequency (Hz/MHz) • Voltage Level  │  │
│               │               │  • Bus Freq/Min/Max   • ACD Level      │  │
│               │               └────────────────────────────────────────┘  │
└───────────────┼───────────────────────────────────┬───────────────────────┘
                ▼                                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                      KonaBessViewModel (MVVM StateFlow)                   │
│  • Dynamic ChipInfo from Read DTS     • Stock Snapshot vs Working Table   │
│  • Undo / Step History Stack          • Shell & Repack Execution Logs     │
└───────────────┬───────────────────────────────────┬───────────────────────┘
                ▼                                   ▼
┌─────────────────────────────────────┐ ┌───────────────────────────────────┐
│     KonaBess Core & DTS Engine      │ │   Dynamic Chip & Level Resolver   │
│  • DtsParser / DtsSerializer        │ │  • Reads Existing DTS Levels First│
│  • qcom,gpu-pwrlevels AST Builder   │ │  • Maps Only Levels Present in    │
│  • Boot/Vendor_Boot Shell Runner    │ │    Target Device / ChipInfo       │
│  • Config Import/Export Codec       │ │  • Lossless Raw Value Preservation│
└─────────────────────────────────────┘ └───────────────────────────────────┘
```

### Data Model & State

- **`DynamicChipProfile` (Read from Existing Values)**:
  - Constructed when the DTB/DTS is unpacked and parsed.
  - Extracts the exact `levelKey` (`qcom,level` or `qcom,cx-ipeak`), the ordered list of distinct voltage levels present in the unpacked table (plus the matched KonaBess `ChipInfo` label decoder for those specific numeric values, or raw numeric/label representation if custom), and whether the device tree contains a separate `qcom,gpu-opp-table`.
- **`GpuPowerBin`**:
  - Represents a parsed `qcom,gpu-pwrlevels-X` node containing its `id`, `headerLines` (preserving non-level bin properties like `qcom,initial-pwrlevel` or `qcom,throttle-pwrlevel`), `levels: List<GpuFreqRow>`, and an immutable `stockLevels: List<GpuFreqRow>` snapshot captured directly from the unpacked DTS.
- **`GpuFreqRow`**:
  - Represents a single `qcom,gpu-pwrlevel@X` node with `frequencyHz`, `voltageLevelCode`, `voltageLabel` (resolved from the read profile), `busFreq`, `busMin`, `busMax`, `acdLevel`, and `rawExtraLines` (preserving every single original DTS property line verbatim).

### Interactive Component & State Mapping

- **`dropVoltageStepsOneLevel()` (`-1 Step` Handler)**:
  1. Reads the active `GpuPowerBin.levels` list (`0` to `N - 1`, ordered from highest to lowest frequency as structured in `qcom,gpu-pwrlevels`).
  2. Saves the current table snapshot onto `stepHistoryStack`.
  3. For each index `i` from `0` to `N - 3`: sets `newRow[i]`'s voltage level to `currentRow[i + 1]`'s existing voltage level.
  4. Leaves `newRow[N - 2]` and `newRow[N - 1]` completely untouched.
  5. Writes the updated values back into the in-memory DTS AST and updates the per-row visual diff badge comparing against `stockLevels[i]`.
- **`raiseVoltageStepsOneLevel()` (`+1 Step` Handler)**:
  1. If `stepHistoryStack` is non-empty, pops the previous table state so `-1 Step` followed by `+1 Step` is a 100% lossless round-trip.
  2. If pressed from the baseline state, shifts `row[i]` (`1 <= i <= N - 3`) to adopt `currentRow[i - 1]`'s existing voltage level from the table (and steps `row[0]` up only if a higher level exists in the device's read profile), while keeping the bottom 2 frequencies untouched.
- **`resetTableToStock()`**:
  1. Restores `levels` to the exact `stockLevels` read during unpacking and clears `stepHistoryStack`.
