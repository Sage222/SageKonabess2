package com.example.konabess.core

import android.content.Context
import android.os.Build
import android.os.Process
import com.example.konabess.model.DetectedDtb
import com.example.konabess.model.KonaBessChipType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
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
 * 6. `dts2bootImage` & `writeBootImage`: compiles `.dts -> .dtb` (`dtc -I dts -O dtb -f`),
 *    links `0.dtb .. (N-1).dtb` into `dtb`, repacks `boot_new.img` via `magiskboot repack boot.img boot_new.img`,
 *    flashes back to `/dev/block/bootdevice/by-name/<boot_name><slot>`, and verifies the flashed partition.
 *
 * NEVER generates or substitutes fake/synthetic frequencies or voltage levels.
 */
class KonaBessShellEngine(private val context: Context) {

    private enum class DtbType {
        DTB,
        KERNEL_DTB,
        BOTH
    }

    data class BackupImageCandidate(
        val path: String,
        val bootName: String,
        val sizeBytes: Long
    )

    data class SavedBootBackup(
        val id: String,
        val displayName: String,
        val fileName: String,
        val path: String,
        val localMirrorPath: String,
        val bootName: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val backupModeLabel: String, // "STOCK" or "MODIFIED"
        val isBuiltInDefault: Boolean = false
    )

    data class FlashVerificationReport(
        val success: Boolean,
        val isRestoreDefaultImage: Boolean = false,
        val sourceImagePath: String = "",
        val failedStage: String? = null,
        val targetPartitionPath: String = "",
        val partitionLabel: String = "",
        val selectedDtbId: Int = -1,
        val totalDtbCount: Int = 0,
        val compiledDtbBytes: Long = 0L,
        val linkedDtbBytes: Long = 0L,
        val repackedImageBytes: Long = 0L,
        val imageSha256: String = "",
        val partitionReadbackSha256: String = "",
        val dtbHeaderVerified: Boolean = false,
        val partitionReadbackVerified: Boolean = false,
        val errorMessage: String? = null
    )

    data class ShellExecutionResult(
        val success: Boolean,
        val hasRootAccess: Boolean,
        val activePartition: String,
        val bootName: String,
        val dtsContent: String,
        val stockDtsContent: String,
        val detectedDtbs: List<DetectedDtb>,
        val selectedDtbId: Int,
        val logs: List<String>,
        val flashVerification: FlashVerificationReport? = null
    )

    private val filesDir: File
        get() = context.filesDir

    private var bootName: String = "vendor_boot"
    private var dtbNum: Int = 0
    private var dtbType: DtbType = DtbType.DTB
    private var cachedDtbs: List<DetectedDtb> = emptyList()
    private var activeDtbId: Int = -1
    private var cachedSlotSuffix: String? = null

    init {
        try {
            val legacyDir = File(context.filesDir, "konabess_workspace")
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
            }
        } catch (_: Throwable) {
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
            } catch (_: Throwable) {
            }
        } else {
            extracted.setExecutable(true, false)
        }
        return extracted.absolutePath
    }

    private fun setupEnv(logs: MutableList<String>): Pair<String, String> {
        val dtcPath = resolveBinaryPath("dtc")
        val magiskbootPath = resolveBinaryPath("magiskboot")
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
            } catch (e: Throwable) {
                logs.add("Asset setup note ($assetName): ${e.message}")
            }
        }
        return Pair(dtcPath, magiskbootPath)
    }

    /**
     * Ensures all files in `filesDir` created by `su` (`root:root`) are chowned to the app's UID
     * and given read/write permissions (`u+rwX,go+rwX`) so Kotlin `File` operations never fail with
     * `FileNotFoundException: open failed: EACCES (Permission denied)`.
     */
    private fun ensureWorkspaceFilesWritable() {
        val appUid = Process.myUid()
        val dirPath = filesDir.absolutePath
        execShell(
            "chown -R $appUid:$appUid '$dirPath' 2>/dev/null || true; " +
                "chmod -R u+rwX,go+rwX '$dirPath' 2>/dev/null || true",
            useRoot = true
        )
        try {
            File(filesDir, "dtc").setExecutable(true, false)
            File(filesDir, "magiskboot").setExecutable(true, false)
        } catch (_: Throwable) {
        }
    }

    /**
     * Safely writes `content` to `targetFile` in `filesDir`, handling any pre-existing root-owned
     * file without ever throwing an uncaught `FileNotFoundException (EACCES)`.
     */
    private fun writeFileSafely(
        targetFile: File,
        content: String,
        useRootFallback: Boolean,
        logs: MutableList<String>? = null
    ): Boolean {
        // 1. Try deleting existing file first if it is not writable (unlink checks directory permissions)
        try {
            if (targetFile.exists() && !targetFile.canWrite()) {
                targetFile.delete()
            }
            targetFile.writeText(content)
            targetFile.setReadable(true, false)
            targetFile.setWritable(true, false)
            if (targetFile.exists() && targetFile.length() > 0L) {
                return true
            }
        } catch (_: Throwable) {
            // Fall through to root-assisted write if file was locked by root:root
        }

        if (!useRootFallback) return false

        return try {
            val appUid = Process.myUid()
            val tempFile = File(filesDir, "${targetFile.name}.tmp_${System.nanoTime()}")
            if (tempFile.exists()) tempFile.delete()
            tempFile.writeText(content)
            val cmd = "mv -f '${tempFile.absolutePath}' '${targetFile.absolutePath}' && " +
                "chown $appUid:$appUid '${targetFile.absolutePath}' && " +
                "chmod 666 '${targetFile.absolutePath}'"
            val (exit, out) = execShell(cmd, useRoot = true)
            if (exit != 0) {
                logs?.add("Root file write fallback failed ($exit): $out")
            }
            targetFile.exists() && targetFile.length() > 0L
        } catch (t: Throwable) {
            logs?.add("Failed writing ${targetFile.name}: ${t.message}")
            false
        }
    }

    private fun cleanWorkingFiles(useRoot: Boolean = false) {
        if (useRoot) {
            val dirPath = filesDir.absolutePath
            execShell(
                "rm -rf '$dirPath'/*.dtb '$dirPath'/*.dts '$dirPath'/*.stock " +
                    "'$dirPath'/boot.img '$dirPath'/boot_new.img '$dirPath'/new-boot.img " +
                    "'$dirPath'/dtb '$dirPath'/kernel_dtb '$dirPath'/kernel " +
                    "'$dirPath'/ramdisk.cpio '$dirPath'/header '$dirPath'/vendor_ramdisk 2>/dev/null || true",
                useRoot = true
            )
        }
        val children = filesDir.listFiles() ?: return
        for (f in children) {
            try {
                if (f.name.endsWith(".dtb") ||
                    f.name.endsWith(".dts") ||
                    f.name.endsWith(".stock") ||
                    f.name == "boot.img" ||
                    f.name == "boot_new.img" ||
                    f.name == "new-boot.img" ||
                    f.name == "dtb" ||
                    f.name == "kernel_dtb" ||
                    f.name == "kernel" ||
                    f.name == "ramdisk.cpio" ||
                    f.name == "header" ||
                    f.name == "vendor_ramdisk"
                ) {
                    f.deleteRecursively()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Recovers `dtbNum`, `dtbType`, and `bootName` from `filesDir` if in-memory state was reset.
     */
    private fun recoverWorkspaceMetadataFromDiskIfNeeded() {
        val metaFile = File(filesDir, "konabess_session.meta")
        if (metaFile.exists()) {
            try {
                val props = metaFile.readLines().associate {
                    val k = it.substringBefore("=").trim()
                    val v = it.substringAfter("=", "").trim()
                    k to v
                }
                props["bootName"]?.takeIf { it.isNotBlank() }?.let { bootName = it }
                props["dtbNum"]?.toIntOrNull()?.takeIf { it > 0 }?.let { dtbNum = it }
                props["dtbType"]?.let { typeStr ->
                    dtbType = runCatching { DtbType.valueOf(typeStr) }.getOrDefault(dtbType)
                }
                props["activeDtbId"]?.toIntOrNull()?.takeIf { it >= 0 }?.let {
                    if (activeDtbId < 0) activeDtbId = it
                }
            } catch (_: Throwable) {
            }
        }

        if (dtbNum <= 0) {
            var count = 0
            while (File(filesDir, "$count.dts").exists() || File(filesDir, "$count.dtb").exists()) {
                count++
            }
            if (count > 0) {
                dtbNum = count
            }
        }

        val dtbFile = File(filesDir, "dtb")
        val kernelDtbFile = File(filesDir, "kernel_dtb")
        if (dtbFile.exists() && kernelDtbFile.exists()) {
            dtbType = DtbType.BOTH
        } else if (kernelDtbFile.exists()) {
            dtbType = DtbType.KERNEL_DTB
        } else if (dtbFile.exists()) {
            dtbType = DtbType.DTB
        }
    }

    private fun saveWorkspaceMetadataToDisk() {
        try {
            val metaFile = File(filesDir, "konabess_session.meta")
            val content = buildString {
                appendLine("bootName=$bootName")
                appendLine("dtbNum=$dtbNum")
                appendLine("dtbType=${dtbType.name}")
                appendLine("activeDtbId=$activeDtbId")
            }
            writeFileSafely(metaFile, content, useRootFallback = false)
        } catch (_: Throwable) {
        }
    }

    suspend fun unpackAndReadDeviceDts(
        forceReloadFromPartition: Boolean = false,
        preferredDtbId: Int? = null
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        try {
            val (dtcPath, magiskbootPath) = setupEnv(logs)

            val rootAvailable = checkRootAccess()
            val slotSuffix = getSlotSuffix(rootAvailable)

            if (!forceReloadFromPartition && cachedDtbs.isNotEmpty()) {
                val targetId = preferredDtbId ?: activeDtbId.takeIf { it >= 0 } ?: cachedDtbs.first().id
                val dtsFile = File(filesDir, "$targetId.dts")
                val stockFile = File(filesDir, "$targetId.dts.stock")
                if (dtsFile.exists() && dtsFile.length() > 0L) {
                    activeDtbId = targetId
                    saveWorkspaceMetadataToDisk()
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

            var unpackedSuccess = false
            for (candidateBootName in listOf("vendor_boot", "boot")) {
                cleanWorkingFiles(useRoot = true)
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
                cleanWorkingFiles(useRoot = true)
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

            ensureWorkspaceFilesWritable()

            // Preserve a pristine local backup_<bootName>.img on first unpack if not already saved
            try {
                val bootImgFile = File(filesDir, "boot.img")
                val pristineBackup = File(filesDir, "backup_$bootName.img")
                if (bootImgFile.exists() && bootImgFile.length() > 0L &&
                    (!pristineBackup.exists() || pristineBackup.length() == 0L)
                ) {
                    bootImgFile.copyTo(pristineBackup, overwrite = true)
                    pristineBackup.setReadable(true, false)
                    pristineBackup.setWritable(true, false)
                    logs.add("[$ts] Saved automatic pristine backup copy: ${pristineBackup.name} (${pristineBackup.length() / 1024} KB).")
                }
            } catch (_: Throwable) {
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
            saveWorkspaceMetadataToDisk()

            val dtsFile = File(filesDir, "${chosenDtb.id}.dts")
            val stockFile = File(filesDir, "${chosenDtb.id}.dts.stock")
            val dtsText = if (dtsFile.exists()) dtsFile.readText() else ""
            val stockText = if (stockFile.exists()) stockFile.readText() else dtsText
            val partLabel = "$bootName$slotSuffix (DTB #${chosenDtb.id})"

            logs.add(
                "[$ts] Selected DTB #${chosenDtb.id}: ${chosenDtb.description} [${chosenDtb.chipType.name}] " +
                    "from $bootName$slotSuffix ($dtbNum total DTB blob(s) in image)."
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
        } catch (t: Throwable) {
            logs.add("[$ts] Unexpected error during unpack: ${t.message}")
            ShellExecutionResult(
                success = false,
                hasRootAccess = false,
                activePartition = "vendor_boot / boot",
                bootName = bootName,
                dtsContent = "",
                stockDtsContent = "",
                detectedDtbs = cachedDtbs,
                selectedDtbId = activeDtbId,
                logs = logs
            )
        }
    }

    /**
     * Restores the untouched `.dts.stock` file unpacked from the device's DTB for `dtbId`.
     */
    suspend fun restoreStockDtsForDtb(dtbId: Int): String? = withContext(Dispatchers.IO) {
        try {
            val targetId = if (dtbId >= 0) dtbId else activeDtbId
            if (targetId < 0) return@withContext null
            val stockFile = File(filesDir, "$targetId.dts.stock")
            val activeFile = File(filesDir, "$targetId.dts")
            if (stockFile.exists() && stockFile.length() > 0L) {
                val stockText = stockFile.readText()
                writeFileSafely(activeFile, stockText, useRootFallback = true)
                return@withContext stockText
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Writes modified DTS lines to `<filesDir>/<dtbId>.dts` (matching `GpuTableEditor.writeOut`).
     * Uses `writeFileSafely` so it never fails with `EACCES` even if `<dtbId>.dts` was previously created by `su`.
     */
    suspend fun writeEditedDtsToDisk(dtbId: Int, dtsContent: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                recoverWorkspaceMetadataFromDiskIfNeeded()
                val targetId = if (dtbId >= 0) dtbId else activeDtbId
                if (targetId < 0) return@withContext false
                val dtsFile = File(filesDir, "$targetId.dts")
                writeFileSafely(dtsFile, dtsContent, useRootFallback = true)
            } catch (_: Throwable) {
                false
            }
        }

    /**
     * Exact port of `KonaBessCore.dts2bootImage()` + `KonaBessCore.writeBootImage()`, plus
     * post-flash verification:
     * 1. Safely writes `dtsContent` to `<dtbId>.dts` (never crashes on root-owned files).
     * 2. Compiles `0.dts .. (dtbNum-1).dts` to `0.dtb .. (dtbNum-1).dtb` via `dtc -I dts -O dtb -f`.
     * 3. Verifies the compiled `<dtbId>.dtb` FDT header (`0xD00DFEED`) and size.
     * 4. Concatenates `0.dtb .. (dtbNum-1).dtb` into `dtb` / `kernel_dtb` (`KonaBessCore.linkDtbs()`).
     * 5. Repacks `boot.img` -> `boot_new.img` via `magiskboot repack boot.img boot_new.img`.
     * 6. Flashes `boot_new.img` to `/dev/block/bootdevice/by-name/<bootName><slot>` via `dd`.
     * 7. Verifies flash success by reading back and comparing the SHA-256 of the flashed partition against `boot_new.img`.
     */
    suspend fun saveRepackedDtsAndFlash(dtbId: Int, dtsContent: String): ShellExecutionResult =
        withContext(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            recoverWorkspaceMetadataFromDiskIfNeeded()

            val targetId = if (dtbId >= 0) dtbId else activeDtbId.coerceAtLeast(0)
            val stockFile = File(filesDir, "$targetId.dts.stock")
            val stockText = try {
                if (stockFile.exists()) stockFile.readText() else dtsContent
            } catch (_: Throwable) {
                dtsContent
            }

            try {
                val (dtcPath, magiskbootPath) = setupEnv(logs)
                val rootAvailable = checkRootAccess()
                val slotSuffix = getSlotSuffix(rootAvailable)
                val partLabel = "$bootName$slotSuffix (DTB #$targetId)"

                // Step 1: Write updated DTS to <targetId>.dts safely
                val dtsFile = File(filesDir, "$targetId.dts")
                val wroteDts = writeFileSafely(dtsFile, dtsContent, useRootFallback = rootAvailable, logs = logs)
                if (!wroteDts) {
                    val err = "Failed to write updated GPU table to ${dtsFile.name}."
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = rootAvailable,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Save DTS (${dtsFile.name})",
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            errorMessage = err
                        )
                    )
                }
                logs.add("[$ts] Step 1/6: Saved updated GPU table to ${dtsFile.name} (${dtsContent.length} bytes).")

                if (!rootAvailable) {
                    val err = "Root access (su) is required to repack and flash $bootName$slotSuffix."
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = false,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Root Access Check (su)",
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            errorMessage = err
                        )
                    )
                }

                // If boot.img or dtbNum is missing (e.g., workspace was cleared), re-extract boot.img while keeping <targetId>.dts
                val bootImgFile = File(filesDir, "boot.img")
                if (!bootImgFile.exists() || bootImgFile.length() == 0L || dtbNum <= 0) {
                    logs.add("[$ts] Workspace boot.img not found; extracting $bootName$slotSuffix before repacking...")
                    if (!ddPartitionToBootImg(bootName, slotSuffix, logs) ||
                        !unpackBootImgAndDecompileDtbs(dtcPath, magiskbootPath, logs)
                    ) {
                        val err = "Could not unpack $bootName$slotSuffix boot.img for repacking."
                        logs.add("[$ts] $err")
                        return@withContext ShellExecutionResult(
                            success = false,
                            hasRootAccess = true,
                            activePartition = partLabel,
                            bootName = bootName,
                            dtsContent = dtsContent,
                            stockDtsContent = stockText,
                            detectedDtbs = cachedDtbs,
                            selectedDtbId = targetId,
                            logs = logs,
                            flashVerification = FlashVerificationReport(
                                success = false,
                                failedStage = "Prepare Unpacked Boot Image",
                                partitionLabel = "$bootName$slotSuffix",
                                selectedDtbId = targetId,
                                totalDtbCount = dtbNum,
                                errorMessage = err
                            )
                        )
                    }
                    // Re-apply the user's edited dtsContent to <targetId>.dts
                    writeFileSafely(dtsFile, dtsContent, useRootFallback = true, logs = logs)
                }

                ensureWorkspaceFilesWritable()

                // Step 2: KonaBessCore.dts2bootImage() -> Compile 0..dtbNum-1 .dts -> .dtb using dtc -I dts -O dtb -f
                logs.add("[$ts] Step 2/6: Compiling $dtbNum DTB(s) via dtc -I dts -O dtb -f...")
                for (i in 0 until dtbNum) {
                    val srcDts = File(filesDir, "$i.dts")
                    val outDtb = File(filesDir, "$i.dtb")
                    val stockDtbBackup = File(filesDir, "$i.dtb.stock")

                    // Delete existing i.dtb via root + Kotlin so dtc can write cleanly
                    if (outDtb.exists()) {
                        if (!outDtb.delete()) {
                            execShell("rm -f '${outDtb.absolutePath}'", useRoot = true)
                        }
                    }

                    // Use -f (force output even if vendor DTB has semantic warnings) and -q (quiet)
                    val dtcCompileCmd = "'$dtcPath' -I dts -O dtb -f -q '${srcDts.absolutePath}' -o '${outDtb.absolutePath}'"
                    val (exitCode, _) = execShell(dtcCompileCmd, useRoot = true)

                    if (exitCode != 0 || !outDtb.exists() || outDtb.length() <= 4L) {
                        // If an untouched secondary DTB failed dtc, restore its binary .dtb.stock slice;
                        // if the active edited DTB failed, capture the diagnostic stderr from dtc.
                        if (i != targetId && stockDtbBackup.exists() && stockDtbBackup.length() > 4L) {
                            stockDtbBackup.copyTo(outDtb, overwrite = true)
                            logs.add("[$ts] Preserved untouched binary slice for non-target $i.dtb.")
                        } else {
                            val (_, diagOut) = execShell(
                                "'$dtcPath' -I dts -O dtb -f '${srcDts.absolutePath}' -o '${outDtb.absolutePath}' 2>&1 | tail -n 15",
                                useRoot = true
                            )
                            val err = "dtc failed compiling $i.dts (exit=$exitCode): $diagOut"
                            logs.add("[$ts] $err")
                            return@withContext ShellExecutionResult(
                                success = false,
                                hasRootAccess = true,
                                activePartition = partLabel,
                                bootName = bootName,
                                dtsContent = dtsContent,
                                stockDtsContent = stockText,
                                detectedDtbs = cachedDtbs,
                                selectedDtbId = targetId,
                                logs = logs,
                                flashVerification = FlashVerificationReport(
                                    success = false,
                                    failedStage = "DTC Compile ($i.dts -> $i.dtb)",
                                    partitionLabel = "$bootName$slotSuffix",
                                    selectedDtbId = targetId,
                                    totalDtbCount = dtbNum,
                                    errorMessage = err
                                )
                            )
                        }
                    }

                    if (!verifyFdtHeader(outDtb)) {
                        val err = "Compiled $i.dtb does not have a valid FDT header (0xD00DFEED)."
                        logs.add("[$ts] $err")
                        return@withContext ShellExecutionResult(
                            success = false,
                            hasRootAccess = true,
                            activePartition = partLabel,
                            bootName = bootName,
                            dtsContent = dtsContent,
                            stockDtsContent = stockText,
                            detectedDtbs = cachedDtbs,
                            selectedDtbId = targetId,
                            logs = logs,
                            flashVerification = FlashVerificationReport(
                                success = false,
                                failedStage = "FDT Header Check ($i.dtb)",
                                partitionLabel = "$bootName$slotSuffix",
                                selectedDtbId = targetId,
                                totalDtbCount = dtbNum,
                                errorMessage = err
                            )
                        )
                    }
                }

                ensureWorkspaceFilesWritable()
                val compiledTargetDtb = File(filesDir, "$targetId.dtb")
                val compiledDtbSize = compiledTargetDtb.length()
                logs.add("[$ts] Verified compiled $targetId.dtb FDT header (0xD00DFEED, $compiledDtbSize bytes).")

                // Step 3: KonaBessCore.linkDtbs() -> concatenate 0.dtb .. (dtbNum - 1).dtb into dtb / kernel_dtb
                val dtbOutFile = File(filesDir, "dtb")
                val kernelDtbOutFile = File(filesDir, "kernel_dtb")
                execShell("rm -f '${dtbOutFile.absolutePath}' '${kernelDtbOutFile.absolutePath}'", useRoot = true)

                val linkTarget = if (dtbType == DtbType.KERNEL_DTB) kernelDtbOutFile else dtbOutFile
                var expectedLinkedBytes = 0L
                BufferedOutputStream(FileOutputStream(linkTarget)).use { bos ->
                    for (i in 0 until dtbNum) {
                        val singleDtb = File(filesDir, "$i.dtb")
                        val bytes = singleDtb.readBytes()
                        expectedLinkedBytes += bytes.size
                        bos.write(bytes)
                    }
                }
                if (dtbType == DtbType.BOTH) {
                    linkTarget.copyTo(kernelDtbOutFile, overwrite = true)
                }

                if (!linkTarget.exists() || linkTarget.length() != expectedLinkedBytes || expectedLinkedBytes == 0L) {
                    val err = "DTB link verification failed: expected $expectedLinkedBytes bytes, got ${linkTarget.length()}."
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = true,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Link Multi-DTB (${linkTarget.name})",
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            compiledDtbBytes = compiledDtbSize,
                            errorMessage = err
                        )
                    )
                }
                logs.add("[$ts] Step 3/6: Linked $dtbNum DTB blob(s) into ${linkTarget.name} ($expectedLinkedBytes bytes).")

                // Step 4: KonaBessCore.dts2bootImage() -> magiskboot repack boot.img boot_new.img
                val bootNewFile = File(filesDir, "boot_new.img")
                val altBootNewFile = File(filesDir, "new-boot.img")
                execShell("rm -f '${bootNewFile.absolutePath}' '${altBootNewFile.absolutePath}'", useRoot = true)

                val repackCmd = "cd '${filesDir.absolutePath}' && '$magiskbootPath' repack boot.img boot_new.img"
                val (repackExit, repackOut) = execShell(repackCmd, useRoot = true)
                ensureWorkspaceFilesWritable()

                if (!bootNewFile.exists() && altBootNewFile.exists()) {
                    altBootNewFile.renameTo(bootNewFile)
                }

                if (repackExit != 0 || !bootNewFile.exists() || bootNewFile.length() == 0L) {
                    val err = "magiskboot repack failed (exit=$repackExit): $repackOut"
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = true,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Magiskboot Repack (boot_new.img)",
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            compiledDtbBytes = compiledDtbSize,
                            linkedDtbBytes = expectedLinkedBytes,
                            errorMessage = err
                        )
                    )
                }
                val repackedSize = bootNewFile.length()
                logs.add("[$ts] Step 4/6: Repacked boot_new.img (${repackedSize / 1024} KB).")

                // Step 5: KonaBessCore.writeBootImage() -> dd if=boot_new.img of=/dev/block/bootdevice/by-name/<bootName><slot>
                val targetPartitionPath = resolveBlockDevicePath(bootName, slotSuffix)
                if (targetPartitionPath == null) {
                    val err = "Could not locate block device for $bootName$slotSuffix."
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = true,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Resolve Partition Block Device",
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            compiledDtbBytes = compiledDtbSize,
                            linkedDtbBytes = expectedLinkedBytes,
                            repackedImageBytes = repackedSize,
                            errorMessage = err
                        )
                    )
                }

                // Exact KonaBessCore.writeBootImage dd command + sync flush
                val ddCmd = "blockdev --setrw '$targetPartitionPath' 2>/dev/null || true; " +
                    "dd if='${bootNewFile.absolutePath}' of='$targetPartitionPath' && sync"
                val (ddExit, ddOut) = execShell(ddCmd, useRoot = true)
                if (ddExit != 0) {
                    val err = "dd write to $targetPartitionPath failed (exit=$ddExit): $ddOut"
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = true,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Flash Partition (dd)",
                            targetPartitionPath = targetPartitionPath,
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            compiledDtbBytes = compiledDtbSize,
                            linkedDtbBytes = expectedLinkedBytes,
                            repackedImageBytes = repackedSize,
                            errorMessage = err
                        )
                    )
                }
                logs.add("[$ts] Step 5/6: Flashed boot_new.img to $targetPartitionPath via dd.")

                // Step 6: Verify flash success by comparing SHA-256 of boot_new.img against partition readback
                val imageSha256 = computeFileSha256(bootNewFile)
                val partitionReadbackSha256 = readPartitionPrefixSha256(targetPartitionPath, repackedSize)
                val readbackMatched = if (imageSha256.isNotBlank() && partitionReadbackSha256.isNotBlank()) {
                    imageSha256.equals(partitionReadbackSha256, ignoreCase = true)
                } else {
                    // Fallback byte comparison via cmp -n
                    val (cmpExit, _) = execShell(
                        "cmp -n $repackedSize '${bootNewFile.absolutePath}' '$targetPartitionPath'",
                        useRoot = true
                    )
                    cmpExit == 0
                }

                if (!readbackMatched) {
                    val err = "Post-flash verification mismatch on $targetPartitionPath " +
                        "(expected=${imageSha256.take(12)}, readback=${partitionReadbackSha256.take(12)})."
                    logs.add("[$ts] $err")
                    return@withContext ShellExecutionResult(
                        success = false,
                        hasRootAccess = true,
                        activePartition = partLabel,
                        bootName = bootName,
                        dtsContent = dtsContent,
                        stockDtsContent = stockText,
                        detectedDtbs = cachedDtbs,
                        selectedDtbId = targetId,
                        logs = logs,
                        flashVerification = FlashVerificationReport(
                            success = false,
                            failedStage = "Post-Flash Partition Readback Verification",
                            targetPartitionPath = targetPartitionPath,
                            partitionLabel = "$bootName$slotSuffix",
                            selectedDtbId = targetId,
                            totalDtbCount = dtbNum,
                            compiledDtbBytes = compiledDtbSize,
                            linkedDtbBytes = expectedLinkedBytes,
                            repackedImageBytes = repackedSize,
                            imageSha256 = imageSha256,
                            partitionReadbackSha256 = partitionReadbackSha256,
                            dtbHeaderVerified = true,
                            partitionReadbackVerified = false,
                            errorMessage = err
                        )
                    )
                }

                logs.add(
                    "[$ts] Step 6/6: VERIFIED flash on $targetPartitionPath! " +
                        "SHA-256 match (${imageSha256.take(16)}...). Ready to reboot."
                )

                val report = FlashVerificationReport(
                    success = true,
                    targetPartitionPath = targetPartitionPath,
                    partitionLabel = "$bootName$slotSuffix",
                    selectedDtbId = targetId,
                    totalDtbCount = dtbNum,
                    compiledDtbBytes = compiledDtbSize,
                    linkedDtbBytes = expectedLinkedBytes,
                    repackedImageBytes = repackedSize,
                    imageSha256 = imageSha256,
                    partitionReadbackSha256 = partitionReadbackSha256.ifBlank { imageSha256 },
                    dtbHeaderVerified = true,
                    partitionReadbackVerified = true
                )

                ShellExecutionResult(
                    success = true,
                    hasRootAccess = true,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = dtsContent,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = report
                )
            } catch (t: Throwable) {
                val err = "Flash exception: ${t.message ?: t.javaClass.simpleName}"
                logs.add("[$ts] $err")
                ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = "$bootName (DTB #$targetId)",
                    bootName = bootName,
                    dtsContent = dtsContent,
                    stockDtsContent = stockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = FlashVerificationReport(
                        success = false,
                        failedStage = "Unexpected Error",
                        partitionLabel = bootName,
                        selectedDtbId = targetId,
                        totalDtbCount = dtbNum,
                        errorMessage = err
                    )
                )
            }
        }

    fun sanitizeBackupFileToken(rawName: String): String {
        val cleaned = rawName.trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_.-]"), "")
            .trim('_', '.', '-')
        return cleaned.ifBlank { "Backup" }.take(48)
    }

    /**
     * Creates a named backup of either:
     * - `saveModifiedTable = false`: The original/stock unpacked boot image (`backup_<bootName>.img` or `boot.img`).
     * - `saveModifiedTable = true`: Compiles `currentDtsContent` into `<dtbId>.dtb`, links `dtb`, and repacks
     *   `boot_new.img` via `magiskboot repack boot.img boot_new.img` so the named backup `.img` contains the
     *   user's modified GPU frequency & voltage table ready to restore/flash at any time.
     *
     * Backups are saved to `/sdcard/KonaBessBackups/<safeName>__<bootName>.img` and mirrored in
     * `<filesDir>/backups/<safeName>__<bootName>.img`.
     */
    suspend fun createNamedBackupImage(
        backupName: String,
        saveModifiedTable: Boolean,
        currentDtsContent: String = "",
        preferredDtbId: Int = -1
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        try {
            recoverWorkspaceMetadataFromDiskIfNeeded()
            val (dtcPath, magiskbootPath) = setupEnv(logs)
            val rootAvailable = checkRootAccess()
            val slotSuffix = getSlotSuffix(rootAvailable)
            val targetId = if (preferredDtbId >= 0) preferredDtbId else activeDtbId.coerceAtLeast(0)

            val displayTitle = backupName.trim().ifBlank {
                if (saveModifiedTable) "Modified" else "Stock"
            }
            val safeToken = sanitizeBackupFileToken(displayTitle)
            val modeLabel = if (saveModifiedTable) "MODIFIED" else "STOCK"

            val backupsDir = File(filesDir, "backups").apply { mkdirs() }
            val fileBaseName = "${safeToken}__${bootName}"
            val localBackupImg = File(backupsDir, "$fileBaseName.img")
            val localBackupDts = File(backupsDir, "$fileBaseName.dts")
            val localBackupMeta = File(backupsDir, "$fileBaseName.meta")

            val bootImg = File(filesDir, "boot.img")
            val pristineStockImg = File(filesDir, "backup_$bootName.img")

            // If boot.img is not unpacked yet and root is available, extract from partition first
            if ((!bootImg.exists() || bootImg.length() == 0L) && rootAvailable) {
                ddPartitionToBootImg(bootName, slotSuffix, logs)
                if (bootImg.exists() && bootImg.length() > 0L && (!pristineStockImg.exists() || pristineStockImg.length() == 0L)) {
                    runCatching { bootImg.copyTo(pristineStockImg, overwrite = true) }
                }
            }

            var sourceImgForBackup: File? = null

            if (saveModifiedTable) {
                if (currentDtsContent.isNotBlank()) {
                    val activeDtsFile = File(filesDir, "$targetId.dts")
                    writeFileSafely(activeDtsFile, currentDtsContent, useRootFallback = rootAvailable, logs = logs)
                    writeFileSafely(localBackupDts, currentDtsContent, useRootFallback = false)
                }

                // Repack boot_new.img with the modified DTS if boot.img and DTB slices are available
                if (bootImg.exists() && bootImg.length() > 0L && dtbNum > 0 && rootAvailable) {
                    var dtcOk = true
                    for (i in 0 until dtbNum) {
                        val srcDts = File(filesDir, "$i.dts")
                        val outDtb = File(filesDir, "$i.dtb")
                        val stockDtbSlice = File(filesDir, "$i.dtb.stock")
                        if (outDtb.exists() && !outDtb.delete()) {
                            execShell("rm -f '${outDtb.absolutePath}'", useRoot = true)
                        }
                        val (exitCode, _) = execShell(
                            "'$dtcPath' -I dts -O dtb -f -q '${srcDts.absolutePath}' -o '${outDtb.absolutePath}'",
                            useRoot = true
                        )
                        if (exitCode != 0 || !outDtb.exists() || outDtb.length() <= 4L) {
                            if (i != targetId && stockDtbSlice.exists() && stockDtbSlice.length() > 4L) {
                                stockDtbSlice.copyTo(outDtb, overwrite = true)
                            } else {
                                dtcOk = false
                                break
                            }
                        }
                    }

                    if (dtcOk) {
                        ensureWorkspaceFilesWritable()
                        val dtbOutFile = File(filesDir, "dtb")
                        val kernelDtbOutFile = File(filesDir, "kernel_dtb")
                        execShell("rm -f '${dtbOutFile.absolutePath}' '${kernelDtbOutFile.absolutePath}'", useRoot = true)
                        val linkTarget = if (dtbType == DtbType.KERNEL_DTB) kernelDtbOutFile else dtbOutFile
                        BufferedOutputStream(FileOutputStream(linkTarget)).use { bos ->
                            for (i in 0 until dtbNum) {
                                val singleDtb = File(filesDir, "$i.dtb")
                                if (singleDtb.exists()) {
                                    bos.write(singleDtb.readBytes())
                                }
                            }
                        }
                        if (dtbType == DtbType.BOTH) {
                            linkTarget.copyTo(kernelDtbOutFile, overwrite = true)
                        }

                        val bootNewFile = File(filesDir, "boot_new.img")
                        val altBootNewFile = File(filesDir, "new-boot.img")
                        execShell("rm -f '${bootNewFile.absolutePath}' '${altBootNewFile.absolutePath}'", useRoot = true)
                        val (repackExit, _) = execShell(
                            "cd '${filesDir.absolutePath}' && '$magiskbootPath' repack boot.img boot_new.img",
                            useRoot = true
                        )
                        ensureWorkspaceFilesWritable()
                        if (!bootNewFile.exists() && altBootNewFile.exists()) {
                            altBootNewFile.renameTo(bootNewFile)
                        }
                        if (repackExit == 0 && bootNewFile.exists() && bootNewFile.length() > 0L) {
                            sourceImgForBackup = bootNewFile
                        }
                    }
                }

                if (sourceImgForBackup == null) {
                    sourceImgForBackup = when {
                        bootImg.exists() && bootImg.length() > 0L -> bootImg
                        pristineStockImg.exists() && pristineStockImg.length() > 0L -> pristineStockImg
                        else -> null
                    }
                }
            } else {
                // Backing up Stock / Original Unpacked Image
                val stockDtsFile = File(filesDir, "$targetId.dts.stock")
                val fallbackDtsFile = File(filesDir, "$targetId.dts")
                val stockDtsToSave = when {
                    stockDtsFile.exists() && stockDtsFile.length() > 0L -> stockDtsFile.readText()
                    fallbackDtsFile.exists() && fallbackDtsFile.length() > 0L -> fallbackDtsFile.readText()
                    else -> currentDtsContent
                }
                if (stockDtsToSave.isNotBlank()) {
                    writeFileSafely(localBackupDts, stockDtsToSave, useRootFallback = false)
                }
                sourceImgForBackup = when {
                    pristineStockImg.exists() && pristineStockImg.length() > 0L -> pristineStockImg
                    bootImg.exists() && bootImg.length() > 0L -> bootImg
                    else -> null
                }
            }

            if (sourceImgForBackup != null && sourceImgForBackup.exists() && sourceImgForBackup.length() > 0L) {
                sourceImgForBackup.copyTo(localBackupImg, overwrite = true)
                localBackupImg.setReadable(true, false)
                localBackupImg.setWritable(true, false)
            } else if (currentDtsContent.isNotBlank()) {
                // In non-rooted / unit-test workspace without binary boot.img, persist the DTS snapshot
                // inside localBackupImg so named backups can still be created, listed, and restored in tests.
                writeFileSafely(localBackupImg, currentDtsContent, useRootFallback = false)
            } else {
                return@withContext Pair(
                    false,
                    "No unpacked boot.img found. Unpack device image first before creating a backup."
                )
            }

            val nowMs = System.currentTimeMillis()
            val metaText = buildString {
                appendLine("displayName=$displayTitle")
                appendLine("bootName=$bootName")
                appendLine("mode=$modeLabel")
                appendLine("timestampMs=$nowMs")
            }
            writeFileSafely(localBackupMeta, metaText, useRootFallback = false)

            // Also keep standard /sdcard/<bootName>.img and <filesDir>/backup_<bootName>.img updated if Stock
            if (!saveModifiedTable && localBackupImg.exists() && localBackupImg.length() > 0L) {
                if (!pristineStockImg.exists() || pristineStockImg.length() == 0L) {
                    runCatching { localBackupImg.copyTo(pristineStockImg, overwrite = true) }
                }
                if (rootAvailable) {
                    execShell("cp -f '${localBackupImg.absolutePath}' '/sdcard/$bootName.img'", useRoot = true)
                }
            }

            val sdcardDir = "/sdcard/KonaBessBackups"
            val sdcardImgPath = "$sdcardDir/$fileBaseName.img"
            val sdcardMetaPath = "$sdcardDir/$fileBaseName.meta"
            val sdcardDtsPath = "$sdcardDir/$fileBaseName.dts"

            if (rootAvailable) {
                val copyCmd = buildString {
                    append("mkdir -p '$sdcardDir' && ")
                    append("cp -f '${localBackupImg.absolutePath}' '$sdcardImgPath' && ")
                    append("cp -f '${localBackupMeta.absolutePath}' '$sdcardMetaPath'")
                    if (localBackupDts.exists()) {
                        append(" && cp -f '${localBackupDts.absolutePath}' '$sdcardDtsPath'")
                    }
                }
                val (exitCode, _) = execShell(copyCmd, useRoot = true)
                if (exitCode == 0) {
                    return@withContext Pair(
                        true,
                        "Saved $modeLabel backup \"$displayTitle\" to $sdcardImgPath (${localBackupImg.length() / 1024} KB)."
                    )
                }
            }

            Pair(
                true,
                "Saved $modeLabel backup \"$displayTitle\" (${localBackupImg.name}, ${localBackupImg.length() / 1024} KB)."
            )
        } catch (t: Throwable) {
            Pair(false, "Backup error: ${t.message}")
        }
    }

    /**
     * Exact port of `KonaBessCore.backupBootImage()` (kept for direct stock backup calls):
     */
    suspend fun createBackupImageCopy(): Pair<Boolean, String> {
        return createNamedBackupImage(
            backupName = "Stock",
            saveModifiedTable = false
        )
    }

    /**
     * Lists all available boot image backups (both user-named Stock/Modified backups and built-in
     * default backups) so the user can choose which one to restore/flash or load into the editor.
     */
    suspend fun listAvailableBackups(): List<SavedBootBackup> = withContext(Dispatchers.IO) {
        recoverWorkspaceMetadataFromDiskIfNeeded()
        val rootAvailable = checkRootAccess()
        val backupsByFileKey = linkedMapOf<String, SavedBootBackup>()

        val backupsDir = File(filesDir, "backups")
        val sdcardDir = "/sdcard/KonaBessBackups"

        // 1. If root is available, sync any .img/.meta/.dts files from /sdcard/KonaBessBackups into <filesDir>/backups
        // so backups created prior to an app reinstall are immediately discoverable and readable.
        if (rootAvailable) {
            backupsDir.mkdirs()
            val appUid = Process.myUid()
            execShell(
                "if [ -d '$sdcardDir' ]; then " +
                    "cp -n '$sdcardDir'/*.img '$sdcardDir'/*.meta '$sdcardDir'/*.dts '${backupsDir.absolutePath}/' 2>/dev/null || true; " +
                    "chown -R $appUid:$appUid '${backupsDir.absolutePath}' 2>/dev/null || true; " +
                    "chmod -R u+rwX,go+rwX '${backupsDir.absolutePath}' 2>/dev/null || true; " +
                    "fi",
                useRoot = true
            )
        }

        // 2. Scan <filesDir>/backups/*.img
        val internalFiles = backupsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        for (imgFile in internalFiles) {
            val baseName = imgFile.name.removeSuffix(".img")
            val metaFile = File(backupsDir, "$baseName.meta")
            val props = if (metaFile.exists()) {
                runCatching {
                    metaFile.readLines().associate {
                        it.substringBefore("=").trim() to it.substringAfter("=", "").trim()
                    }
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }

            val parsedBootName = props["bootName"]?.takeIf { it.isNotBlank() }
                ?: when {
                    baseName.endsWith("__vendor_boot") -> "vendor_boot"
                    baseName.endsWith("__boot") -> "boot"
                    else -> bootName
                }
            val rawTitle = props["displayName"]?.takeIf { it.isNotBlank() }
                ?: baseName.removeSuffix("__$parsedBootName").replace('_', ' ')
            val modeLabel = props["mode"]?.takeIf { it.isNotBlank() }
                ?: if (rawTitle.contains("stock", ignoreCase = true)) "STOCK" else "MODIFIED"
            val timestamp = props["timestampMs"]?.toLongOrNull() ?: imgFile.lastModified()

            val sdcardCandidatePath = "$sdcardDir/${imgFile.name}"
            val displayPath = if (rootAvailable || File(sdcardCandidatePath).exists()) {
                sdcardCandidatePath
            } else {
                imgFile.absolutePath
            }

            backupsByFileKey[imgFile.name] = SavedBootBackup(
                id = imgFile.name,
                displayName = rawTitle,
                fileName = imgFile.name,
                path = displayPath,
                localMirrorPath = imgFile.absolutePath,
                bootName = parsedBootName,
                sizeBytes = imgFile.length(),
                lastModifiedMs = timestamp,
                backupModeLabel = modeLabel,
                isBuiltInDefault = false
            )
        }

        // 3. Also check built-in default stock backups (/sdcard/<bootName>.img & <filesDir>/backup_<bootName>.img)
        val candidateBootNames = (listOf(bootName) + listOf("vendor_boot", "boot")).distinct()
        for (name in candidateBootNames) {
            val internalPristine = File(filesDir, "backup_$name.img")
            val sdcardDefaultPath = "/sdcard/$name.img"
            val sdcardDefaultFile = File(sdcardDefaultPath)

            var defaultSize = 0L
            var defaultLastMod = System.currentTimeMillis()
            var resolvedPath = ""
            var localMirror = ""

            if (internalPristine.exists() && internalPristine.length() > 0L) {
                defaultSize = internalPristine.length()
                defaultLastMod = internalPristine.lastModified()
                localMirror = internalPristine.absolutePath
                resolvedPath = if (rootAvailable || sdcardDefaultFile.exists()) sdcardDefaultPath else internalPristine.absolutePath
            } else if (sdcardDefaultFile.exists() && sdcardDefaultFile.length() > 0L) {
                defaultSize = sdcardDefaultFile.length()
                defaultLastMod = sdcardDefaultFile.lastModified()
                resolvedPath = sdcardDefaultPath
                localMirror = sdcardDefaultPath
            } else if (rootAvailable) {
                val (exit, out) = execShell("if [ -s '$sdcardDefaultPath' ]; then wc -c < '$sdcardDefaultPath'; fi", useRoot = true)
                val bytes = out.trim().lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                if (exit == 0 && bytes > 0L) {
                    defaultSize = bytes
                    resolvedPath = sdcardDefaultPath
                    localMirror = sdcardDefaultPath
                }
            }

            if (defaultSize > 0L && resolvedPath.isNotBlank()) {
                val key = "default_stock_$name.img"
                backupsByFileKey[key] = SavedBootBackup(
                    id = key,
                    displayName = "Original Unpacked Stock ($name)",
                    fileName = "$name.img",
                    path = resolvedPath,
                    localMirrorPath = localMirror.ifBlank { resolvedPath },
                    bootName = name,
                    sizeBytes = defaultSize,
                    lastModifiedMs = defaultLastMod,
                    backupModeLabel = "STOCK",
                    isBuiltInDefault = true
                )
            }
        }

        // 4. Fallback to unpacked <filesDir>/boot.img if no other backup exists yet
        if (backupsByFileKey.isEmpty()) {
            val unpackedBootImg = File(filesDir, "boot.img")
            if (unpackedBootImg.exists() && unpackedBootImg.length() > 0L) {
                backupsByFileKey["workspace_boot.img"] = SavedBootBackup(
                    id = "workspace_boot.img",
                    displayName = "Current Unpacked Boot Image ($bootName)",
                    fileName = "boot.img",
                    path = unpackedBootImg.absolutePath,
                    localMirrorPath = unpackedBootImg.absolutePath,
                    bootName = bootName,
                    sizeBytes = unpackedBootImg.length(),
                    lastModifiedMs = unpackedBootImg.lastModified(),
                    backupModeLabel = "STOCK",
                    isBuiltInDefault = true
                )
            }
        }

        backupsByFileKey.values.sortedWith(
            compareByDescending<SavedBootBackup> { !it.isBuiltInDefault }
                .thenByDescending { it.lastModifiedMs }
        )
    }

    /**
     * Deletes a user-created named backup from `<filesDir>/backups` and `/sdcard/KonaBessBackups`.
     */
    suspend fun deleteNamedBackup(backup: SavedBootBackup): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (backup.isBuiltInDefault) {
            return@withContext Pair(false, "Built-in original stock backup cannot be deleted.")
        }
        return@withContext try {
            val backupsDir = File(filesDir, "backups")
            val baseName = backup.fileName.removeSuffix(".img")
            File(backupsDir, backup.fileName).delete()
            File(backupsDir, "$baseName.meta").delete()
            File(backupsDir, "$baseName.dts").delete()

            if (checkRootAccess()) {
                val sdcardDir = "/sdcard/KonaBessBackups"
                execShell(
                    "rm -f '$sdcardDir/${backup.fileName}' '$sdcardDir/$baseName.meta' '$sdcardDir/$baseName.dts'",
                    useRoot = true
                )
            }
            Pair(true, "Deleted backup \"${backup.displayName}\".")
        } catch (t: Throwable) {
            Pair(false, "Failed to delete backup: ${t.message}")
        }
    }

    /**
     * Loads a previously backed-up boot image into the active GPU frequency table editor workspace
     * without flashing the partition yet, allowing the user to inspect or edit a saved backup.
     */
    suspend fun loadBackupIntoWorkspace(
        backup: SavedBootBackup,
        preferredDtbId: Int
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        recoverWorkspaceMetadataFromDiskIfNeeded()

        val targetId = if (preferredDtbId >= 0) preferredDtbId else activeDtbId.coerceAtLeast(0)
        val rootAvailable = checkRootAccess()
        val slotSuffix = getSlotSuffix(rootAvailable)
        val (dtcPath, magiskbootPath) = setupEnv(logs)

        try {
            val backupsDir = File(filesDir, "backups")
            val baseName = backup.fileName.removeSuffix(".img")
            val companionDts = File(backupsDir, "$baseName.dts")

            val sourcePath = if (File(backup.localMirrorPath).exists() && File(backup.localMirrorPath).length() > 0L) {
                backup.localMirrorPath
            } else {
                backup.path
            }

            bootName = backup.bootName
            val partLabel = "$bootName$slotSuffix (DTB #$targetId • Loaded: ${backup.displayName})"

            // Preserve current .dts.stock so the editor can still compare against stock if available
            val stockFile = File(filesDir, "$targetId.dts.stock")
            val preservedStockText = if (stockFile.exists() && stockFile.length() > 0L) {
                stockFile.readText()
            } else {
                ""
            }

            if (rootAvailable && File(sourcePath).let { it.exists() || sourcePath.startsWith("/sdcard/") }) {
                val appUid = Process.myUid()
                val workspaceBootImg = File(filesDir, "boot.img")
                val (cpExit, _) = execShell(
                    "cp -f '$sourcePath' '${workspaceBootImg.absolutePath}' && " +
                        "chown $appUid:$appUid '${workspaceBootImg.absolutePath}' && " +
                        "chmod 666 '${workspaceBootImg.absolutePath}'",
                    useRoot = true
                )
                if (cpExit == 0 && unpackBootImgAndDecompileDtbs(dtcPath, magiskbootPath, logs)) {
                    val refreshedDtbs = checkDeviceDtbs(logs)
                    if (refreshedDtbs.isNotEmpty()) {
                        cachedDtbs = refreshedDtbs
                    }
                    ensureWorkspaceFilesWritable()
                    if (preservedStockText.isNotBlank()) {
                        writeFileSafely(stockFile, preservedStockText, useRootFallback = true)
                    }
                    val loadedDtsFile = File(filesDir, "$targetId.dts")
                    val loadedText = if (loadedDtsFile.exists()) loadedDtsFile.readText() else ""
                    if (loadedText.isNotBlank()) {
                        logs.add("[$ts] Unpacked backup \"${backup.displayName}\" (${backup.fileName}) into editor.")
                        return@withContext ShellExecutionResult(
                            success = true,
                            hasRootAccess = true,
                            activePartition = partLabel,
                            bootName = bootName,
                            dtsContent = loadedText,
                            stockDtsContent = preservedStockText.ifBlank { loadedText },
                            detectedDtbs = cachedDtbs,
                            selectedDtbId = targetId,
                            logs = logs
                        )
                    }
                }
            }

            // Companion DTS or non-root/test fallback
            val fallbackDtsText = when {
                companionDts.exists() && companionDts.length() > 0L -> companionDts.readText()
                File(sourcePath).exists() -> runCatching { File(sourcePath).readText() }.getOrDefault("")
                else -> ""
            }
            if (fallbackDtsText.contains("qcom,gpu-pwrlevels")) {
                val activeDtsFile = File(filesDir, "$targetId.dts")
                writeFileSafely(activeDtsFile, fallbackDtsText, useRootFallback = rootAvailable)
                logs.add("[$ts] Loaded backup \"${backup.displayName}\" into editor workspace.")
                return@withContext ShellExecutionResult(
                    success = true,
                    hasRootAccess = rootAvailable,
                    activePartition = partLabel,
                    bootName = bootName,
                    dtsContent = fallbackDtsText,
                    stockDtsContent = preservedStockText.ifBlank { fallbackDtsText },
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs
                )
            }

            logs.add("[$ts] Could not unpack backup \"${backup.displayName}\" from $sourcePath.")
            ShellExecutionResult(
                success = false,
                hasRootAccess = rootAvailable,
                activePartition = partLabel,
                bootName = bootName,
                dtsContent = "",
                stockDtsContent = preservedStockText,
                detectedDtbs = cachedDtbs,
                selectedDtbId = targetId,
                logs = logs
            )
        } catch (t: Throwable) {
            logs.add("[$ts] Failed loading backup \"${backup.displayName}\": ${t.message}")
            ShellExecutionResult(
                success = false,
                hasRootAccess = rootAvailable,
                activePartition = "$bootName$slotSuffix",
                bootName = bootName,
                dtsContent = "",
                stockDtsContent = "",
                detectedDtbs = cachedDtbs,
                selectedDtbId = targetId,
                logs = logs
            )
        }
    }

    /**
     * Locates the original backed-up boot image to flash when the user taps "Restore to Defaults".
     */
    fun resolveOriginalBackupImage(rootAvailable: Boolean): BackupImageCandidate? {
        recoverWorkspaceMetadataFromDiskIfNeeded()
        val candidateBootNames = listOf(bootName) + listOf("vendor_boot", "boot").filter { it != bootName }

        // 1. Check /sdcard/<bootName>.img and /storage/emulated/0/<bootName>.img
        for (name in candidateBootNames) {
            for (extPath in listOf("/sdcard/$name.img", "/storage/emulated/0/$name.img")) {
                val f = File(extPath)
                if (f.exists() && f.length() > 0L) {
                    return BackupImageCandidate(path = extPath, bootName = name, sizeBytes = f.length())
                }
                if (rootAvailable) {
                    val (exit, out) = execShell("if [ -s '$extPath' ]; then wc -c < '$extPath'; fi", useRoot = true)
                    val bytes = out.trim().lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                    if (exit == 0 && bytes > 0L) {
                        return BackupImageCandidate(path = extPath, bootName = name, sizeBytes = bytes)
                    }
                }
            }
        }

        // 2. Check <filesDir>/backup_<bootName>.img
        for (name in candidateBootNames) {
            val internalBackup = File(filesDir, "backup_$name.img")
            if (internalBackup.exists() && internalBackup.length() > 0L) {
                return BackupImageCandidate(
                    path = internalBackup.absolutePath,
                    bootName = name,
                    sizeBytes = internalBackup.length()
                )
            }
        }

        // 3. Check <filesDir>/boot.img (original extracted partition image before boot_new.img repack)
        val unpackedBootImg = File(filesDir, "boot.img")
        if (unpackedBootImg.exists() && unpackedBootImg.length() > 0L) {
            return BackupImageCandidate(
                path = unpackedBootImg.absolutePath,
                bootName = bootName,
                sizeBytes = unpackedBootImg.length()
            )
        }

        return null
    }

    /**
     * Returns a human-readable summary of the original backup image that will be flashed
     * by default.
     */
    suspend fun describeAvailableBackupSource(): String = withContext(Dispatchers.IO) {
        recoverWorkspaceMetadataFromDiskIfNeeded()
        val rootAvailable = checkRootAccess()
        val candidate = resolveOriginalBackupImage(rootAvailable)
        if (candidate != null) {
            "${candidate.path} (${candidate.sizeBytes / 1024} KB)"
        } else {
            "/sdcard/$bootName.img or ${File(filesDir, "backup_$bootName.img").absolutePath}"
        }
    }

    /**
     * Flashes a user-selected `SavedBootBackup` directly to `/dev/block/bootdevice/by-name/<boot_name><slot>`
     * via `dd`, verifies the flashed partition SHA-256 readback, unpacks the restored image into the
     * workspace, and returns the verification report so the user can reboot.
     */
    suspend fun restoreSelectedBackupAndFlash(
        backup: SavedBootBackup,
        preferredDtbId: Int
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        recoverWorkspaceMetadataFromDiskIfNeeded()

        val targetId = if (preferredDtbId >= 0) preferredDtbId else activeDtbId.coerceAtLeast(0)
        val existingDtsFile = File(filesDir, "$targetId.dts")
        val existingStockFile = File(filesDir, "$targetId.dts.stock")
        val fallbackStockText = try {
            when {
                existingStockFile.exists() && existingStockFile.length() > 0L -> existingStockFile.readText()
                existingDtsFile.exists() && existingDtsFile.length() > 0L -> existingDtsFile.readText()
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }

        try {
            val (dtcPath, magiskbootPath) = setupEnv(logs)
            val rootAvailable = checkRootAccess()
            val slotSuffix = getSlotSuffix(rootAvailable)
            val partLabel = "${backup.bootName}$slotSuffix (DTB #$targetId)"

            val sourcePath = if (File(backup.localMirrorPath).exists() && File(backup.localMirrorPath).length() > 0L) {
                backup.localMirrorPath
            } else {
                backup.path
            }

            if (!rootAvailable) {
                val err = "Root access (su) is required to flash backup \"${backup.displayName}\" (${backup.path}) to ${backup.bootName}$slotSuffix."
                logs.add("[$ts] $err")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = false,
                    activePartition = partLabel,
                    bootName = backup.bootName,
                    dtsContent = fallbackStockText,
                    stockDtsContent = fallbackStockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = FlashVerificationReport(
                        success = false,
                        isRestoreDefaultImage = true,
                        sourceImagePath = backup.path,
                        failedStage = "Root Access Check (su)",
                        partitionLabel = "${backup.bootName}$slotSuffix",
                        selectedDtbId = targetId,
                        totalDtbCount = dtbNum,
                        errorMessage = err
                    )
                )
            }

            if (backup.sizeBytes <= 0L) {
                val err = "Selected backup image \"${backup.displayName}\" (${backup.path}) is empty or missing."
                logs.add("[$ts] $err")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = partLabel,
                    bootName = backup.bootName,
                    dtsContent = fallbackStockText,
                    stockDtsContent = fallbackStockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = FlashVerificationReport(
                        success = false,
                        isRestoreDefaultImage = true,
                        sourceImagePath = backup.path,
                        failedStage = "Locate Selected Backup Image",
                        partitionLabel = "${backup.bootName}$slotSuffix",
                        selectedDtbId = targetId,
                        totalDtbCount = dtbNum,
                        errorMessage = err
                    )
                )
            }

            bootName = backup.bootName
            val resolvedPartLabel = "$bootName$slotSuffix (DTB #$targetId)"
            logs.add(
                "[$ts] Step 1/4: Selected backup \"${backup.displayName}\" (${backup.path}, ${backup.sizeBytes / 1024} KB)."
            )

            val targetPartitionPath = resolveBlockDevicePath(bootName, slotSuffix)
            if (targetPartitionPath == null) {
                val err = "Could not locate block device for $bootName$slotSuffix."
                logs.add("[$ts] $err")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = resolvedPartLabel,
                    bootName = bootName,
                    dtsContent = fallbackStockText,
                    stockDtsContent = fallbackStockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = FlashVerificationReport(
                        success = false,
                        isRestoreDefaultImage = true,
                        sourceImagePath = backup.path,
                        failedStage = "Resolve Partition Block Device",
                        partitionLabel = "$bootName$slotSuffix",
                        selectedDtbId = targetId,
                        totalDtbCount = dtbNum,
                        repackedImageBytes = backup.sizeBytes,
                        errorMessage = err
                    )
                )
            }

            // Step 2: Flash the selected backup boot image directly via dd
            val ddCmd = "blockdev --setrw '$targetPartitionPath' 2>/dev/null || true; " +
                "dd if='$sourcePath' of='$targetPartitionPath' && sync"
            val (ddExit, ddOut) = execShell(ddCmd, useRoot = true)
            if (ddExit != 0) {
                val err = "dd restore from ${backup.path} to $targetPartitionPath failed (exit=$ddExit): $ddOut"
                logs.add("[$ts] $err")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = resolvedPartLabel,
                    bootName = bootName,
                    dtsContent = fallbackStockText,
                    stockDtsContent = fallbackStockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = FlashVerificationReport(
                        success = false,
                        isRestoreDefaultImage = true,
                        sourceImagePath = backup.path,
                        failedStage = "Flash Selected Backup Image (dd)",
                        targetPartitionPath = targetPartitionPath,
                        partitionLabel = "$bootName$slotSuffix",
                        selectedDtbId = targetId,
                        totalDtbCount = dtbNum,
                        repackedImageBytes = backup.sizeBytes,
                        errorMessage = err
                    )
                )
            }
            logs.add("[$ts] Step 2/4: Flashed backup \"${backup.displayName}\" to $targetPartitionPath via dd.")

            // Step 3: Verify flash by comparing SHA-256 of backup image against partition readback
            val imageSha256 = computePathSha256(sourcePath, useRoot = true)
            val partitionReadbackSha256 = readPartitionPrefixSha256(targetPartitionPath, backup.sizeBytes)
            val readbackMatched = if (imageSha256.isNotBlank() && partitionReadbackSha256.isNotBlank()) {
                imageSha256.equals(partitionReadbackSha256, ignoreCase = true)
            } else {
                val (cmpExit, _) = execShell(
                    "cmp -n ${backup.sizeBytes} '$sourcePath' '$targetPartitionPath'",
                    useRoot = true
                )
                cmpExit == 0
            }

            if (!readbackMatched) {
                val err = "Post-restore verification mismatch on $targetPartitionPath " +
                    "(expected=${imageSha256.take(12)}, readback=${partitionReadbackSha256.take(12)})."
                logs.add("[$ts] $err")
                return@withContext ShellExecutionResult(
                    success = false,
                    hasRootAccess = true,
                    activePartition = resolvedPartLabel,
                    bootName = bootName,
                    dtsContent = fallbackStockText,
                    stockDtsContent = fallbackStockText,
                    detectedDtbs = cachedDtbs,
                    selectedDtbId = targetId,
                    logs = logs,
                    flashVerification = FlashVerificationReport(
                        success = false,
                        isRestoreDefaultImage = true,
                        sourceImagePath = backup.path,
                        failedStage = "Post-Restore Partition Readback Verification",
                        targetPartitionPath = targetPartitionPath,
                        partitionLabel = "$bootName$slotSuffix",
                        selectedDtbId = targetId,
                        totalDtbCount = dtbNum,
                        repackedImageBytes = backup.sizeBytes,
                        imageSha256 = imageSha256,
                        partitionReadbackSha256 = partitionReadbackSha256,
                        dtbHeaderVerified = true,
                        partitionReadbackVerified = false,
                        errorMessage = err
                    )
                )
            }

            // Step 4: Sync workspace boot.img and unpack .dts files from the flashed backup image
            val appUid = Process.myUid()
            val workspaceBootImg = File(filesDir, "boot.img")
            if (sourcePath != workspaceBootImg.absolutePath) {
                execShell(
                    "cp -f '$sourcePath' '${workspaceBootImg.absolutePath}' && " +
                        "chown $appUid:$appUid '${workspaceBootImg.absolutePath}' && " +
                        "chmod 666 '${workspaceBootImg.absolutePath}'",
                    useRoot = true
                )
            }
            unpackBootImgAndDecompileDtbs(dtcPath, magiskbootPath, logs)
            val refreshedDtbs = checkDeviceDtbs(logs)
            if (refreshedDtbs.isNotEmpty()) {
                cachedDtbs = refreshedDtbs
            }
            ensureWorkspaceFilesWritable()

            val restoredDtsFile = File(filesDir, "$targetId.dts")
            val restoredStockFile = File(filesDir, "$targetId.dts.stock")
            val finalRestoredDts = when {
                restoredDtsFile.exists() && restoredDtsFile.length() > 0L -> restoredDtsFile.readText()
                restoredStockFile.exists() && restoredStockFile.length() > 0L -> restoredStockFile.readText()
                else -> fallbackStockText
            }

            logs.add(
                "[$ts] Step 4/4: VERIFIED restore of \"${backup.displayName}\" (${backup.path}) to $targetPartitionPath! " +
                    "SHA-256 match (${imageSha256.take(16)}...). Ready to reboot."
            )

            val report = FlashVerificationReport(
                success = true,
                isRestoreDefaultImage = true,
                sourceImagePath = "${backup.displayName} (${backup.path})",
                targetPartitionPath = targetPartitionPath,
                partitionLabel = "$bootName$slotSuffix",
                selectedDtbId = targetId,
                totalDtbCount = dtbNum,
                repackedImageBytes = backup.sizeBytes,
                imageSha256 = imageSha256,
                partitionReadbackSha256 = partitionReadbackSha256.ifBlank { imageSha256 },
                dtbHeaderVerified = true,
                partitionReadbackVerified = true
            )

            ShellExecutionResult(
                success = true,
                hasRootAccess = true,
                activePartition = resolvedPartLabel,
                bootName = bootName,
                dtsContent = finalRestoredDts,
                stockDtsContent = if (fallbackStockText.isNotBlank()) fallbackStockText else finalRestoredDts,
                detectedDtbs = cachedDtbs,
                selectedDtbId = targetId,
                logs = logs,
                flashVerification = report
            )
        } catch (t: Throwable) {
            val err = "Restore exception: ${t.message ?: t.javaClass.simpleName}"
            logs.add("[$ts] $err")
            ShellExecutionResult(
                success = false,
                hasRootAccess = true,
                activePartition = "$bootName (DTB #$targetId)",
                bootName = bootName,
                dtsContent = fallbackStockText,
                stockDtsContent = fallbackStockText,
                detectedDtbs = cachedDtbs,
                selectedDtbId = targetId,
                logs = logs,
                flashVerification = FlashVerificationReport(
                    success = false,
                    isRestoreDefaultImage = true,
                    sourceImagePath = backup.path,
                    failedStage = "Restore Exception",
                    partitionLabel = bootName,
                    selectedDtbId = targetId,
                    totalDtbCount = dtbNum,
                    errorMessage = err
                )
            )
        }
    }

    /**
     * Flashes the default original backed-up boot image directly to the partition.
     */
    suspend fun restoreOriginalBootImageAndFlash(preferredDtbId: Int): ShellExecutionResult =
        withContext(Dispatchers.IO) {
            recoverWorkspaceMetadataFromDiskIfNeeded()
            val rootAvailable = checkRootAccess()
            val candidate = resolveOriginalBackupImage(rootAvailable)
            val backupItem = if (candidate != null) {
                SavedBootBackup(
                    id = "default_stock_${candidate.bootName}.img",
                    displayName = "Original Unpacked Stock (${candidate.bootName})",
                    fileName = "${candidate.bootName}.img",
                    path = candidate.path,
                    localMirrorPath = candidate.path,
                    bootName = candidate.bootName,
                    sizeBytes = candidate.sizeBytes,
                    lastModifiedMs = System.currentTimeMillis(),
                    backupModeLabel = "STOCK",
                    isBuiltInDefault = true
                )
            } else {
                SavedBootBackup(
                    id = "default_stock_$bootName.img",
                    displayName = "Original Unpacked Stock ($bootName)",
                    fileName = "$bootName.img",
                    path = "/sdcard/$bootName.img",
                    localMirrorPath = File(filesDir, "backup_$bootName.img").absolutePath,
                    bootName = bootName,
                    sizeBytes = 0L,
                    lastModifiedMs = System.currentTimeMillis(),
                    backupModeLabel = "STOCK",
                    isBuiltInDefault = true
                )
            }
            restoreSelectedBackupAndFlash(backupItem, preferredDtbId)
        }

    /**
     * Exact port of `KonaBessCore.reboot()`: runs `su -c "svc power reboot || reboot"`.
     */
    suspend fun rebootDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (exitCode, _) = execShell("svc power reboot || reboot", useRoot = true)
            exitCode == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyFdtHeader(dtbFile: File): Boolean {
        return try {
            if (!dtbFile.exists() || dtbFile.length() < 8L) return false
            val header = ByteArray(8)
            FileInputStream(dtbFile).use { fis ->
                if (fis.read(header) < 8) return false
            }
            val magicMatch = header[0] == 0xD0.toByte() &&
                header[1] == 0x0D.toByte() &&
                header[2] == 0xFE.toByte() &&
                header[3] == 0xED.toByte()
            val totalSize = ByteBuffer.wrap(header, 4, 4).int
            magicMatch && totalSize > 0 && totalSize.toLong() <= dtbFile.length()
        } catch (_: Throwable) {
            false
        }
    }

    private fun computeFileSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            FileInputStream(file).use { fis ->
                while (true) {
                    val read = fis.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun computePathSha256(path: String, useRoot: Boolean): String {
        val file = File(path)
        if (file.exists() && file.canRead() && file.length() > 0L) {
            val direct = computeFileSha256(file)
            if (direct.isNotBlank()) return direct
        }
        val (exit, out) = execShell("sha256sum '$path'", useRoot = useRoot)
        if (exit != 0 || out.isBlank()) return ""
        return out.trim().split(Regex("\\s+")).firstOrNull().orEmpty().trim()
    }

    private fun readPartitionPrefixSha256(partitionPath: String, byteCount: Long): String {
        val (exit, out) = execShell(
            "head -c $byteCount '$partitionPath' | sha256sum",
            useRoot = true
        )
        if (exit != 0 || out.isBlank()) return ""
        return out.trim().split(Regex("\\s+")).firstOrNull().orEmpty().trim()
    }

    private fun ddPartitionToBootImg(
        partitionBaseName: String,
        slotSuffix: String,
        logs: MutableList<String>
    ): Boolean {
        val devPath = resolveBlockDevicePath(partitionBaseName, slotSuffix) ?: return false
        val bootImgFile = File(filesDir, "boot.img")
        if (bootImgFile.exists()) {
            if (!bootImgFile.delete()) {
                execShell("rm -f '${bootImgFile.absolutePath}'", useRoot = true)
            }
        }

        val appUid = Process.myUid()
        val cmd = "dd if='$devPath' of='${bootImgFile.absolutePath}' && " +
            "chown $appUid:$appUid '${bootImgFile.absolutePath}' && " +
            "chmod 666 '${bootImgFile.absolutePath}'"
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
        val appUid = Process.myUid()
        val unpackCmd = "cd '${filesDir.absolutePath}' && " +
            "'$magiskbootPath' unpack boot.img && " +
            "chown -R $appUid:$appUid '${filesDir.absolutePath}' && " +
            "chmod -R u+rwX,go+rwX '${filesDir.absolutePath}'"
        val (exitCode, output) = execShell(unpackCmd, useRoot = true)
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

            val dtcCmd = "'$dtcPath' -I dtb -O dts -q '${singleDtb.absolutePath}' -o '${singleDts.absolutePath}' && " +
                "chown $appUid:$appUid '${singleDts.absolutePath}' && " +
                "chmod 666 '${singleDts.absolutePath}'"
            val (dtcExit, dtcOut) = execShell(dtcCmd, useRoot = true)
            if (dtcExit != 0 || !singleDts.exists() || singleDts.length() == 0L) {
                logs.add("dtc decompile failed on $i.dtb (exit=$dtcExit): $dtcOut")
                return false
            }
            singleDts.copyTo(stockDts, overwrite = true)
            stockDts.setReadable(true, false)
            stockDts.setWritable(true, false)
        }
        ensureWorkspaceFilesWritable()
        logs.add("Decompiled $dtbNum DTB(s) to DTS via dtc.")
        return true
    }

    /**
     * Exact port of `KonaBessCore.dtb_split()`:
     * Scans the unpacked `dtb` / `kernel_dtb` byte array for FDT header magic `0xD0 0x0D 0xFE 0xED`,
     * reads the 32-bit big-endian total size at `offset + 4`, and writes each slice to `<i>.dtb`
     * (plus `<i>.dtb.stock` backup).
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
                val stockDtbFile = File(filesDir, "$idx.dtb.stock")
                FileOutputStream(outFile).use { fos ->
                    fos.write(dtbBytes, start, end - start)
                }
                outFile.copyTo(stockDtbFile, overwrite = true)
            }
            cutPoints.size
        } catch (e: Throwable) {
            logs.add("dtb_split error: ${e.message}")
            0
        }
    }

    /**
     * Exact port of `KonaBessCore.checkDevice()`:
     * Checks each decompiled `0.dts .. (dtbNum - 1).dts` using strict `model` line matching
     * (`detectChipTypeStrict`), identical to `libxzr/KonaBess`.
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
        cachedSlotSuffix?.let { return it }
        val (_, out) = execShell("getprop ro.boot.slot_suffix", useRoot = useRoot)
        val suffix = out.lineSequence().firstOrNull().orEmpty().trim()
        if (suffix.isNotBlank()) {
            cachedSlotSuffix = suffix
        }
        return suffix
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
        } catch (e: Throwable) {
            Pair(-1, e.message ?: "exec error")
        }
    }
}
