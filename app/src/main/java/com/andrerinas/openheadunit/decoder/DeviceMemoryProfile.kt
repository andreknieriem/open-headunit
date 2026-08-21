package com.andrerinas.openheadunit.decoder

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * How much room this device has for the video pipeline to spend.
 *
 * The pipeline's buffers were sized for a unit that has memory to spare, and the same numbers land
 * very differently on one that does not. A 1 GB MediaTek head unit was measured running a 12-20 MB
 * Java heap while the app asked for a 2 MB `KEY_MAX_INPUT_SIZE`, which the component answered with
 * eight buffers of that size - 16 MB of graphics memory for input alone - and while the collector
 * ran every 5-10 seconds freeing 84-208 large arrays a cycle, once pausing for 1.4 s. The decoder
 * itself was keeping up throughout (`dropped=0`), so what hurt was the size of the machinery rather
 * than its speed.
 *
 * The classification is deliberately coarse and total-RAM-first. The allocations that matter most
 * are the codec's own input and output buffers, which come from graphics memory rather than the Java
 * heap, so the heap limit alone would miss them; and `isLowRamDevice` cannot be relied on because
 * these units set it as often as they do not.
 *
 * Only [classify] holds the rule, so it can be tested without a device.
 */
enum class DeviceMemoryProfile {
    /** Roughly 1 GB units and the like: buffers should be sized down. */
    CONSTRAINED,

    /** The middle ground. Present behaviour. */
    NORMAL,

    /** Modern tablets and units with memory to spare. */
    AMPLE;

    companion object {

        /** At or below this much total RAM, treat the device as constrained. */
        const val CONSTRAINED_TOTAL_RAM_MB = 1536L

        /** At or below this heap ceiling, treat the device as constrained whatever its total RAM. */
        const val CONSTRAINED_HEAP_LIMIT_MB = 96L

        /** Both of these are needed before extra buffering is considered free. */
        const val AMPLE_TOTAL_RAM_MB = 3584L
        const val AMPLE_HEAP_LIMIT_MB = 192L

        private const val BYTES_PER_MB = 1024L * 1024L

        /**
         * Classifies a device from three numbers.
         *
         * [totalRamMb] is the whole device's physical memory, [heapLimitMb] the app's Java heap
         * ceiling, and [systemLowRamFlag] whatever `ActivityManager.isLowRamDevice()` reports. The
         * flag can only push the verdict down, never up: a unit that admits to being low-RAM is
         * believed, one that does not is still judged on its numbers.
         *
         * A non-positive input is treated as unknown and ignored rather than as constrained, so a
         * platform that fails to report something does not silently shrink the pipeline.
         */
        fun classify(totalRamMb: Long, heapLimitMb: Long, systemLowRamFlag: Boolean): DeviceMemoryProfile {
            if (systemLowRamFlag) return CONSTRAINED
            if (totalRamMb in 1..CONSTRAINED_TOTAL_RAM_MB) return CONSTRAINED
            if (heapLimitMb in 1..CONSTRAINED_HEAP_LIMIT_MB) return CONSTRAINED
            if (totalRamMb >= AMPLE_TOTAL_RAM_MB && heapLimitMb >= AMPLE_HEAP_LIMIT_MB) return AMPLE
            return NORMAL
        }

        /**
         * Reads the device's numbers and classifies them, keeping every input in the result so a bug
         * report carries what the verdict rested on and not only the verdict.
         */
        fun read(context: Context): DeviceMemoryReading {
            val totalRamMb = readTotalRamMb(context)
            val heapLimitMb = Runtime.getRuntime().maxMemory() / BYTES_PER_MB
            val memoryClassMb = runCatching { activityManager(context).memoryClass.toLong() }.getOrDefault(0L)
            val lowRam = runCatching {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && activityManager(context).isLowRamDevice
            }.getOrDefault(false)

            return DeviceMemoryReading(
                profile = classify(totalRamMb, heapLimitMb, lowRam),
                totalRamMb = totalRamMb,
                heapLimitMb = heapLimitMb,
                memoryClassMb = memoryClassMb,
                systemLowRamFlag = lowRam,
            )
        }

        /**
         * [read], with [override] replacing the verdict when it is set.
         *
         * The override exists so the rig can run the constrained path without a 1 GB device. The
         * measured numbers are kept in the reading either way, and the reading says it was forced, so
         * a log from a forced run cannot be mistaken for a log from the hardware it imitates.
         */
        fun readWithOverride(context: Context, override: DeviceMemoryProfile?): DeviceMemoryReading {
            val measured = read(context)
            return if (override == null) measured else measured.copy(profile = override, forced = true)
        }

        /** Parses a stored profile name, tolerating anything unrecognised. */
        fun fromName(name: String?): DeviceMemoryProfile? =
            name?.let { stored -> entries.firstOrNull { it.name == stored } }

        private fun activityManager(context: Context): ActivityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        private fun readTotalRamMb(context: Context): Long = runCatching {
            val info = ActivityManager.MemoryInfo()
            activityManager(context).getMemoryInfo(info)
            info.totalMem / BYTES_PER_MB
        }.getOrDefault(0L)
    }
}

/** A [DeviceMemoryProfile] plus the numbers behind it, for logging. */
data class DeviceMemoryReading(
    val profile: DeviceMemoryProfile,
    val totalRamMb: Long,
    val heapLimitMb: Long,
    val memoryClassMb: Long,
    val systemLowRamFlag: Boolean,
    /** True when [profile] was overridden for testing rather than measured. */
    val forced: Boolean = false,
) {
    override fun toString(): String =
        "$profile${if (forced) " (FORCED)" else ""} (totalRam=${totalRamMb}MB heapLimit=${heapLimitMb}MB " +
            "memoryClass=${memoryClassMb}MB lowRamFlag=$systemLowRamFlag)"
}
