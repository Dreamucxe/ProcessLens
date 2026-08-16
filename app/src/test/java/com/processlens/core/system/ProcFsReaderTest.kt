package com.processlens.core.system

import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.ProcessState
import com.processlens.testing.assertContains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the `/proc` reader (Sections 42, 46, 47).
 *
 * The reader takes its filesystem roots as constructor arguments, which is what makes
 * this possible: a fake `/proc` tree on disk exercises the real parsing code with no
 * device and no privileged access. That matters more here than anywhere else in the
 * app, because these parsers are the ones that would otherwise turn a malformed line
 * into a plausible-looking number.
 */
class ProcFsReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var proc: File
    private lateinit var sys: File
    private lateinit var reader: ProcFsReader

    @Before
    fun setUp() {
        proc = temp.newFolder("proc")
        sys = temp.newFolder("sys")
        reader = ProcFsReader(root = proc, sysRoot = sys)
    }

    private fun write(relative: String, contents: String) {
        val file = File(proc, relative)
        file.parentFile?.mkdirs()
        file.writeText(contents)
    }

    private fun writeSys(relative: String, contents: String) {
        val file = File(sys, relative)
        file.parentFile?.mkdirs()
        file.writeText(contents)
    }

    /** A stat line with the full field count a real kernel produces. */
    private fun statLine(
        pid: Int,
        comm: String,
        state: String = "S",
        ppid: Int = 1,
        utime: Long = 500L,
        stime: Long = 250L,
        numThreads: Int = 14,
        rssPages: Long = 1_000L,
    ): String {
        // rest[] indices: 0 state, 1 ppid, 11 utime, 12 stime, 17 numThreads,
        // 19 startTime, 20 vsize, 21 rss. Padded out to a realistic length.
        val rest = MutableList(50) { "0" }
        rest[0] = state
        rest[1] = ppid.toString()
        rest[11] = utime.toString()
        rest[12] = stime.toString()
        rest[17] = numThreads.toString()
        rest[19] = "2000"
        rest[20] = "123456789"
        rest[21] = rssPages.toString()
        return "$pid ($comm) " + rest.joinToString(" ")
    }

    // -------------------------------------------------------------- /proc/stat

    @Test
    fun `system cpu times are read from proc stat`() {
        write(
            "stat",
            """
            cpu  100 200 300 400 500 600 700 800 0 0
            cpu0 10 20 30 40 50 60 70 80 0 0
            intr 12345
            """.trimIndent(),
        )

        val times = reader.readSystemCpuTimes().valueOrNull
        assertNotNull("system cpu times should be readable", times)
        requireNotNull(times)
        assertEquals(100L, times.user)
        assertEquals(200L, times.nice)
        assertEquals(300L, times.system)
        assertEquals(400L, times.idle)
        assertEquals(500L, times.iowait)
    }

    @Test
    fun `a missing proc stat is reported as unavailable rather than as zero`() {
        // No file written at all. The honest answer is "could not read": a CpuTimes
        // full of zeros would be indistinguishable from a perfectly idle machine.
        assertNull(reader.readSystemCpuTimes().valueOrNull)
    }

    @Test
    fun `per core times exclude the aggregate cpu line`() {
        write(
            "stat",
            """
            cpu  100 0 0 100 0 0 0 0 0 0
            cpu0 10 0 0 10 0 0 0 0 0 0
            cpu1 20 0 0 20 0 0 0 0 0 0
            cpu2 30 0 0 30 0 0 0 0 0 0
            """.trimIndent(),
        )

        val cores = reader.readPerCoreCpuTimes().valueOrNull
        assertNotNull(cores)
        requireNotNull(cores)
        assertEquals(3, cores.size)
        assertEquals(listOf("cpu0", "cpu1", "cpu2"), cores.map { it.name })
    }

    // ----------------------------------------------------------- CpuTimes maths

    @Test
    fun `utilisation is computed from the delta between two samples`() {
        // 100 active jiffies out of 200 total elapsed is 50%.
        val first = CpuTimes("cpu", 100, 0, 0, 100, 0, 0, 0, 0)
        val second = CpuTimes("cpu", 200, 0, 0, 200, 0, 0, 0, 0)

        val utilisation = second.utilisationSince(first)
        assertNotNull(utilisation)
        assertEquals(50f, utilisation!!, 0.01f)
    }

    @Test
    fun `utilisation is null when the counters did not advance`() {
        // The single most important assertion about CPU in this app. Two identical
        // readings mean "no information", not "0% busy" — a stalled counter and a
        // genuinely idle core are indistinguishable from the delta alone.
        val same = CpuTimes("cpu", 100, 0, 0, 100, 0, 0, 0, 0)

        assertNull(same.utilisationSince(same))
    }

    @Test
    fun `utilisation is null when the counters went backwards`() {
        val later = CpuTimes("cpu", 100, 0, 0, 100, 0, 0, 0, 0)
        val earlier = CpuTimes("cpu", 200, 0, 0, 200, 0, 0, 0, 0)

        assertNull(later.utilisationSince(earlier))
    }

    @Test
    fun `a fully busy interval reports one hundred percent`() {
        val first = CpuTimes("cpu", 0, 0, 0, 0, 0, 0, 0, 0)
        val second = CpuTimes("cpu", 100, 0, 0, 0, 0, 0, 0, 0)

        assertEquals(100f, second.utilisationSince(first)!!, 0.01f)
    }

    @Test
    fun `a fully idle interval reports zero percent`() {
        // Distinct from the "did not advance" case above: here the counters *did*
        // move, and all of the movement was idle. That is a real measurement of 0%.
        val first = CpuTimes("cpu", 0, 0, 0, 0, 0, 0, 0, 0)
        val second = CpuTimes("cpu", 0, 0, 0, 100, 0, 0, 0, 0)

        assertEquals(0f, second.utilisationSince(first)!!, 0.01f)
    }

    @Test
    fun `iowait counts as elapsed time but not as active time`() {
        val times = CpuTimes("cpu", 10, 20, 30, 40, 50, 1, 2, 3)
        assertEquals(10 + 20 + 30 + 1 + 2 + 3L, times.active)
        assertEquals(10 + 20 + 30 + 40 + 50 + 1 + 2 + 3L, times.total)
    }

    // ------------------------------------------------------------- /proc/meminfo

    @Test
    fun `meminfo values are parsed in kibibytes`() {
        write(
            "meminfo",
            """
            MemTotal:        3906252 kB
            MemFree:          204800 kB
            MemAvailable:    1048576 kB
            Buffers:           51200 kB
            Cached:           512000 kB
            SwapTotal:       2097152 kB
            SwapFree:        1048576 kB
            """.trimIndent(),
        )

        val info = reader.readMemInfo().valueOrNull
        assertNotNull(info)
        requireNotNull(info)
        assertEquals(3_906_252L, info["MemTotal"])
        assertEquals(1_048_576L, info["MemAvailable"])
        assertEquals(2_097_152L, info["SwapTotal"])
    }

    @Test
    fun `a meminfo line without a numeric value is skipped rather than guessed`() {
        write(
            "meminfo",
            """
            MemTotal:        1000 kB
            Broken:          notanumber kB
            MemFree:          500 kB
            """.trimIndent(),
        )

        val info = reader.readMemInfo().valueOrNull
        requireNotNull(info)
        assertEquals(1_000L, info["MemTotal"])
        assertEquals(500L, info["MemFree"])
        assertNull("a malformed line must not become a value", info["Broken"])
    }

    @Test
    fun `an empty meminfo is a failure rather than an empty map of zeros`() {
        write("meminfo", "")
        assertNull(reader.readMemInfo().valueOrNull)
    }

    // ------------------------------------------------------------------- pids

    @Test
    fun `only numeric directory names count as pids`() {
        listOf("1", "42", "1234", "self", "net", "irq", "notapid").forEach {
            File(proc, it).mkdirs()
        }

        val pids = reader.listVisiblePids().valueOrNull
        assertNotNull(pids)
        assertEquals(listOf(1, 42, 1234), pids)
    }

    @Test
    fun `pids are returned in ascending order`() {
        listOf("300", "12", "5000", "7").forEach { File(proc, it).mkdirs() }

        assertEquals(listOf(7, 12, 300, 5000), reader.listVisiblePids().valueOrNull)
    }

    @Test
    fun `a proc tree with no pid directories yields an empty list`() {
        // What a normal app sees under hidepid on API 29+. The reader reports the
        // empty truth; deciding that this means "the list is incomplete" is the
        // repository's job, which is why it carries isCompleteList separately.
        val pids = reader.listVisiblePids().valueOrNull
        assertNotNull("an empty /proc is still a successful read", pids)
        assertTrue(pids!!.isEmpty())
    }

    // -------------------------------------------------------------- stat parsing

    @Test
    fun `a process name containing spaces and parentheses is parsed correctly`() {
        // The classic /proc/pid/stat trap: comm is wrapped in parentheses and may
        // itself contain spaces and brackets, so splitting the line on whitespace
        // shifts every later field and silently corrupts every number after it.
        val stat = ProcFsReader.parseStatLine(statLine(1234, "my app (beta)"))

        assertNotNull("a comm with spaces and brackets must still parse", stat)
        requireNotNull(stat)
        assertEquals(1234, stat.pid)
        assertEquals("my app (beta)", stat.comm)
        assertEquals(ProcessState.SLEEPING, stat.state)
        assertEquals(1, stat.ppid)
    }

    @Test
    fun `cpu jiffies are the sum of user and system time`() {
        val stat = ProcFsReader.parseStatLine(statLine(1, "init", utime = 500, stime = 250))
        requireNotNull(stat)
        assertEquals(750L, stat.cpuJiffies)
    }

    @Test
    fun `rss is converted from pages to bytes`() {
        val stat = ProcFsReader.parseStatLine(statLine(1, "init", rssPages = 1_000L))
        requireNotNull(stat)
        assertEquals(1_000L * 4096L, stat.rssBytes)
    }

    @Test
    fun `the running state maps from its kernel character`() {
        val stat = ProcFsReader.parseStatLine(statLine(1, "busy", state = "R"))
        requireNotNull(stat)
        assertEquals(ProcessState.RUNNING, stat.state)
    }

    @Test
    fun `a zombie is reported as a zombie`() {
        val stat = ProcFsReader.parseStatLine(statLine(1, "gone", state = "Z"))
        requireNotNull(stat)
        assertEquals(ProcessState.ZOMBIE, stat.state)
    }

    @Test
    fun `the pid override wins over the pid in the line`() {
        // The elevated observer reads this text through a shell and already knows the
        // pid it asked for, so the override is what keeps the two in step.
        val stat = ProcFsReader.parseStatLine(statLine(1234, "app"), pidOverride = 99)
        requireNotNull(stat)
        assertEquals(99, stat.pid)
    }

    @Test
    fun `a truncated stat line is rejected rather than half read`() {
        // A short read must produce nothing at all. A partly-filled record would put
        // a fabricated 0 jiffies and 0 RSS in front of the user (Section 42).
        assertNull(ProcFsReader.parseStatLine("1234 (app) S 1"))
        assertNull(ProcFsReader.parseStatLine("1234 (app) S"))
    }

    @Test
    fun `an unparseable stat line is rejected`() {
        assertNull(ProcFsReader.parseStatLine(""))
        assertNull(ProcFsReader.parseStatLine("garbage with no parens"))
        assertNull(ProcFsReader.parseStatLine("notapid (app) " + "0 ".repeat(50)))
    }

    @Test
    fun `a stat file is read for a specific pid`() {
        File(proc, "900").mkdirs()
        write("900/stat", statLine(900, "com.example"))

        val stat = reader.readProcessStat(900).valueOrNull
        assertNotNull(stat)
        assertEquals("com.example", stat!!.comm)
        assertEquals(900, stat.pid)
    }

    @Test
    fun `a missing stat file is unavailable rather than an empty record`() {
        assertNull(reader.readProcessStat(4242).valueOrNull)
    }

    // ----------------------------------------------------------------- cmdline

    @Test
    fun `cmdline yields the first argv entry from the NUL separated list`() {
        File(proc, "500").mkdirs()
        // The kernel separates argv with NUL bytes and leaves a trailing one, so a
        // naive read would hand the whole vector to the UI with NULs embedded in it.
        write("500/cmdline", "com.example.app\u0000--flag\u0000")

        val cmdline = reader.readCmdline(500).valueOrNull
        assertEquals("com.example.app", cmdline)
    }

    @Test
    fun `an empty cmdline is not reported as a value`() {
        File(proc, "501").mkdirs()
        write("501/cmdline", "")

        // Kernel threads have an empty cmdline. Reporting "" as the command line
        // would render as a blank field that looks like a failed lookup instead.
        assertNull(reader.readCmdline(501).valueOrNull)
    }

    // ------------------------------------------------------------------- status

    @Test
    fun `uid is extracted from the status file`() {
        File(proc, "600").mkdirs()
        write(
            "600/status",
            """
            Name:   com.example
            State:  S (sleeping)
            Uid:    10123   10123   10123   10123
            Gid:    10123   10123   10123   10123
            Threads:        14
            VmRSS:      123456 kB
            """.trimIndent(),
        )

        val status = reader.readProcessStatus(600).valueOrNull
        assertNotNull(status)
        requireNotNull(status)
        assertEquals("14", status["Threads"])
        assertEquals(10_123, reader.uidFromStatus(status))
    }

    @Test
    fun `a status file without a uid line yields null rather than zero`() {
        File(proc, "601").mkdirs()
        write("601/status", "Name:   thing\nThreads:        2")

        val status = reader.readProcessStatus(601).valueOrNull
        requireNotNull(status)
        // uid 0 is root. Defaulting an unknown uid to 0 would label an ordinary app
        // as a root process, which is both fabricated and alarming.
        assertNull(reader.uidFromStatus(status))
    }

    @Test
    fun `a non numeric uid yields null`() {
        assertNull(reader.uidFromStatus(mapOf("Uid" to "notanumber")))
    }

    // ------------------------------------------------------------------ threads

    @Test
    fun `thread ids are listed from the task directory`() {
        listOf("700", "701", "702").forEach { File(proc, "700/task/$it").mkdirs() }

        val tids = reader.listThreadIds(700).valueOrNull
        assertNotNull(tids)
        assertEquals(listOf(700, 701, 702), tids)
    }

    @Test
    fun `a process with no task directory yields an empty thread list`() {
        File(proc, "800").mkdirs()

        val tids = reader.listThreadIds(800).valueOrNull
        assertNotNull(tids)
        assertTrue(tids!!.isEmpty())
    }

    @Test
    fun `a thread stat is read from the task subtree`() {
        File(proc, "700/task/701").mkdirs()
        write("700/task/701/stat", statLine(701, "worker"))

        val stat = reader.readThreadStat(700, 701).valueOrNull
        assertNotNull(stat)
        assertEquals("worker", stat!!.comm)
    }

    // ------------------------------------------------------------ sysfs readings

    @Test
    fun `core frequencies are read from sysfs`() {
        writeSys("devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "1800000\n")
        writeSys("devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq", "300000\n")
        writeSys("devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq", "1800000\n")
        writeSys("devices/system/cpu/cpu1/cpufreq/scaling_cur_freq", "2400000\n")

        val freqs = reader.readCoreFrequencies(2).valueOrNull
        assertNotNull(freqs)
        requireNotNull(freqs)
        assertEquals(2, freqs.size)
        assertEquals(1_800_000L, freqs[0].currentKHz)
        assertEquals(300_000L, freqs[0].minKHz)
        assertEquals(2_400_000L, freqs[1].currentKHz)
        assertTrue(freqs[0].isOnline)
        assertTrue(freqs[1].isOnline)
    }

    @Test
    fun `a parked core is marked offline rather than reported as zero hertz`() {
        writeSys("devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "1800000\n")
        // cpu1 deliberately absent: SoCs park cores and remove the cpufreq node.

        val freqs = reader.readCoreFrequencies(2).valueOrNull
        requireNotNull(freqs)
        assertEquals(2, freqs.size)
        assertTrue(freqs[0].isOnline)
        // isOnline is the flag the UI reads. Without it, 0 kHz would render as a real
        // frequency of zero rather than as "this core was not running".
        assertFalse("a parked core must be flagged offline", freqs[1].isOnline)
    }

    @Test
    fun `absent cpufreq nodes report unavailable rather than a list of zeros`() {
        val freqs = reader.readCoreFrequencies(4)
        assertNull(
            "no readable cpufreq node at all must not become four zeroed cores",
            freqs.valueOrNull,
        )
    }

    @Test
    fun `a cpu thermal zone is read and converted to deci celsius`() {
        writeSys("class/thermal/thermal_zone0/type", "cpu-therm\n")
        writeSys("class/thermal/thermal_zone0/temp", "48000\n")

        // 48000 milli-degrees is 48.0 C, which is 480 deci-degrees.
        assertEquals(480, reader.readCpuTemperature().valueOrNull)
    }

    @Test
    fun `a non cpu thermal zone is ignored`() {
        writeSys("class/thermal/thermal_zone0/type", "battery\n")
        writeSys("class/thermal/thermal_zone0/temp", "31000\n")

        // The battery zone is a real reading of the wrong thing. Reporting it as CPU
        // temperature would be a fabricated relationship (Section 42).
        assertNull(reader.readCpuTemperature().valueOrNull)
    }

    @Test
    fun `an implausible thermal reading is rejected`() {
        writeSys("class/thermal/thermal_zone0/type", "cpu\n")
        writeSys("class/thermal/thermal_zone0/temp", "999999999\n")

        assertNull(reader.readCpuTemperature().valueOrNull)
    }

    @Test
    fun `absent thermal zones report unavailable rather than a plausible temperature`() {
        // Many devices expose no zones to an app. Inventing a comfortable 30 degrees
        // here is exactly the kind of fabrication Section 42 forbids.
        assertNull(reader.readCpuTemperature().valueOrNull)
    }

    // ------------------------------------------------------------------ loadavg

    @Test
    fun `load average is parsed from its three leading figures`() {
        write("loadavg", "0.52 1.24 2.06 2/1234 5678\n")

        val load = reader.readLoadAverage().valueOrNull
        assertNotNull(load)
        requireNotNull(load)
        assertEquals(0.52f, load.oneMinute, 0.001f)
        assertEquals(1.24f, load.fiveMinute, 0.001f)
        assertEquals(2.06f, load.fifteenMinute, 0.001f)
    }

    @Test
    fun `an unparseable loadavg is not reported as no load`() {
        write("loadavg", "not numbers here\n")
        assertNull(reader.readLoadAverage().valueOrNull)
    }

    // -------------------------------------------------------------------- jiffies

    @Test
    fun `jiffies convert to milliseconds at the assumed clock tick`() {
        // USER_HZ is 100 on every Android kernel in practice, and the reader documents
        // that assumption because it cannot call sysconf without native code.
        assertEquals(1_000L, reader.jiffiesToMillis(100L))
        assertEquals(0L, reader.jiffiesToMillis(0L))
        assertEquals(10L, reader.jiffiesToMillis(1L))
    }

    // ------------------------------------------------------------------ provenance

    @Test
    fun `a successful read is attributed to the proc filesystem`() {
        write("stat", "cpu 1 2 3 4 5 6 7 8 0 0")

        // Provenance is part of the contract: every figure the UI shows can name
        // where it came from, so no reading can appear without a source.
        assertContains(reader.readSystemCpuTimes().toString(), "PROC_FS")
    }
}
