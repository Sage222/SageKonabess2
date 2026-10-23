package com.example.konabess.core

import android.content.Context
import android.os.Build
import com.example.konabess.model.DetectedDtb
import com.example.konabess.model.KonaBessChipType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Authentic port of `libxzr/KonaBess` `KonaBessCore.java`.
 *
 * Executes the real `magiskboot` and `dtc` binaries against the device's `vendor_boot` or `boot`
 * partition via `su`:
 * 1. `getBootImage`: probes `vendor_boot<slot>` first, then falls back to `boot<slot>`.
 * 2. `unpackBootImage`: runs `magiskboot unpack boot.img` to extract `dtb` / `kernel_dtb`.
 * 3. `dtb_split`: splits concatenated FDT blobs (`0xD00DFEED`) into `0.dtb .. (N-1).dtb`.
 * 4. `dtb2dts`: decompiles each `.dtb` to `.dts` via `dtc -I dtb -O dts` and saves `.dts.stock`.
 * 5. `checkDevice`: matches each `.dts` against `ChipInfo.type` and `ro.boot.dtb_idx`.
 * 6. `dts2bootImage` & `writeBootImage`: compiles `.dts -> .dtb`, links `dtb`, repacks `boot_new.img`,
 *    and flashes back to `/dev/block/bootdevice/by-name/<boot_name><slot>`.
 *
 * NEVER generates or substitutes fake/synthetic frequencies or voltage levels.
 */
class KonaBessShellEngine(private val context: Context) {

    private enum class DtbType {
        DTB,
        KERNEL_DTB,
        BOTH
    }

    data class ShellExecutionResult(
        val success: Boolean,
        val hasRootAccess: Boolean,
        val activePartition: String,
        val bootName: String,
        val dtsContent: String,
        val stockDtsContent: String,
        val detectedDtbs: List<DetectedDtb>,
        val selectedDtbId: Int,
        val logs: List<String>
    )

    private val filesDir: File
        get() = context.filesDir

    private var bootName: String = "vendor_boot"
    private var dtbNum: Int = 0
    private var dtbType: DtbType = DtbType.DTB
    private var cachedDtbs: List<DetectedDtb> = emptyList()
    private var activeDtbId: Int = -1

    init {
        // Purge any legacy synthetic workspace files from older builds so fake values never persist
        try {
            val legacyDir = File(context.filesDir, "konabess_workspace")
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveBinaryPath(name: String): String {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val soFile = File(nativeLibDir, "lib$name.so")
        if (soFile.exists() && soFile.canExecute()) {
            return soFile.absolutePath
        }
        val extracted = File(filesDir, name)
        if (!extracted.exists() || extracted.length() == 0L) {
            try {
                context.assets.open(name).use { input ->
                    FileOutputStream(extracted).use { output ->
                        input.copyTo(output)
                    }
                }
                extracted.setExecutable(true, false)
            } catch (_: Exception) {
            }
        } else {
            extracted.setExecutable(true, false)
        }
        return extracted.absolutePath
    }

    private fun setupEnv(logs: MutableList<String>): Pair<String, String> {
        val dtcPath = resolveBinaryPath("dtc")
        val magiskbootPath = resolveBinaryPath("magiskboot")
        // Also ensure filesDir copies exist with 755 permissions as in original KonaBessCore.setupEnv
        for (assetName in listOf("dtc", "magiskboot")) {
            val target = File(filesDir, assetName)
            try {
                if (!target.exists() || target.length() == 0L) {
                    context.assets.open(assetName).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                target.setReadable(true, false)
                target.setExecutable(true, false)
            } catch (e: Exception) {
                logs.add("Asset setup note ($assetName): ${e.message}")
            }
        }
        return Pair(dtcPath, magiskbootPath)
    }

    private fun cleanWorkingFiles() {
        val children = filesDir.listFiles() ?: return
        for (f in children) {
            if (f.name.endsWith(".dtb") ||
                f.name.endsWith(".dts") ||
                f.name.endsWith(".stock") ||
                f.name == "boot.img" ||
                f.name == "boot_new.img" ||
                f.name == "dtb" ||
                f.name == "kernel_dtb" ||
                f.name == "kernel" ||
                f.name == "ramdisk.cpio" ||
                f.name == "header"
            ) {
                f.delete()
            }
        }
    }

    suspend fun unpackAndReadDeviceDts(
        forceReloadFromPartition: Boolean = false,
        preferredDtbId: Int? = null
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val (dtcPath, magiskbootPath) = setupEnv(logs)

        val rootAvailable = checkRootAccess()
        val slotSuffix = getSlotSuffix(rootAvailable)

        // If we already unpacked DTBs in this session and user is just switching DTB or not forcing re-dd:
        if (!forceReloadFromPartition && cachedDtbs.isNotEmpty()) {
            val targetId = preferredDtbId ?: activeDtbId.takeIf { it >= 0 } ?: cachedDtbs.first().id
            val dtsFile = File(filesDir, "$targetId.dts")
            val stockFile = File(filesDir, "$targetId.dts.stock")
            if (dtsFile.exists() && dtsFile.length() > 0L) {
                activeDtbId = targetId
                val dtsText = dtsFile.readText()
                val stockText = if (stockFile.exists()) stockFile.readText() else dtsText
                val partLabel = "$bootName$slotSuffix (DTB #$targetId)"
                logs.add("[$ts] Loaded unpacked DTB #$targetId from $bootName$slotSuffix.")
                return@withContext ShellExecutionResult(
                    success = true,
                    hasRootAccess = rootAvailable,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = dtsText,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs
                )
            }
        }

        if (!rootAvailable) {
            logs.add("[$ts] Root shell (su) not granted or unavailable on this device.")
            logs.add("[$ts] Grant root permission and tap Re-Read Device, or import a .dts / konabess:// config.")
            return@withContext ShellExecutionResult(
                success = false,
                hasRootAccess = false,
                activePartition = "Root Required (vendor_boot / boot)",
                bootName = bootName,
                dtsContent = "",
                stockDtsContent = "",
                detectedDtbs = emptyList(),
                selectedDtbId = -1,
                logs = logs
            )
        }

        logs.add("[$ts] Root shell verified (uid=0). Slot suffix: '${slotSuffix.ifBlank { "none" }}'")

        // Match KonaBessCore.getBootImage(): try vendor_boot first, then fall back to boot
        var unpackedSuccess = false
        for (candidateBootName in listOf("vendor_boot", "boot")) {
            cleanWorkingFiles()
            logs.add("[$ts] Probing $candidateBootName$slotSuffix partition...")
            if (!ddPartitionToBootImg(candidateBootName, slotSuffix, logs)) {
                continue
            }
            bootName = candidateBootName

            if (!unpackBootImgAndDecompileDtbs(dtcPath, magiskbootPath, logs)) {
                logs.add("[$ts] Failed to unpack/decompile DTBs from $candidateBootName$slotSuffix.")
                continue
            }

            val dtbs = checkDeviceDtbs(logs)
            if (dtbs.isEmpty()) {
                logs.add("[$ts] No GPU power levels found in $candidateBootName$slotSuffix DTBs; trying next partition...")
                continue
            }

            cachedDtbs = dtbs
            unpackedSuccess = true
            break
        }

        if (!unpackedSuccess || cachedDtbs.isEmpty()) {
            cleanWorkingFiles()
            logs.add("[$ts] Could not locate Qualcomm Adreno GPU DTB in vendor_boot or boot.")
            return@withContext ShellExecutionResult(
                success = false,
                hasRootAccess = true,
                activePartition = "vendor_boot / boot",
                bootName = bootName,
                dtsContent = "",
                stockDtsContent = "",
                detectedDtbs = emptyList(),
                selectedDtbId = -1,
                logs = logs
            )
        }

        val chosenDtb = when {
            preferredDtbId != null && cachedDtbs.any { it.id == preferredDtbId } ->
                cachedDtbs.first { it.id == preferredDtbId }
            cachedDtbs.any { it.isActiveBootDtb } ->
                cachedDtbs.first { it.isActiveBootDtb }
            else ->
                cachedDtbs.first()
        }
        activeDtbId = chosenDtb.id

        val dtsFile = File(filesDir, "${chosenDtb.id}.dts")
        val stockFile = File(filesDir, "${chosenDtb.id}.dts.stock")
        val dtsText = if (dtsFile.exists()) dtsFile.readText() else ""
        val stockText = if (stockFile.exists()) stockFile.readText() else dtsText
        val partLabel = "$bootName$slotSuffix (DTB #${chosenDtb.id})"

        logs.add(
            "[$ts] Selected DTB #${chosenDtb.id}: ${chosenDtb.description} [${chosenDtb.chipType.name}] " +
                "from $bootName$slotSuffix (${cachedDtbs.size} compatible DTB(s) found)."
        )

        ShellExecutionResult(
            success = dtsText.isNotBlank(),
            hasRootAccess = true,
            activePartition = partLabel,
            bootName = bootName,
            dtsContent = dtsText,
            stockDtsContent = stockText,
            detectedDtbs = cachedDtbs,
            selectedDtbId = chosenDtb.id,
            logs = logs
        )
    }

    /**
     * Restores the untouched `.dts.stock` file unpacked from the device's DTB for `dtbId`.
     */
    suspend fun restoreStockDtsForDtb(dtbId: Int): String? = withContext(Dispatchers.IO) {
        val targetId = if (dtbId >= 0) dtbId else activeDtbId
        if (targetId < 0) return@withContext null
        val stockFile = File(filesDir, "$targetId.dts.stock")
        val activeFile = File(filesDir, "$targetId.dts")
        if (stockFile.exists() && stockFile.length() > 0L) {
            val stockText = stockFile.readText()
            activeFile.writeText(stockText)
            return@withContext stockText
        }
        null
    }

    /**
     * Writes modified DTS lines to `<filesDir>/<dtbId>.dts` (matching `GpuTableEditor.writeOut`).
     */
    suspend fun writeEditedDtsToDisk(dtbId: Int, dtsContent: String): Boolean =
        withContext(Dispatchers.IO) {
            val targetId = if (dtbId >= 0) dtbId else activeDtbId
            if (targetId < 0) return@withContext false
            return@withContext try {
                File(filesDir, "$targetId.dts").writeText(dtsContent)
                true
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Exact port of `KonaBessCore.dts2bootImage()` + `KonaBessCore.writeBootImage()`:
     * 1. Writes `dtsContent` to `<dtbId>.dts`.
     * 2. Recompiles `0.dts .. (dtbNum-1).dts` to `0.dtb .. (dtbNum-1).dtb` via `dtc -I dts -O dtb`.
     * 3. Concatenates `0.dtb .. (dtbNum-1).dtb` into `dtb` / `kernel_dtb` (`linkDtbs`).
     * 4. Repacks `boot.img` -> `boot_new.img` via `magiskboot repack boot.img boot_new.img`.
     * 5. Flashes `boot_new.img` to `/dev/block/bootdevice/by-name/<bootName><slot>`.
     */
    suspend fun saveRepackedDtsAndFlash(dtbId: Int, dtsContent: String): ShellExecutionResult =
        withContext(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val (dtcPath, magiskbootPath) = setupEnv(logs)
            val rootAvailable = checkRootAccess()
            val slotSuffix = getSlotSuffix(rootAvailable)
            val targetId = if (dtbId >= 0) dtbId else activeDtbId.coerceAtLeast(0)

            val dtsFile = File(filesDir, "$targetId.dts")
            dtsFile.writeText(dtsContent)
            logs.add("[$ts] Wrote updated GPU table to ${dtsFile.name} (${dtsContent.length} bytes).")

            val stockFile = File(filesDir, "$targetId.dts.stock")
            val stockText = if (stockFile.exists()) stockFile.readText() else dtsContent
            val partLabel = "$bootName$slotSuffix (DTB #$targetId)"

            if (!rootAvailable || dtbNum <= 0 || !File(filesDir, "boot.img").exists()) {
                logs.add("[$ts] Saved DTS changes in workspace. Root unpacked boot.img required to flash partition.")
                return@withContext ShellExecutionResult(
                    success = rootAvailable,
                    hasRootAccess = rootAvailable,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = dtsContent,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs
                )
            }

            // Step 1: Compile all 0..dtbNum-1 .dts -> .dtb using dtc
            logs.add("[$ts] Compiling $dtbNum DTB(s) via dtc -I dts -O dtb...")
            for (i in 0 until dtbNum) {
                val srcDts = File(filesDir, "$i.dts")
                val outDtb = File(filesDir, "$i.dtb")
                if (outDtb.exists()) outDtb.delete()
                val cmd = "'$dtcPath' -I dts -O dtb '${srcDts.absolutePath}' -o '${outDtb.absolutePath}'"
                val (exitCode, outText) = execShell(cmd, useRoot = true)
                if (exitCode != 0 || !outDtb.exists() || outDtb.length() == 0L) {
                    logs.add("[$ts] dtc compile failed on $i.dts (exit=$exitCode): $outText")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = true,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs
                    )
                }
            }

            // Step 2: linkDtbs() -> concatenate 0.dtb .. (dtbNum - 1).dtb into dtb / kernel_dtb
            val dtbOutFile = File(filesDir, "dtb")
            val kernelDtbOutFile = File(filesDir, "kernel_dtb")
            if (dtbOutFile.exists()) dtbOutFile.delete()
            if (kernelDtbOutFile.exists()) kernelDtbOutFile.delete()

            val linkTarget = if (dtbType == DtbType.KERNEL_DTB) kernelDtbOutFile else dtbOutFile
            BufferedOutputStream(FileOutputStream(linkTarget)).use { bos ->
                for (i in 0 until dtbNum) {
                    val singleDtb = File(filesDir, "$i.dtb")
                    bos.write(singleDtb.readBytes())
                }
            }
            if (dtbType == DtbType.BOTH) {
                linkTarget.copyTo(kernelDtbOutFile, overwrite = true)
            }
            logs.add("[$ts] Linked $dtbNum DTB blob(s) into ${linkTarget.name} (${linkTarget.length()} bytes).")

            // Step 3: magiskboot repack boot.img boot_new.img
            val bootNewFile = File(filesDir, "boot_new.img")
            if (bootNewFile.exists()) bootNewFile.delete()
            val repackCmd = "cd '${filesDir.absolutePath}' && '$magiskbootPath' repack boot.img boot_new.img"
            val (repackExit, repackOut) = execShell(repackCmd, useRoot = true)
            if (repackExit != 0 || !bootNewFile.exists() || bootNewFile.length() == 0L) {
                logs.add("[$ts] magiskboot repack failed (exit=$repackExit): $repackOut")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = dtsContent,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs
                )
            }
            logs.add("[$ts] Repacked boot_new.img (${bootNewFile.length()} bytes).")

            // Step 4: writeBootImage -> dd if=boot_new.img of=/dev/block/bootdevice/by-name/<bootName><slot>
            val targetPartitionPath = resolveBlockDevicePath(bootName, slotSuffix)
            if (targetPartitionPath == null) {
                logs.add("[$ts] Could not locate block device for $bootName$slotSuffix.")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = dtsContent,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs
                )
            }

            val ddCmd = "dd if='${bootNewFile.absolutePath}' of='$targetPartitionPath'"
            val (ddExit, ddOut) = execShell(ddCmd, useRoot = true)
            if (ddExit != 0) {
                logs.add("[$ts] Failed flashing $targetPartitionPath (exit=$ddExit): $ddOut")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = dtsContent,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs
                )
            }

            logs.add("[$ts] Successfully flashed boot_new.img to $targetPartitionPath!")
            ShellExecutionResult(
                success = true,
                hasRootAccess = true,
                activePartition = partLabel,
                bootName = bootName,
                dtsContent = dtsContent,
                stockDtsContent = stockText,
                detectedDtbs = cachedDtbs,
                selectedDtbId = targetId,
                logs = logs
            )
        }

    /**
     * Exact port of `KonaBessCore.backupBootImage()`:
     * Copies `<filesDir>/boot.img` to `/sdcard/<boot_name>.img`.
     */
    suspend fun createBackupImageCopy(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val bootImg = File(filesDir, "boot.img")
        if (!bootImg.exists() || bootImg.length() == 0L) {
            return@withContext Pair(
                false,
                "No unpacked boot.img found. Unpack device image first before backing up."
            )
        }
        val destPath = "/sdcard/$bootName.img"
        val (exitCode, out) = execShell("cp -f '${bootImg.absolutePath}' '$destPath'", useRoot = true)
        if (exitCode == 0) {
            Pair(true, "Backed up stock $bootName.img to $destPath (${bootImg.length() / 1024} KB).")
        } else {
            val localBackup = File(filesDir, "backup_$bootName.img")
            bootImg.copyTo(localBackup, overwrite = true)
            Pair(true, "Backed up $bootName.img to ${localBackup.absolutePath} ($out).")
        }
    }

    /**
     * Exact port of `KonaBessCore.reboot()`: runs `su -c svc power reboot`.
     */
    suspend fun rebootDevice(): Boolean = withContext(Dispatchers.IO) {
        val (exitCode, _) = execShell("svc power reboot || reboot", useRoot = true)
        exitCode == 0
    }

    private fun ddPartitionToBootImg(
        partitionBaseName: String,
        slotSuffix: String,
        logs: MutableList<String>
    ): Boolean {
        val devPath = resolveBlockDevicePath(partitionBaseName, slotSuffix) ?: return false
        val bootImgFile = File(filesDir, "boot.img")
        if (bootImgFile.exists()) bootImgFile.delete()

        val cmd = "dd if='$devPath' of='${bootImgFile.absolutePath}' && chmod 644 '${bootImgFile.absolutePath}'"
        val (exitCode, output) = execShell(cmd, useRoot = true)
        if (exitCode != 0 || !bootImgFile.exists() || bootImgFile.length() == 0L) {
            logs.add("dd from $devPath failed (exit=$exitCode): $output")
            if (bootImgFile.exists()) bootImgFile.delete()
            return false
        }
        logs.add("Extracted $devPath -> boot.img (${bootImgFile.length() / 1024} KB)")
        return true
    }

    private fun resolveBlockDevicePath(partitionBaseName: String, slotSuffix: String): String? {
        val candidates = listOf(
            "/dev/block/bootdevice/by-name/$partitionBaseName$slotSuffix",
            "/dev/block/by-name/$partitionBaseName$slotSuffix",
            "/dev/block/mapper/$partitionBaseName$slotSuffix",
            "/dev/block/bootdevice/by-name/$partitionBaseName",
            "/dev/block/by-name/$partitionBaseName"
        )
        for (path in candidates) {
            val (exit, _) = execShell("ls '$path'", useRoot = true)
            if (exit == 0) return path
        }
        return null
    }

    private fun unpackBootImgAndDecompileDtbs(
        dtcPath: String,
        magiskbootPath: String,
        logs: MutableList<String>
    ): Boolean {
        val unpackCmd = "cd '${filesDir.absolutePath}' && '$magiskbootPath' unpack boot.img && chmod -R 644 '${filesDir.absolutePath}'/*"
        val (exitCode, output) = execShell(unpackCmd, useRoot = true)
        // Re-ensure binaries in filesDir remain executable after chmod 644
        File(filesDir, "dtc").setExecutable(true, false)
        File(filesDir, "magiskboot").setExecutable(true, false)

        if (exitCode != 0) {
            logs.add("magiskboot unpack exited with $exitCode: $output")
            return false
        }

        val dtbFile = File(filesDir, "dtb")
        val kernelDtbFile = File(filesDir, "kernel_dtb")

        val sourceDtbFile: File = when {
            dtbFile.exists() && dtbFile.length() > 0L && kernelDtbFile.exists() && kernelDtbFile.length() > 0L -> {
                dtbType = DtbType.BOTH
                dtbFile
            }
            dtbFile.exists() && dtbFile.length() > 0L -> {
                dtbType = DtbType.DTB
                dtbFile
            }
            kernelDtbFile.exists() && kernelDtbFile.length() > 0L -> {
                dtbType = DtbType.KERNEL_DTB
                kernelDtbFile
            }
            else -> return false
        }

        dtbNum = splitConcatenatedDtbs(sourceDtbFile, logs)
        if (dtbNum <= 0) return false

        for (i in 0 until dtbNum) {
            val singleDtb = File(filesDir, "$i.dtb")
            val singleDts = File(filesDir, "$i.dts")
            val stockDts = File(filesDir, "$i.dts.stock")

            val dtcCmd = "'$dtcPath' -I dtb -O dts '${singleDtb.absolutePath}' -o '${singleDts.absolutePath}' && chmod 644 '${singleDts.absolutePath}'"
            val (dtcExit, dtcOut) = execShell(dtcCmd, useRoot = true)
            if (dtcExit != 0 || !singleDts.exists() || singleDts.length() == 0L) {
                logs.add("dtc decompile failed on $i.dtb (exit=$dtcExit): $dtcOut")
                return false
            }
            // Save untouched stock copy immediately on unpack
            singleDts.copyTo(stockDts, overwrite = true)
        }
        logs.add("Decompiled $dtbNum DTB(s) to DTS via dtc.")
        return true
    }

    /**
     * Exact port of `KonaBessCore.dtb_split()`:
     * Scans the unpacked `dtb` / `kernel_dtb` byte array for FDT header magic `0xD0 0x0D 0xFE 0xED`,
     * reads the 32-bit big-endian total size at `offset + 4`, and writes each slice to `<i>.dtb`.
     */
    private fun splitConcatenatedDtbs(dtbBlobFile: File, logs: MutableList<String>): Int {
        return try {
            val dtbBytes = dtbBlobFile.readBytes()
            val cutPoints = mutableListOf<Int>()
            var i = 0
            while (i + 8 < dtbBytes.size) {
                if (dtbBytes[i] == 0xD0.toByte() &&
                    dtbBytes[i + 1] == 0x0D.toByte() &&
                    dtbBytes[i + 2] == 0xFE.toByte() &&
                    dtbBytes[i + 3] == 0xED.toByte()
                ) {
                    cutPoints.add(i)
                    val size = ByteBuffer.wrap(dtbBytes, i + 4, 4).int
                    if (size > 0 && i + size <= dtbBytes.size) {
                        i += size
                        continue
                    }
                }
                i++
            }

            if (cutPoints.isEmpty()) return 0

            for (idx in cutPoints.indices) {
                val start = cutPoints[idx]
                val end = if (idx < cutPoints.lastIndex) cutPoints[idx + 1] else dtbBytes.size
                val outFile = File(filesDir, "$idx.dtb")
                FileOutputStream(outFile).use { fos ->
                    fos.write(dtbBytes, start, end - start)
                }
            }
            cutPoints.size
        } catch (e: Exception) {
            logs.add("dtb_split error: ${e.message}")
            0
        }
    }

    /**
     * Exact port of `KonaBessCore.checkDevice()`:
     * Checks each decompiled `0.dts .. (dtbNum - 1).dts` using strict `model` line matching
     * (`detectChipTypeStrict`), identical to `libxzr/KonaBess`.
     *
     * On devices like Snapdragon 8 Gen 2 (`Kalama`), `vendor_boot` contains 4 DTBs (`0.dtb` = Kalama,
     * `1.dtb` = Kalama v2, `2.dtb` = Kalama IOT, `3.dtb` = Kalama HHG). `detectChipTypeStrict` matches
     * ONLY `1.dtb` (`Kalama v2`), so `matched.size == 1` and only the exact DTB exposed by original
     * KonaBess is returned. If multiple DTBs match and one is the active boot DTB (`ro.boot.dtb_idx`
     * or `/proc/device-tree/model`), only that active DTB is exposed.
     */
    private fun checkDeviceDtbs(logs: MutableList<String>): List<DetectedDtb> {
        val bootDtbIdx = getDtbIndexProperty()
        val liveModel = getLiveDeviceTreeModel()
        val deviceName = Build.DEVICE.orEmpty()
        val strictMatched = mutableListOf<DetectedDtb>()

        for (i in 0 until dtbNum) {
            val dtsFile = File(filesDir, "$i.dts")
            if (!dtsFile.exists()) continue
            val lines = dtsFile.readLines()
            val hasGpuPwrlevels = lines.any {
                val t = it.trim()
                t.startsWith("qcom,gpu-pwrlevels {") || t.startsWith("qcom,gpu-pwrlevels-")
            }
            if (!hasGpuPwrlevels) continue

            val (chipType, modelDesc) = DtsParser.detectChipTypeStrict(lines, deviceName)
            if (chipType != KonaBessChipType.unknown) {
                val isBootMatch = (i == bootDtbIdx) ||
                    (liveModel.isNotBlank() && modelDesc.isNotBlank() && liveModel.contains(modelDesc))
                strictMatched.add(
                    DetectedDtb(
                        id = i,
                        chipType = chipType,
                        description = modelDesc.ifBlank { chipType.name },
                        isActiveBootDtb = isBootMatch
                    )
                )
            }
        }

        // Fallback only if no DTB matched strict model strings (e.g., custom OEM model string)
        val candidates = if (strictMatched.isNotEmpty()) {
            strictMatched
        } else {
            val fallbackList = mutableListOf<DetectedDtb>()
            for (i in 0 until dtbNum) {
                val dtsFile = File(filesDir, "$i.dts")
                if (!dtsFile.exists()) continue
                val lines = dtsFile.readLines()
                val hasGpuPwrlevels = lines.any {
                    val t = it.trim()
                    t.startsWith("qcom,gpu-pwrlevels {") || t.startsWith("qcom,gpu-pwrlevels-")
                }
                if (!hasGpuPwrlevels) continue
                val (chipType, modelDesc) = DtsParser.detectChipType(lines)
                if (chipType != KonaBessChipType.unknown) {
                    val isBootMatch = (i == bootDtbIdx) ||
                        (liveModel.isNotBlank() && modelDesc.isNotBlank() && liveModel.contains(modelDesc))
                    fallbackList.add(
                        DetectedDtb(
                            id = i,
                            chipType = chipType,
                            description = modelDesc.ifBlank { chipType.name },
                            isActiveBootDtb = isBootMatch
                        )
                    )
                }
            }
            fallbackList
        }

        // If multiple DTBs matched but one is explicitly the active boot DTB, expose only that active DTB
        val activeOnly = candidates.filter { it.isActiveBootDtb }
        val finalMatched = if (activeOnly.size == 1) {
            activeOnly
        } else {
            candidates
        }

        if (finalMatched.size == 1 && !finalMatched[0].isActiveBootDtb) {
            return listOf(finalMatched[0].copy(isActiveBootDtb = true))
        }
        return finalMatched
    }

    private fun checkRootAccess(): Boolean {
        val (exitCode, output) = execShell("id", useRoot = true)
        return exitCode == 0 && output.contains("uid=0")
    }

    private fun getSlotSuffix(useRoot: Boolean): String {
        val (_, out) = execShell("getprop ro.boot.slot_suffix", useRoot = useRoot)
        return out.lineSequence().firstOrNull().orEmpty().trim()
    }

    private fun getDtbIndexProperty(): Int {
        val (_, out) = execShell("getprop ro.boot.dtb_idx", useRoot = true)
        return out.lineSequence().firstOrNull().orEmpty().trim().toIntOrNull() ?: -1
    }

    private fun getLiveDeviceTreeModel(): String {
        val (_, out) = execShell("tr -d '\\0' < /proc/device-tree/model", useRoot = true)
        return out.lineSequence().firstOrNull().orEmpty().trim()
    }

    private fun execShell(command: String, useRoot: Boolean): Pair<Int, String> {
        return try {
            val process = if (useRoot) {
                ProcessBuilder("su", "-c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode, output.trim())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "exec error")
        }
    }
}
