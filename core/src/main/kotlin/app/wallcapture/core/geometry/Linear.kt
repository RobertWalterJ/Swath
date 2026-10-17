package app.wallcapture.core.geometry

import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val n = norm()
        require(n > 1e-12) { "cannot normalise a zero vector" }
        return this * (1.0 / n)
    }

    fun toList() = listOf(x, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun of(l: List<Double>) = Vec3(l[0], l[1], l[2])
    }
}

/** Row-major 3x3 matrix. */
class Mat3(val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    operator fun get(r: Int, c: Int) = m[r * 3 + c]

    operator fun times(o: Mat3): Mat3 {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += this[r, k] * o[k, c]
            out[r * 3 + c] = s
        }
        return Mat3(out)
    }

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    fun inverse(): Mat3 {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        require(kotlin.math.abs(det) > 1e-18) { "singular matrix" }
        val id = 1.0 / det
        return Mat3(
            doubleArrayOf(
                (a[4] * a[8] - a[5] * a[7]) * id, (a[2] * a[7] - a[1] * a[8]) * id, (a[1] * a[5] - a[2] * a[4]) * id,
                (a[5] * a[6] - a[3] * a[8]) * id, (a[0] * a[8] - a[2] * a[6]) * id, (a[2] * a[3] - a[0] * a[5]) * id,
                (a[3] * a[7] - a[4] * a[6]) * id, (a[1] * a[6] - a[0] * a[7]) * id, (a[0] * a[4] - a[1] * a[3]) * id,
            ),
        )
    }

    companion object {
        fun identity() = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun fromColumns(a: Vec3, b: Vec3, c: Vec3) =
            Mat3(doubleArrayOf(a.x, b.x, c.x, a.y, b.y, c.y, a.z, b.z, c.z))

        fun rotX(rad: Double): Mat3 {
            val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad)
            return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        }

        fun rotY(rad: Double): Mat3 {
            val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad)
            return Mat3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
        }

        fun rotZ(rad: Double): Mat3 {
            val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}

/** Row-major 4x4 rigid transform. */
class Mat4(val m: DoubleArray) {
    init {
        require(m.size == 16)
    }

    fun rotation() = Mat3(doubleArrayOf(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]))
    fun translation() = Vec3(m[3], m[7], m[11])

    fun inverseRigid(): Mat4 {
        val rt = rotation().transpose()
        val t = rt * translation() * -1.0
        return fromRt(rt, t)
    }

    fun transformPoint(p: Vec3): Vec3 = rotation() * p + translation()
    fun transformDir(d: Vec3): Vec3 = rotation() * d

    fun toList(): List<Double> = m.toList()

    companion object {
        fun identity() = fromRt(Mat3.identity(), Vec3.ZERO)
        fun fromRt(r: Mat3, t: Vec3) = Mat4(
            doubleArrayOf(
                r[0, 0], r[0, 1], r[0, 2], t.x,
                r[1, 0], r[1, 1], r[1, 2], t.y,
                r[2, 0], r[2, 1], r[2, 2], t.z,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        fun of(l: List<Double>) = Mat4(l.toDoubleArray())
    }
}
