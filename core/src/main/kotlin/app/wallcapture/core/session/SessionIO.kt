package app.wallcapture.core.session

import app.wallcapture.core.export.Json
import app.wallcapture.core.export.asDouble
import app.wallcapture.core.export.asDoubles
import app.wallcapture.core.export.asMap
import app.wallcapture.core.geometry.Intrinsics
import app.wallcapture.core.geometry.Mat4
import app.wallcapture.core.geometry.Vec3
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.ImageCodec
import app.wallcapture.core.mosaic.ImageSource
import app.wallcapture.core.mosaic.StillInput
import java.io.File

/** Pose and quality data stored next to each JPEG still. */
data class StillSidecar(
    val id: String,
    val timestampMs: Long,
    val intrinsics: Intrinsics,
    val poseRaw: Mat4,
    val sharpness: Double,
    val angleDeg: Double,
    val distanceM: Double,
    val device: String = "",
)

data class RecordedSession(
    val dir: File,
    val frame: WallFrame,
    val title: String,
    val device: String,
    val stills: List<StillInput>,
)

/**
 * Session folder format from the spec:
 *   stills/<id>.jpg + <id>.json, mosaic/, tiles/, notes/, session.json
 */
object SessionIO {
    fun writeFrame(f: WallFrame) = mapOf(
        "origin" to f.origin.toList(), "x" to f.xAxis.toList(), "y" to f.yAxis.toList(), "normal" to f.normal.toList(),
    )

    fun readFrame(m: Map<String, Any?>) = WallFrame(
        Vec3.of(m.getValue("origin").asDoubles()), Vec3.of(m.getValue("x").asDoubles()),
        Vec3.of(m.getValue("y").asDoubles()), Vec3.of(m.getValue("normal").asDoubles()),
    )

    fun writeSessionHeader(dir: File, title: String, frame: WallFrame, device: String) {
        dir.mkdirs()
        File(dir, "session.json").writeText(
            Json.write(mapOf("title" to title, "device" to device, "wallFrame" to writeFrame(frame), "format" to 1)),
            Charsets.UTF_8,
        )
    }

    fun writeStill(dir: File, sidecar: StillSidecar, jpeg: ByteArray) {
        val stills = File(dir, "stills").apply { mkdirs() }
        File(stills, "${sidecar.id}.jpg").writeBytes(jpeg)
        val k = sidecar.intrinsics
        File(stills, "${sidecar.id}.json").writeText(
            Json.write(
                mapOf(
                    "id" to sidecar.id, "timestampMs" to sidecar.timestampMs,
                    "intrinsics" to mapOf(
                        "fx" to k.fx, "fy" to k.fy, "cx" to k.cx, "cy" to k.cy, "width" to k.width, "height" to k.height,
                    ),
                    "pose" to sidecar.poseRaw.toList(),
                    "sharpness" to sidecar.sharpness, "angleDeg" to sidecar.angleDeg,
                    "distanceM" to sidecar.distanceM, "device" to sidecar.device,
                ),
            ),
            Charsets.UTF_8,
        )
    }

    fun read(dir: File, codec: ImageCodec): RecordedSession {
        val head = Json.parse(File(dir, "session.json").readText(Charsets.UTF_8)).asMap()
        val frame = readFrame(head.getValue("wallFrame").asMap())
        val stills = (File(dir, "stills").listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }
            .map { f ->
                val m = Json.parse(f.readText(Charsets.UTF_8)).asMap()
                val k = m.getValue("intrinsics").asMap()
                val intr = Intrinsics(
                    k.getValue("fx").asDouble(), k.getValue("fy").asDouble(),
                    k.getValue("cx").asDouble(), k.getValue("cy").asDouble(),
                    k.getValue("width").asDouble().toInt(), k.getValue("height").asDouble().toInt(),
                )
                val jpg = File(f.parentFile, f.nameWithoutExtension + ".jpg")
                StillInput(
                    id = m.getValue("id") as String,
                    intrinsics = intr,
                    pose = Mat4.of(m.getValue("pose").asDoubles()),
                    sharpness = m.getValue("sharpness").asDouble(),
                    source = ImageSource { codec.decode(jpg.readBytes()) },
                )
            }
        return RecordedSession(dir, frame, head["title"] as? String ?: dir.name, head["device"] as? String ?: "", stills)
    }
}
