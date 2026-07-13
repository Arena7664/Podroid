/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AVF (Android Virtualization Framework) diagnostic + smoke-test entry point.
 *
 * The android.system.virtualmachine.* APIs are @SystemApi (only present in
 * the system-stub JAR, not the public SDK). We reach them via reflection so
 * a normal Gradle build still compiles on every device. On phones without
 * pKVM the reflective lookups simply fail and the probe reports "not
 * available" — no crash, no missing-class linker error.
 *
 * Purpose: validate the manifest+`adb pm grant` path on pKVM hardware
 * (Pixel 8/9/10) before investing in a real dual-backend rewrite. Reports
 * what's present, what's granted, whether the service is reachable, and
 * (optionally) attempts to create + start a minimal VM using our existing
 * Alpine kernel/initrd in filesDir.
 *
 * Also hosts runGpuDisplaySmokeTest(): a separate, experimental spike that
 * checks whether a non-platform-signed app can reach a real GPU-accelerated
 * display surface on AVF (the ICrosvmAndroidDisplayService path Google's own
 * Terminal app uses), as opposed to Podroid's current Xvnc/VNC-only display
 * pipeline. Answers one question — reachable or denied — before any guest
 * kernel/Mesa/compositor work is worth doing.
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.IBinder
import android.view.Surface
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One-line entries in the diagnostic report; UI just joins them. */
data class AvfReport(
    val featureSupported: Boolean,
    val managePermissionGranted: Boolean,
    val customPermissionGranted: Boolean,
    val virtApexPresent: Boolean,
    val managerClassPresent: Boolean,
    val serviceReachable: Boolean,
    val customVmConfigSupported: Boolean = false,
    val smokeTestResult: String?,
    val capabilitiesRaw: Int = 0,
    val capabilitiesDecoded: String = "n/a",
    val activeBackend: String = "?",
) {
    fun pretty(): String = buildString {
        appendLine("Active backend")
        appendLine("  $activeBackend")
        appendLine()
        appendLine("Feature: virtualization_framework")
        appendLine("  supported = $featureSupported")
        appendLine()
        appendLine("Permission: MANAGE_VIRTUAL_MACHINE")
        appendLine("  granted = $managePermissionGranted")
        appendLine()
        appendLine("Permission: USE_CUSTOM_VIRTUAL_MACHINE")
        appendLine("  granted = $customPermissionGranted")
        appendLine()
        appendLine("APEX /apex/com.android.virt")
        appendLine("  present = $virtApexPresent")
        appendLine()
        appendLine("API VirtualMachineManager")
        appendLine("  class loadable = $managerClassPresent")
        appendLine()
        appendLine("Service")
        appendLine("  reachable via system service = $serviceReachable")
        appendLine()
        appendLine("Custom-VM API")
        appendLine("  builder present = $customVmConfigSupported")
        appendLine()
        appendLine("Hypervisor capabilities")
        appendLine("  raw = $capabilitiesRaw ($capabilitiesDecoded)")
        if (smokeTestResult != null) {
            appendLine()
            appendLine("Smoke test")
            appendLine(smokeTestResult.prependIndent("  "))
        }
    }
}

object AvfDiagnostics {

    private const val FEATURE = "android.software.virtualization_framework"
    private const val PERM_MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
    private const val PERM_CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    private const val CLS_MANAGER = "android.system.virtualmachine.VirtualMachineManager"
    private const val CLS_CONFIG = "android.system.virtualmachine.VirtualMachineConfig"
    private const val CLS_CUSTOM_CFG = "android.system.virtualmachine.VirtualMachineCustomImageConfig"

    /**
     * True only if this device's AVF build exposes the custom-VM builder API
     * Podroid drives (raw kernel + initrd). A vendor build that ships AVF for
     * system use but omits the custom-image config will return false, so
     * EngineHolder.pick() can fall back to QEMU at selection time instead of
     * erroring at VM start. Never throws: a missing class is a normal "no".
     */
    fun customVmConfigSupported(): Boolean = runCatching {
        val builder = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        builder.getDeclaredMethod("setKernelPath", String::class.java)
        builder.getDeclaredMethod("setInitrdPath", String::class.java)
        Class.forName("$CLS_CONFIG\$Builder")
            .getDeclaredMethod("setCustomImageConfig", Class.forName(CLS_CUSTOM_CFG))
        true
    }.getOrDefault(false)

    /**
     * Read-only probe — never blocks, never touches the system service for
     * real (just checks reachability). Safe to call from anywhere.
     */
    fun probe(context: Context): AvfReport {
        val pm = context.packageManager
        val featureSupported = pm.hasSystemFeature(FEATURE)
        val managePermissionGranted = pm.checkPermission(PERM_MANAGE, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val customPermissionGranted = pm.checkPermission(PERM_CUSTOM, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val virtApexPresent = File("/apex/com.android.virt/bin").exists()
        val managerClassPresent = runCatching { Class.forName(CLS_MANAGER) }.isSuccess
        val serviceReachable = managerClassPresent && managePermissionGranted &&
            runCatching { getVirtualizationManager(context) != null }.getOrDefault(false)

        val capabilitiesRaw = if (serviceReachable) {
            runCatching { AvfReflect.getCapabilities(AvfReflect.manager(context)) }.getOrDefault(0)
        } else 0

        return AvfReport(
            featureSupported = featureSupported,
            managePermissionGranted = managePermissionGranted,
            customPermissionGranted = customPermissionGranted,
            virtApexPresent = virtApexPresent,
            managerClassPresent = managerClassPresent,
            serviceReachable = serviceReachable,
            customVmConfigSupported = customVmConfigSupported(),
            smokeTestResult = null,
            capabilitiesRaw = capabilitiesRaw,
            capabilitiesDecoded = AvfCapabilities.decode(capabilitiesRaw),
        )
    }

    /**
     * Attempts a real minimal-VM creation using our existing alpine kernel
     * + initrd. Returns a human-readable result string. Blocks for a few
     * seconds. Call off the UI thread.
     *
     * NOTE: this only validates that *creation* + *run* are accepted by the
     * framework — it does not stream the console or wait for guest boot.
     * The VM is stopped/deleted immediately.
     */
    fun runSmokeTest(context: Context): String {
        val pre = probe(context)
        if (!pre.featureSupported)   return "skipped: feature flag not present (device does not ship AVF)"
        if (!pre.managePermissionGranted) return "skipped: MANAGE_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_MANAGE)"
        if (!pre.customPermissionGranted) return "skipped: USE_CUSTOM_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_CUSTOM)"
        if (!pre.managerClassPresent) return "FAILED: $CLS_MANAGER not on the boot classpath — system stub missing"

        val kernelSrc = File(context.filesDir, "vmlinuz-virt")
        val initrd = File(context.filesDir, "initrd.img")
        if (!kernelSrc.exists()) return "FAILED: kernel not extracted yet at ${kernelSrc.absolutePath}"
        if (!initrd.exists()) return "FAILED: initrd not extracted yet at ${initrd.absolutePath}"

        if (AvfCapabilities.choose(pre.capabilitiesRaw) is AvfCapabilities.ProtectedVmChoice.Unsupported) {
            return "not applicable on this device: the hypervisor only supports protected VMs " +
                "(caps=${pre.capabilitiesDecoded}), and Podroid's custom Linux kernel can run only as a " +
                "non-protected VM. This is expected, not a failure: Podroid automatically uses the QEMU " +
                "backend here."
        }

        return try {
            val vmm = getVirtualizationManager(context)
                ?: return "FAILED: VirtualMachineManager system service returned null"

            // crosvm needs the raw ARM64 Image, not the gzip vmlinuz — decompress
            // exactly like AvfEngine.ensureRawKernel (and reuse its cached .raw).
            // Without this, crosvm fails to load the kernel ("invalid magic
            // number") the moment it gets past arg parsing.
            val kernel = ensureRawKernel(kernelSrc)
            val customCfg = buildCustomImageConfig(kernel.absolutePath, initrd.absolutePath)
            val config = buildVirtualMachineConfig(vmm, context, customCfg)

            val name = "podroid-avf-smoke"
            val vm = invokeOrCreate(vmm, name, config)

            // Start + immediately stop. We're proving the framework accepts us,
            // not running a workload.
            runCatching {
                vm.javaClass.getMethod("run").invoke(vm)
            }.onFailure { e ->
                deleteSafely(vmm, name)
                return "FAILED at vm.run(): ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
            }

            // Give the VM 1.5s to actually start (we just want to know whether
            // the framework rejected us — usually fails synchronously).
            Thread.sleep(1500)

            runCatching { vm.javaClass.getMethod("stop").invoke(vm) }
            deleteSafely(vmm, name)

            "SUCCESS: AVF accepted our config, VM started + stopped cleanly. The dev-grant path works on this device."
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            if (cause is UnsupportedOperationException &&
                cause.message?.contains("protected", ignoreCase = true) == true) {
                // Device is protected-only but getCapabilities() reported 0, so the
                // framework rejected our non-protected VM at build/run instead. Same
                // expected outcome as the early check above, not a failure.
                "not applicable on this device: AVF rejected a non-protected VM " +
                    "(${cause.message}). Podroid's custom Linux kernel can run only as a non-protected " +
                    "VM. This is expected, not a failure: Podroid automatically uses the QEMU backend here."
            } else {
                "FAILED: ${cause.javaClass.simpleName}: ${cause.message}"
            }
        }
    }

    private fun getVirtualizationManager(context: Context): Any? {
        // Context.getSystemService(Class) — but the class is loaded reflectively
        // so we can't call the typed overload at compile time.
        val mgrCls = Class.forName(CLS_MANAGER)
        val m = Context::class.java.getMethod("getSystemService", Class::class.java)
        return m.invoke(context, mgrCls)
    }

    /**
     * Mirrors AvfEngine.ensureRawKernel: crosvm requires the raw ARM64 Image
     * (magic `ARM\x64` at 0x38), not the gzip-compressed vmlinuz. Decompress to
     * the same sibling `.raw` file so the cache is shared with the real VM path.
     * Returns the source untouched if it isn't gzip.
     */
    private fun ensureRawKernel(source: File): File {
        val magic = ByteArray(4)
        source.inputStream().use { it.read(magic) }
        if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) return source
        val raw = File(source.parentFile, "${source.name}.raw")
        if (raw.exists() && raw.lastModified() >= source.lastModified()) return raw
        java.util.zip.GZIPInputStream(source.inputStream().buffered()).use { gz ->
            raw.outputStream().buffered().use { out -> gz.copyTo(out) }
        }
        raw.setLastModified(System.currentTimeMillis())
        return raw
    }

    private fun buildCustomImageConfig(kernelPath: String, initrdPath: String): Any {
        val builderCls = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        val ctor = builderCls.getDeclaredConstructor().apply { isAccessible = true }
        val builder = ctor.newInstance()
        invokeSetter(builderCls, builder, "setName", String::class.java, "podroid-avf-smoke")
        invokeSetter(builderCls, builder, "setKernelPath", String::class.java, kernelPath)
        invokeSetter(builderCls, builder, "setInitrdPath", String::class.java, initrdPath)
        runCatching {
            invokeSetter(builderCls, builder, "setParams", String::class.java, "console=hvc0 panic=1")
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun buildVirtualMachineConfig(vmm: Any, context: Context, customCfg: Any): Any {
        val builderCls = Class.forName("$CLS_CONFIG\$Builder")
        val ctor = builderCls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val builder = ctor.newInstance(context)
        val customCfgCls = Class.forName(CLS_CUSTOM_CFG)
        invokeSetter(builderCls, builder, "setCustomImageConfig", customCfgCls, customCfg)
        when (val choice = AvfReflect.applyProtectedVm(vmm, builder)) {
            is AvfCapabilities.ProtectedVmChoice.Unsupported ->
                throw UnsupportedOperationException(choice.reason)
            else -> Unit  // NonProtected/Unknown: setter already applied (or threw, which surfaces)
        }
        runCatching {
            invokeSetter(builderCls, builder, "setMemoryBytes",
                Long::class.javaPrimitiveType!!, 256L * 1024 * 1024)
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun invokeSetter(cls: Class<*>, target: Any, name: String, argType: Class<*>, arg: Any?) {
        val m = cls.getDeclaredMethod(name, argType).apply { isAccessible = true }
        m.invoke(target, arg)
    }

    private fun invokeOrCreate(vmm: Any, name: String, config: Any): Any {
        val cfgCls = Class.forName(CLS_CONFIG)
        return runCatching {
            val m = vmm.javaClass.getDeclaredMethod("getOrCreate", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        }.getOrElse {
            val m = vmm.javaClass.getDeclaredMethod("create", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        } ?: error("getOrCreate returned null")
    }

    private fun deleteSafely(vmm: Any, name: String) {
        runCatching { vmm.javaClass.getMethod("delete", String::class.java).invoke(vmm, name) }
    }

    // ---- GPU / display probe (experimental spike) --------------------------
    //
    // Answers one question: can a non-platform-signed app with the standard
    // dev-grant (MANAGE_VIRTUAL_MACHINE + USE_CUSTOM_VIRTUAL_MACHINE) reach a
    // real, GPU-accelerated display surface on AVF — the same path Google's own
    // Terminal app uses (packages/modules/Virtualization/android/TerminalApp/,
    // same AOSP tree as the rest of this reflection) — or is it walled off to
    // privileged/system callers only.
    //
    // ServiceManager.waitForService("android.system.virtualizationservice") is
    // the SAME binder Podroid already reaches via VirtualMachineManager for
    // every normal VM operation; TerminalApp's DisplayProvider just casts it
    // through the internal AIDL interface (IVirtualizationServiceInternal)
    // instead of the public one to reach waitDisplayService(). Whatever
    // permission check exists inside virtmgr for that method is exactly what
    // this probe surfaces — SecurityException means "no", a returned service
    // means "yes", anything else means the API shape differs on this build.
    //
    // Unlike AvfReflect.setGpuConfig (backend=2d, surfaceless — used only to
    // steer crosvm binary selection on every normal launch), this attaches a
    // real virglrenderer GPU config + a DisplayConfig, because binary
    // selection isn't what's being tested here.

    private const val CLS_GPU_CFG = "$CLS_CUSTOM_CFG\$GpuConfig"
    private const val CLS_DISPLAY_CFG = "$CLS_CUSTOM_CFG\$DisplayConfig"
    private const val SVC_VIRTUALIZATION = "android.system.virtualizationservice"
    private const val CLS_SERVICE_MANAGER = "android.os.ServiceManager"
    private const val CLS_INTERNAL = "android.system.virtualizationservice_internal.IVirtualizationServiceInternal"
    private const val CLS_CROSVM_DISPLAY = "android.crosvm.ICrosvmAndroidDisplayService"

    private sealed class DisplayProbeResult {
        data class Reached(val service: Any) : DisplayProbeResult()
        data class TimedOut(val timeoutMs: Long) : DisplayProbeResult()
        data class Denied(val detail: String) : DisplayProbeResult()
        data class Unavailable(val detail: String) : DisplayProbeResult()
    }

    /**
     * Creates a throwaway custom VM with a real gpu+display config, starts it,
     * then attempts the exact waitDisplayService() -> setSurface() dance
     * TerminalApp's DisplayProvider performs. Blocks for a few seconds. Call
     * off the UI thread. The VM is stopped/deleted before returning.
     */
    fun runGpuDisplaySmokeTest(context: Context): String {
        val pre = probe(context)
        if (!pre.featureSupported) return "skipped: feature flag not present (device does not ship AVF)"
        if (!pre.managePermissionGranted) return "skipped: MANAGE_VIRTUAL_MACHINE not granted"
        if (!pre.customPermissionGranted) return "skipped: USE_CUSTOM_VIRTUAL_MACHINE not granted"
        if (!pre.managerClassPresent) return "FAILED: $CLS_MANAGER not on the boot classpath"

        val gpuCfgPresent = runCatching { Class.forName("$CLS_GPU_CFG\$Builder") }.isSuccess
        val displayCfgPresent = runCatching { Class.forName("$CLS_DISPLAY_CFG\$Builder") }.isSuccess
        if (!gpuCfgPresent || !displayCfgPresent) {
            return "not available: GpuConfig/DisplayConfig builder classes absent on this AVF revision " +
                "(gpuConfig=$gpuCfgPresent, displayConfig=$displayCfgPresent)"
        }

        val kernelSrc = File(context.filesDir, "vmlinuz-virt")
        val initrd = File(context.filesDir, "initrd.img")
        if (!kernelSrc.exists()) return "FAILED: kernel not extracted yet at ${kernelSrc.absolutePath}"
        if (!initrd.exists()) return "FAILED: initrd not extracted yet at ${initrd.absolutePath}"

        if (AvfCapabilities.choose(pre.capabilitiesRaw) is AvfCapabilities.ProtectedVmChoice.Unsupported) {
            return "not applicable on this device: hypervisor only supports protected VMs; " +
                "GPU passthrough needs a non-protected custom VM."
        }

        val name = "podroid-gpu-probe"
        var reader: ImageReader? = null
        return try {
            val vmm = getVirtualizationManager(context)
                ?: return "FAILED: VirtualMachineManager system service returned null"

            val kernel = ensureRawKernel(kernelSrc)
            val customCfg = buildGpuCustomImageConfig(kernel.absolutePath, initrd.absolutePath)
            val config = buildVirtualMachineConfig(vmm, context, customCfg)
            val vm = invokeOrCreate(vmm, name, config)

            runCatching {
                vm.javaClass.getMethod("run").invoke(vm)
            }.onFailure { e ->
                deleteSafely(vmm, name)
                return "FAILED at vm.run(): ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
            }

            // Give crosvm a moment to stand up virtio-gpu before we go looking
            // for its display service.
            Thread.sleep(1000)

            val displayResult = probeDisplayServiceWithTimeout(timeoutMs = 5000)
            val surfaceResult = (displayResult as? DisplayProbeResult.Reached)?.let {
                reader = ImageReader.newInstance(320, 480, PixelFormat.RGBA_8888, 2)
                attemptSetSurface(it.service, reader!!.surface)
            }

            runCatching { vm.javaClass.getMethod("stop").invoke(vm) }
            deleteSafely(vmm, name)

            buildString {
                appendLine("VM created + started with real gpu+display config: OK")
                append("waitDisplayService(): ")
                appendLine(
                    when (displayResult) {
                        is DisplayProbeResult.Reached -> "SUCCESS — obtained ICrosvmAndroidDisplayService"
                        is DisplayProbeResult.TimedOut -> "TIMED OUT after ${displayResult.timeoutMs}ms (no display client connected)"
                        is DisplayProbeResult.Denied -> "DENIED — ${displayResult.detail}"
                        is DisplayProbeResult.Unavailable -> "UNAVAILABLE — ${displayResult.detail}"
                    },
                )
                if (surfaceResult != null) appendLine("setSurface(): $surfaceResult")
            }
        } catch (e: Throwable) {
            runCatching { getVirtualizationManager(context)?.let { deleteSafely(it, name) } }
            val cause = e.cause ?: e
            "FAILED: ${cause.javaClass.simpleName}: ${cause.message}"
        } finally {
            runCatching { reader?.close() }
        }
    }

    private fun buildGpuCustomImageConfig(kernelPath: String, initrdPath: String): Any {
        val builderCls = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        val builder = builderCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        invokeSetter(builderCls, builder, "setName", String::class.java, "podroid-gpu-probe")
        invokeSetter(builderCls, builder, "setKernelPath", String::class.java, kernelPath)
        invokeSetter(builderCls, builder, "setInitrdPath", String::class.java, initrdPath)
        runCatching {
            invokeSetter(builderCls, builder, "setParams", String::class.java, "console=hvc0 panic=1")
        }

        val gpuCls = Class.forName(CLS_GPU_CFG)
        val gpuBuilderCls = Class.forName("$CLS_GPU_CFG\$Builder")
        val gpuBuilder = gpuBuilderCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        invokeSetter(gpuBuilderCls, gpuBuilder, "setBackend", String::class.java, "virglrenderer")
        runCatching {
            invokeSetter(gpuBuilderCls, gpuBuilder, "setContextTypes", Array<String>::class.java, arrayOf("virgl2"))
        }
        runCatching { invokeSetter(gpuBuilderCls, gpuBuilder, "setRendererUseEgl", java.lang.Boolean::class.java, true) }
        runCatching { invokeSetter(gpuBuilderCls, gpuBuilder, "setRendererUseGles", java.lang.Boolean::class.java, true) }
        runCatching { invokeSetter(gpuBuilderCls, gpuBuilder, "setRendererUseSurfaceless", java.lang.Boolean::class.java, false) }
        val gpuConfig = gpuBuilderCls.getDeclaredMethod("build").apply { isAccessible = true }.invoke(gpuBuilder)
        invokeSetter(builderCls, builder, "setGpuConfig", gpuCls, gpuConfig)

        val displayCls = Class.forName(CLS_DISPLAY_CFG)
        val displayBuilderCls = Class.forName("$CLS_DISPLAY_CFG\$Builder")
        val displayBuilder = displayBuilderCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        invokeSetter(displayBuilderCls, displayBuilder, "setWidth", Int::class.javaPrimitiveType!!, 320)
        invokeSetter(displayBuilderCls, displayBuilder, "setHeight", Int::class.javaPrimitiveType!!, 480)
        runCatching { invokeSetter(displayBuilderCls, displayBuilder, "setHorizontalDpi", Int::class.javaPrimitiveType!!, 160) }
        runCatching { invokeSetter(displayBuilderCls, displayBuilder, "setVerticalDpi", Int::class.javaPrimitiveType!!, 160) }
        runCatching { invokeSetter(displayBuilderCls, displayBuilder, "setRefreshRate", Int::class.javaPrimitiveType!!, 60) }
        val displayConfig = displayBuilderCls.getDeclaredMethod("build").apply { isAccessible = true }.invoke(displayBuilder)
        invokeSetter(builderCls, builder, "setDisplayConfig", displayCls, displayConfig)

        return builderCls.getDeclaredMethod("build").apply { isAccessible = true }.invoke(builder)
    }

    /**
     * Runs the waitForService -> asInterface -> waitDisplayService chain on a
     * daemon thread with a hard wall-clock timeout: waitForService/
     * waitDisplayService are blocking-by-design and could hang indefinitely on
     * a build where the display client never connects.
     */
    private fun probeDisplayServiceWithTimeout(timeoutMs: Long): DisplayProbeResult {
        val resultRef = AtomicReference<DisplayProbeResult>()
        val latch = CountDownLatch(1)
        Thread({
            resultRef.set(probeDisplayServiceBlocking())
            latch.countDown()
        }, "AvfGpuProbe").apply { isDaemon = true }.start()
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) return DisplayProbeResult.TimedOut(timeoutMs)
        return resultRef.get() ?: DisplayProbeResult.Unavailable("probe thread finished without a result")
    }

    private fun probeDisplayServiceBlocking(): DisplayProbeResult = try {
        val waitForService = Class.forName(CLS_SERVICE_MANAGER)
            .getDeclaredMethod("waitForService", String::class.java).apply { isAccessible = true }
        val binder = waitForService.invoke(null, SVC_VIRTUALIZATION) as? IBinder
            ?: return DisplayProbeResult.Unavailable("waitForService(\"$SVC_VIRTUALIZATION\") returned null")

        val internalCls = Class.forName(CLS_INTERNAL)
        val internal = Class.forName("$CLS_INTERNAL\$Stub")
            .getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
            .invoke(null, binder)
            ?: return DisplayProbeResult.Unavailable("IVirtualizationServiceInternal.Stub.asInterface returned null")

        val displayBinder = internalCls.getMethod("waitDisplayService").apply { isAccessible = true }
            .invoke(internal) as? IBinder
            ?: return DisplayProbeResult.Unavailable("waitDisplayService() returned null")

        val service = Class.forName("$CLS_CROSVM_DISPLAY\$Stub")
            .getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
            .invoke(null, displayBinder)
            ?: return DisplayProbeResult.Unavailable("ICrosvmAndroidDisplayService.Stub.asInterface returned null")

        DisplayProbeResult.Reached(service)
    } catch (e: InvocationTargetException) {
        val cause = e.cause ?: e
        if (cause is SecurityException) {
            DisplayProbeResult.Denied("${cause.javaClass.simpleName}: ${cause.message}")
        } else {
            DisplayProbeResult.Unavailable("${cause.javaClass.simpleName}: ${cause.message}")
        }
    } catch (e: ClassNotFoundException) {
        DisplayProbeResult.Unavailable("class not found: ${e.message} (API absent on this AVF revision)")
    } catch (e: NoSuchMethodException) {
        DisplayProbeResult.Unavailable("method not found: ${e.message} (API shape differs on this AVF revision)")
    } catch (e: Exception) {
        DisplayProbeResult.Unavailable("${e.javaClass.simpleName}: ${e.message}")
    }

    private fun attemptSetSurface(service: Any, surface: Surface): String = try {
        service.javaClass.getMethod(
            "setSurface", Surface::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, surface, false)
        "SUCCESS — crosvm accepted the surface"
    } catch (e: InvocationTargetException) {
        val cause = e.cause ?: e
        "FAILED: ${cause.javaClass.simpleName}: ${cause.message}"
    } catch (e: Exception) {
        "FAILED: ${e.javaClass.simpleName}: ${e.message}"
    }
}
