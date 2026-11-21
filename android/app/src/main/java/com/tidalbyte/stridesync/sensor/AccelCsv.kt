package com.tidalbyte.stridesync.sensor

/**
 * The replay/recording format: a header `t_ns,ax,ay,az`, then one sample per line. Times are
 * elapsedRealtime nanos as recorded; replay rebases them. Pure Kotlin, shared by ReplaySource, the
 * recorder, and JVM tests.
 */
object AccelCsv {
    const val HEADER = "t_ns,ax,ay,az"

    fun format(s: AccelSample): String = "${s.tNanos},${s.x},${s.y},${s.z}"

    /** Parses lines, skipping the header, blank lines, `#` comments and malformed rows. */
    fun parse(lines: Sequence<String>): List<AccelSample> {
        val out = ArrayList<AccelSample>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("t_ns")) continue
            val p = line.split(',')
            if (p.size < 4) continue
            val t = p[0].trim().toLongOrNull() ?: continue
            val x = p[1].trim().toFloatOrNull() ?: continue
            val y = p[2].trim().toFloatOrNull() ?: continue
            val z = p[3].trim().toFloatOrNull() ?: continue
            out += AccelSample(t, x, y, z)
        }
        return out
    }
}
