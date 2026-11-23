package com.example.powerlocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/**
 * The on-device NSFW models (OwenElliott/image-safety-classifier-*, MIT):
 * SwiftFormer image classifiers, 3 classes [NSFL, NSFW, SFW], in four
 * sizes — XS (3.5M, 97.76%), S (6.1M, 97.99%), M (12.1M, 98.06%), L
 * (28.5M, 98.20%). Plus two different models for comparison: NudeNet
 * 320n (a detector that finds exposed body parts) and a MobileNetV4 NSFW
 * classifier (5 classes). The user picks one in the Image Guard card to
 * compare which scores best on the same content; each file is downloaded
 * once and stored under filesDir — models are never bundled in the APK,
 * and no image ever leaves the phone: every frame is classified on the
 * device and then discarded.
 */
/** Result of a model download: [ok], plus a short English [reason] on failure. */
data class ModelDownloadResult(val ok: Boolean, val reason: String = "")

object ImageGuardModel {
    /** How the frame's pixels are fed to the model. */
    enum class Preprocess {
        /** 0-255 floats, normalisation baked into the graph (OwenElliott). */
        RAW_255,
        /** 0-1 floats, normalisation baked in as Sub/Div (MobileNetV4). */
        UNIT_01,
        /** Letterboxed (padded square) 0-1 floats (NudeNet). */
        LETTERBOX_01,
        /** BGR order, 0-255, dataset mean [104, 117, 123] subtracted
         * (Yahoo OpenNSFW, Caffe ResNet style). Always goes with an NHWC
         * tensor — see [Info.nhwc]. */
        BGR_MEAN
    }

    /** How the model's output becomes one 0-1 bad score. */
    enum class ScoreKind {
        /** Classifier: the NSFW class probability (OwenElliott index 1). */
        CLASS_INDEX,
        /** Classifier: softmax, then sum the unsafe classes (MobileNetV4:
         * hentai + porn + sexy). */
        SUM_CLASSES,
        /** Detector: the highest unsafe-part confidence (NudeNet). */
        DETECTION
    }

    /** One model of the switcher. All OwenElliott sizes share the same ONNX
     * use (224x224, 0-255, normalisation + softmax baked in, 3 classes
     * [NSFL, NSFW, SFW]); NudeNet and MobileNetV4 differ — input size,
     * tensor name, preprocessing and score mapping are per-model here, so
     * the scoring code never hard-codes one model's shape. Byte sizes are
     * the exact file sizes, verified 2026-11-22; all files are IR 10 or
     * older (ORT 1.20.0 understands IR 10). */
    data class Info(
        val id: String,
        val url: String,
        val bytes: Long,
        /** e.g. "~13 MB" — shown in the picker and the download dialog. */
        val sizeLabel: String,
        /** e.g. "97.76%" — the published accuracy ("—" when unknown). */
        val accuracy: String,
        /** Input edge in pixels (224 or 320). */
        val inputSize: Int,
        /** ONNX input tensor name. */
        val inputName: String,
        /** True when the model takes [C, H, W] with no batch dim
         * (MobileNetV4's export quirk: its first dim is the channel). */
        val noBatch: Boolean,
        /** True when the model takes an NHWC tensor [1, H, W, 3] instead
         * of NCHW (Yahoo OpenNSFW, a tf2onnx conversion). The pixels are
         * then filled per-pixel (HWC order) instead of per-channel. */
        val nhwc: Boolean,
        val preprocess: Preprocess,
        val scoreKind: ScoreKind
    )

    val MODELS = listOf(
        Info(
            "XS",
            "https://huggingface.co/OwenElliott/image-safety-classifier-xs/resolve/main/onnx/image-safety-classifier-xs.onnx",
            13_137_569L, "~13 MB", "97.76%",
            224, "image", false, false, Preprocess.RAW_255, ScoreKind.CLASS_INDEX
        ),
        Info(
            "S",
            "https://huggingface.co/OwenElliott/image-safety-classifier-s/resolve/main/onnx/image-safety-classifier-s.onnx",
            23_701_765L, "~24 MB", "97.99%",
            224, "image", false, false, Preprocess.RAW_255, ScoreKind.CLASS_INDEX
        ),
        Info(
            "M",
            "https://huggingface.co/OwenElliott/image-safety-classifier-m/resolve/main/onnx/image-safety-classifier-m.onnx",
            46_308_944L, "~46 MB", "98.06%",
            224, "image", false, false, Preprocess.RAW_255, ScoreKind.CLASS_INDEX
        ),
        Info(
            "L",
            "https://huggingface.co/OwenElliott/image-safety-classifier-l/resolve/main/onnx/image-safety-classifier-l.onnx",
            111_121_598L, "~106 MB", "98.20%",
            224, "image", false, false, Preprocess.RAW_255, ScoreKind.CLASS_INDEX
        ),
        Info(
            "NUDE",
            "https://raw.githubusercontent.com/notAI-tech/NudeNet/v3/nudenet/320n.onnx",
            12_150_158L, "~12 MB", "—",
            320, "images", false, false, Preprocess.LETTERBOX_01, ScoreKind.DETECTION
        ),
        Info(
            "MOB",
            "https://huggingface.co/taufiqdp/mobilenetv4_conv_small.e2400_r224_in1k_nsfw_classifier/resolve/main/mobilenetv4_conv_small.e2400_r224_in1k_nsfw_classifier.onnx",
            9_974_311L, "~10 MB", "—",
            224, "input", true, false, Preprocess.UNIT_01, ScoreKind.SUM_CLASSES
        ),
        Info(
            "YAHOO",
            "https://huggingface.co/bluefoxcreation/open-nsfw/resolve/main/open-nsfw.onnx",
            23_590_724L, "~24 MB", "—",
            224, "input:0", false, true, Preprocess.BGR_MEAN, ScoreKind.CLASS_INDEX
        )
    )

    /** Unknown ids fall back to L (the original, most accurate model). */
    fun info(id: String): Info = MODELS.find { it.id == id } ?: MODELS.last()

    fun dir(ctx: Context): File = File(ctx.filesDir, "imageguard")

    /** The file name carries the model size, so models never overwrite each
     * other and an older file is never mistaken for another model. */
    fun file(ctx: Context, modelId: String): File =
        File(dir(ctx), "model-" + modelId.lowercase() + ".onnx")

    fun file(ctx: Context): File = file(ctx, Extra.igModel(ctx))

    /** True only when the file is present AND complete (exact size). */
    fun ready(ctx: Context, modelId: String): Boolean = try {
        val f = file(ctx, modelId)
        f.exists() && f.length() == info(modelId).bytes
    } catch (_: Exception) {
        false
    }

    /** True when the currently selected model is ready. */
    fun ready(ctx: Context): Boolean = ready(ctx, Extra.igModel(ctx))

    /** True when the active network is Wi-Fi. New model downloads are
     * Wi-Fi-only: the user lost ~3 GB of mobile data to repeated model
     * downloads before, so a new model must never download on mobile data. */
    fun isWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads the model on the calling thread (call off the main thread).
     * [onProgress] gets 0..100. Downloads to a .tmp file and renames only
     * when complete, so a partial download can never look like a model.
     * Tries twice: a dropped mobile connection is common on a large
     * download. A 4xx response is not retried — it means the URL is wrong.
     * The result carries a short English reason on failure, so the user
     * sees WHY it failed instead of only "try again".
     */
    fun download(ctx: Context, modelId: String, onProgress: (Int) -> Unit): ModelDownloadResult {
        var last: ModelDownloadResult = ModelDownloadResult(false, "error")
        repeat(2) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(2000)
                } catch (_: Exception) {
                }
                onProgress(0)
            }
            last = downloadOnce(ctx, modelId, onProgress)
            if (last.ok) return last
            if (last.reason.startsWith("HTTP 4")) return last
        }
        return last
    }

    /** Downloads the currently selected model. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): ModelDownloadResult =
        download(ctx, Extra.igModel(ctx), onProgress)

    /** One download attempt. Resumable: a partial .tmp from an interrupted
     * download is continued with an HTTP Range request instead of starting
     * over, so a dropped connection does not waste mobile data. The
     * finished file must match the expected size exactly, or it is
     * reported incomplete (and retried, resuming again). */
    private fun downloadOnce(ctx: Context, modelId: String, onProgress: (Int) -> Unit): ModelDownloadResult {
        val meta = info(modelId)
        return try {
            val d = dir(ctx)
            if (!d.exists()) d.mkdirs()
            // drop the old small model if it is still on the phone
            try {
                val old = File(d, "model.onnx")
                if (old.exists()) old.delete()
            } catch (_: Exception) {
            }
            // a file that failed to load is corrupt, not short: delete it
            // (and any partial download) so this attempt starts clean.
            // Only this model's files — other models are never touched.
            if (Extra.igModelError(ctx)) {
                try {
                    file(ctx, modelId).delete()
                } catch (_: Exception) {
                }
                try {
                    File(d, "model-" + modelId.lowercase() + ".onnx.tmp").delete()
                } catch (_: Exception) {
                }
            }
            val tmp = File(d, "model-" + modelId.lowercase() + ".onnx.tmp")
            val existing = if (tmp.exists()) tmp.length() else 0L
            val conn = URL(meta.url).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            conn.connect()
            val code = conn.responseCode
            val resume = code == 206 && existing > 0
            if (code !in 200..299) {
                conn.disconnect()
                return ModelDownloadResult(false, "HTTP $code")
            }
            if (!resume && tmp.exists()) tmp.delete()
            // total size: Content-Range on a resumed download, else
            // Content-Length; fall back to the known file size
            val total = when {
                code == 206 -> totalFromContentRange(conn.getHeaderField("Content-Range"))
                    .takeIf { it > 0 } ?: (existing + conn.contentLengthLong)
                conn.contentLengthLong > 0 -> conn.contentLengthLong
                else -> meta.bytes
            }
            var done = if (resume) existing else 0L
            if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp, resume).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            // the file must be complete, not just big: a silently
            // truncated download loads as a corrupt model otherwise
            if (tmp.length() != meta.bytes) {
                return ModelDownloadResult(false, "incomplete")
            }
            if (!tmp.renameTo(file(ctx, modelId))) {
                tmp.delete()
                return ModelDownloadResult(false, "save failed")
            }
            Store.settings(ctx).edit().putBoolean("ig_model_err", false).apply()
            ModelDownloadResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "model download failed", e)
            // keep the partial .tmp: the next attempt resumes it instead
            // of downloading from zero again
            ModelDownloadResult(false, reasonFor(e))
        }
    }

    /** Total size from a "bytes <start>-<end>/<total>" Content-Range, or -1. */
    private fun totalFromContentRange(header: String?): Long {
        if (header == null) return -1
        val slash = header.lastIndexOf('/')
        if (slash < 0) return -1
        return header.substring(slash + 1).trim().toLongOrNull() ?: -1
    }

    /** Short plain-English reason for a download exception. */
    private fun reasonFor(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException -> "no internet"
        is SocketTimeoutException -> "timeout"
        is IOException -> "network error"
        else -> "error"
    }
}

/**
 * ONNX Runtime wrapper for one image-safety model. The per-model details
 * (input size, tensor name, preprocessing, score mapping) come from
 * [ImageGuardModel.Info], so the same wrapper serves classifiers and the
 * NudeNet detector:
 * - OwenElliott: input "image" [1, 3, 224, 224], 0-255 (normalisation and
 *   softmax baked in); output float[1][3] in class order [NSFL, NSFW, SFW]
 *   — index 1 is the NSFW score.
 * - MobileNetV4: input "input" [3, 224, 224] with NO batch dim (an export
 *   quirk — the first dim is the channel), 0-1 (normalisation baked in as
 *   Sub/Div); output 5 logits [drawings, hentai, neutral, porn, sexy] —
 *   softmax, then hentai + porn + sexy is the bad score.
 * - NudeNet 320n: input "images" [1, 3, 320, 320], letterboxed 0-1;
 *   output [1, 22, N] detections — the score is the highest confidence
 *   among detections of an unsafe (exposed) body part.
 * - Yahoo OpenNSFW: input "input:0" [1, 224, 224, 3] NHWC, BGR order,
 *   0-255 minus the dataset mean [104, 117, 123] (Caffe ResNet style);
 *   output float[1][2] probabilities [SFW, NSFW] — index 1 is the NSFW
 *   score. The frame is resized straight to 224 (no centre crop): the
 *   official flow crops the middle, but the guard must see the whole
 *   screen.
 */
private class NsfwModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val meta: ImageGuardModel.Info
) {
    /** Result of one scoring: the NSFW probability (0..1), plus an
     * optional detail for the log (NudeNet: the top detected class). */
    data class Scored(val score: Float, val detail: String? = null)

    companion object {
        /** NudeNet 320n's 18 detection classes, in output order. */
        private val NUDENET_LABELS = arrayOf(
            "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
            "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED",
            "ARMPITS_EXPOSED", "FACE_MALE", "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED", "ANUS_COVERED",
            "FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED"
        )

        /** NudeNet classes that count as unsafe for the score: exposed
         * buttocks, breast, genitalia and anus. A bare male chest, feet,
         * belly or armpits do not count — those appear in ordinary photos. */
        private val UNSAFE_NUDENET_CLASSES = intArrayOf(2, 3, 4, 6, 14)

        /**
         * Loads the model. Returns the model plus an empty reason on
         * success, or null plus a short reason on failure: "file missing",
         * "wrong size: N bytes", or the ORT error. The reason goes into
         * the diagnostic log so a load failure can be traced instead of
         * guessed.
         */
        fun load(ctx: Context, modelId: String): Pair<NsfwModel?, String> {
            val meta = ImageGuardModel.info(modelId)
            val file = ImageGuardModel.file(ctx, modelId)
            if (!file.exists()) return null to "file missing"
            if (file.length() != meta.bytes) {
                return null to "wrong size: ${file.length()} bytes"
            }
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                opts.setIntraOpNumThreads(4)
                val session = env.createSession(file.absolutePath, opts)
                try {
                    opts.close()
                } catch (_: Exception) {
                }
                NsfwModel(env, session, meta) to ""
            } catch (e: Exception) {
                Log.e(TAG, "NsfwModel load failed", e)
                null to "ORT error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** NSFW probability (0..1), or null if inference failed. [bmp] must be
     * the model's input size (see [ImageGuardModel.Info.inputSize]),
     * prepared by the caller (letterboxed for NudeNet). */
    fun nsfwScore(bmp: Bitmap): Scored? {
        val size = meta.inputSize
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)
        val buf = FloatBuffer.allocate(3 * size * size)
        when (meta.preprocess) {
            ImageGuardModel.Preprocess.RAW_255 -> {
                // CHW order: all reds, then greens, then blues, as 0-255
                // floats (normalisation is baked into the graph)
                for (p in pixels) buf.put(((p shr 16) and 0xFF).toFloat())
                for (p in pixels) buf.put(((p shr 8) and 0xFF).toFloat())
                for (p in pixels) buf.put((p and 0xFF).toFloat())
            }
            ImageGuardModel.Preprocess.BGR_MEAN -> {
                // NHWC order: per pixel B, G, R, 0-255 minus the dataset
                // mean [104, 117, 123] (Yahoo OpenNSFW, Caffe ResNet style)
                for (p in pixels) {
                    buf.put((p and 0xFF).toFloat() - 104f)
                    buf.put(((p shr 8) and 0xFF).toFloat() - 117f)
                    buf.put(((p shr 16) and 0xFF).toFloat() - 123f)
                }
            }
            else -> {
                // CHW, 0-1 floats (MobileNet: normalisation baked in as
                // Sub/Div; NudeNet: 1/255 scaling, like its Python library)
                for (p in pixels) buf.put(((p shr 16) and 0xFF) / 255f)
                for (p in pixels) buf.put(((p shr 8) and 0xFF) / 255f)
                for (p in pixels) buf.put((p and 0xFF) / 255f)
            }
        }
        buf.rewind()
        return try {
            val shape = when {
                meta.nhwc -> longArrayOf(1, size.toLong(), size.toLong(), 3)
                meta.noBatch -> longArrayOf(3, size.toLong(), size.toLong())
                else -> longArrayOf(1, 3, size.toLong(), size.toLong())
            }
            OnnxTensor.createTensor(env, buf, shape).use { input ->
                session.run(mapOf(meta.inputName to input)).use { result ->
                    when (meta.scoreKind) {
                        ImageGuardModel.ScoreKind.CLASS_INDEX -> {
                            val out = result[0].value as? Array<*>
                            val row = out?.get(0) as? FloatArray
                            row?.getOrNull(1)?.let { Scored(it) }
                        }
                        ImageGuardModel.ScoreKind.SUM_CLASSES -> {
                            // 5 logits [drawings, hentai, neutral, porn,
                            // sexy]: softmax, then sum the unsafe classes
                            val out = result[0].value as? Array<*>
                            val row = out?.get(0) as? FloatArray
                            row?.let { Scored(softmaxSum(it, intArrayOf(1, 3, 4))) }
                        }
                        ImageGuardModel.ScoreKind.DETECTION -> {
                            scoreDetections(result[0].value)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NsfwModel inference failed", e)
            null
        }
    }

    /** Softmax over [logits], summed over [indices]. */
    private fun softmaxSum(logits: FloatArray, indices: IntArray): Float {
        var max = logits[0]
        for (v in logits) if (v > max) max = v
        var sum = 0f
        val exps = FloatArray(logits.size)
        for (i in logits.indices) {
            exps[i] = kotlin.math.exp((logits[i] - max).toDouble()).toFloat()
            sum += exps[i]
        }
        if (sum <= 0f) return 0f
        var out = 0f
        for (i in indices) {
            if (i in exps.indices) out += exps[i] / sum
        }
        return out
    }

    /** NudeNet output is [1, 22, N]: 4 box rows + 18 class rows, N
     * candidate boxes. The score is the highest confidence among
     * detections of an unsafe (exposed) body part — NMS is skipped: it
     * only removes overlapping duplicates, and the maximum survives it.
     * Rows below 0.2 are ignored, exactly like the Python library. */
    private fun scoreDetections(value: Any?): Scored? {
        val out = value as? Array<*> ?: return null
        val batch = out.getOrNull(0) as? Array<*> ?: return null
        if (batch.size < 22) return null
        val n = (batch.getOrNull(0) as? FloatArray)?.size ?: return null
        var best = 0f
        var bestClass: String? = null
        for (i in 0 until n) {
            var maxScore = 0f
            var maxClass = -1
            for (c in UNSAFE_NUDENET_CLASSES) {
                val row = batch[4 + c] as? FloatArray ?: continue
                val s = row.getOrNull(i) ?: 0f
                if (s > maxScore) {
                    maxScore = s
                    maxClass = c
                }
            }
            if (maxScore >= 0.2f && maxScore > best) {
                best = maxScore
                bestClass = NUDENET_LABELS.getOrNull(maxClass)
            }
        }
        return Scored(best, bestClass)
    }

    fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Image guard: while a lock runs, takes a screenshot of the screen about
 * once a second (through the accessibility service — no screen-share
 * permission is needed, and there is no grant to lose when the screen
 * locks), classifies each frame with the on-device ONNX model, and when
 * the NSFW score passes the sensitivity threshold covers the screen and
 * sends the user Home. Nothing is saved or sent anywhere — frames are
 * classified in memory and recycled at once.
 *
 * The service only runs while the guard is on AND the model is ready
 * (see [sync]); the sampling loop itself also checks the lock, so no
 * frame is ever captured outside a lock. Screenshots need Android 9+
 * (takeScreenshot arrived in API 28) and the accessibility service
 * enabled — the guard cannot work without either.
 */
class ImageGuardService : Service() {

    companion object {
        const val ACTION_START = "com.example.powerlocks.IG_START"

        private const val NOTIF_ID = 11
        private const val NOTIF_MODEL_ERR_ID = 13
        private const val CHANNEL_ID = "powerlock_image"
        private const val SCAN_INTERVAL_MS = 1000L
        private const val IDLE_INTERVAL_MS = 3000L
        private const val COVER_HIDE_MS = 1500L
        private const val DETECT_COOLDOWN_MS = 5000L
        // Android rate-limits accessibility screenshots (about one per
        // second): never ask sooner than this after the last shot
        private const val SHOT_MIN_MS = 1100L

        @Volatile
        var isRunning = false
            private set

        /** Plain-language diagnostic lines (newest last), capped at 60. */
        val debugSummary = mutableListOf<String>()

        /** Technical diagnostic lines (newest last), capped at 120. */
        val debugLog = mutableListOf<String>()

        /** "[HH:mm]" stamp for diagnostic lines. */
        fun stamp(): String =
            SimpleDateFormat("HH:mm", Locale.US).format(Date())

        fun dbgSummary(line: String) {
            synchronized(debugSummary) {
                debugSummary.add(line)
                while (debugSummary.size > 60) debugSummary.removeAt(0)
            }
        }

        fun dbg(line: String) {
            synchronized(debugLog) {
                debugLog.add(line)
                while (debugLog.size > 120) debugLog.removeAt(0)
            }
        }

        fun clearDebug() {
            synchronized(debugSummary) { debugSummary.clear() }
            synchronized(debugLog) { debugLog.clear() }
        }

        /** Logs a model switch so the diagnostic log shows which model each
         * score came from when the user compares models. */
        fun noteModelSwitch(modelId: String) {
            dbgSummary("[${stamp()}] মডেল বদলানো হয়েছে: $modelId")
            dbg("model switched to $modelId")
        }

        /**
         * Starts the service iff the guard is on, the model is downloaded,
         * the device can take accessibility screenshots (Android 9+) and
         * the accessibility service is enabled; otherwise stops it. The
         * service stays running the whole time the guard is on — only the
         * SCANNING is gated on a lock (see tick). Cheap and idempotent —
         * safe to call every second from the MainActivity ticker and from
         * the accessibility service. Starting a foreground service from
         * the background can throw; that is caught here and retried on
         * the next call, never a crash.
         */
        @Synchronized
        fun sync(ctx: Context) {
            try {
                val supported = Build.VERSION.SDK_INT >= 28
                val on = supported && Extra.imageGuardOn(ctx) && accessibilityOn(ctx)
                val modelOk = ImageGuardModel.ready(ctx)
                val want = on && modelOk
                if (want && !isRunning) {
                    ctx.startForegroundService(
                        Intent(ctx, ImageGuardService::class.java).setAction(ACTION_START)
                    )
                } else if (!want && isRunning) {
                    ctx.stopService(Intent(ctx, ImageGuardService::class.java))
                }
            } catch (e: Exception) {
                Log.e(TAG, "ImageGuard sync failed", e)
            }
        }

        /** True when this app's accessibility service is enabled — the
         * guard needs it for screenshots and for the foreground app. */
        fun accessibilityOn(ctx: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
            } catch (_: Exception) {
                false
            }
        }

        private fun ensureChannel(ctx: Context) {
            try {
                if (Build.VERSION.SDK_INT < 26) return
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Image guard", NotificationManager.IMPORTANCE_LOW)
                )
            } catch (e: Exception) {
                Log.e(TAG, "image guard channel failed", e)
            }
        }

    }

    private val handler = Handler(Looper.getMainLooper())
    private var inferThread: HandlerThread? = null
    private var inferHandler: Handler? = null
    // screenshot capture (build 45): a shot is requested from the
    // accessibility service about once a second — never while one is
    // still pending, and never sooner than SHOT_MIN_MS after the last one
    @Volatile
    private var shotPending = false
    private var lastShotAt = 0L

    @Volatile
    private var inferBusy = false

    @Volatile
    private var model: NsfwModel? = null

    @Volatile
    private var modelFailed = false

    /** Which model failed to load, if any — a failed load must not poison
     * the NEXT model: getModel() retries when the user picks a different
     * model than the one that failed, but never retries the same broken
     * model every second. */
    @Volatile
    private var failedModel: String? = null

    /** The failed model's file time at the failure — if the file is
     * replaced since (re-downloaded), the new file gets a fresh try. */
    @Volatile
    private var failedFileTime: Long = 0L

    /** Which model the loaded session belongs to — when the user switches
     * models, getModel() drops the old session and loads the new one. */
    @Volatile
    private var loadedModel: String? = null

    @Volatile
    private var destroyed = false

    private var foreground = false
    private var lastDetectAt = 0L
    private var idleTicks = 0
    private var cover: View? = null
    private var coverIcon: IconView? = null
    private var coverAttached = false
    // live score badge: a small floating pill with the foreground app's
    // latest NSFW score (see updateScoreBadge)
    private var scoreBadge: View? = null
    private var scoreBadgeText: TextView? = null
    private var scoreBadgeImage: ImageView? = null
    private var scoreBadgeAttached = false
    private var lastBadgePkg: String? = null
    private var lastBadgeLabel: String? = null
    // the latest frame the model classified (a copy of the 224x224 input),
    // shown in the live preview; shownFrame is the copy currently displayed.
    // Written on the inference thread (runInference sets it to the
    // highest-scoring tile), read on the main thread (updateScoreBadge).
    @Volatile
    private var lastFrame: Bitmap? = null
    private var shownFrame: Bitmap? = null

    @Volatile
    private var lastScore: Float? = null

    private val tick = object : Runnable {
        override fun run() {
            var nextDelay = SCAN_INTERVAL_MS
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastDetectAt < DETECT_COOLDOWN_MS) {
                    // cooling down after a detection: the cover itself must
                    // never be scanned
                } else if (!protecting()) {
                    // no lock: do NOT scan. The service stays alive (only
                    // the scanning pauses), so the next lock starts
                    // scanning at once.
                    nextDelay = IDLE_INTERVAL_MS
                    idleTicks++
                    // idle for ~30s: let sync() stop us if we are no longer
                    // wanted (guard turned off while the app was closed)
                    if (idleTicks >= 10) {
                        idleTicks = 0
                        sync(this@ImageGuardService)
                    }
                } else {
                    idleTicks = 0
                    // scan everywhere: the guard watches the whole screen,
                    // in any app — no app list gates it any more (build 46)
                    requestShot()
                }
                // live score badge (if the user turned it on): refresh every
                // tick — it also hides itself when the lock ends
                updateScoreBadge()
            } catch (e: Exception) {
                Log.e(TAG, "image guard tick failed", e)
            }
            if (!destroyed) handler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            L.load(this)
        } catch (_: Exception) {
        }
        isRunning = true
        val t = HandlerThread("ig-infer")
        t.start()
        inferThread = t
        inferHandler = Handler(t.looper)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // ACTION_START or a system restart (null intent): no grant is
            // needed any more — screenshots come from the accessibility
            // service, which is already running (sync() only starts us
            // when the guard is on, the model is ready and accessibility
            // is enabled)
            dbgSummary("[${stamp()}] গার্ড চালু হয়েছে")
            dbg("START: service started (screenshot capture)")
            goForeground()
            startSampling()
        } catch (e: Exception) {
            Log.e(TAG, "ImageGuard start failed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        dbgSummary("[${stamp()}] গার্ড বন্ধ হয়েছে")
        dbg("onDestroy")
        try {
            handler.removeCallbacks(tick)
            handler.removeCallbacksAndMessages(null)
            hideCover()
            hideScoreBadge()
            recycleFrames()
            try {
                model?.close()
            } catch (_: Exception) {
            }
            model = null
            loadedModel = null
            try {
                inferThread?.quitSafely()
            } catch (_: Exception) {
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "ImageGuard destroy failed", e)
        }
        isRunning = false
        foreground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun protecting(): Boolean = Store.isActive(this) || Store.isLocProtecting(this)

    /** Foreground service, type specialUse (matches the manifest). */
    private fun goForeground() {
        if (foreground) return
        foreground = true
        ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(L.t("Image guard active", "ছবি সুরক্ষা সক্রিয়"))
            .setContentText(L.t("Watching the screen while the lock runs", "লক চলাকালীন স্ক্রিন পর্যবেক্ষণ চলছে"))
            .setOngoing(true)
            .setContentIntent(pi)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, b.build())
        }
    }

    private fun startSampling() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Asks the accessibility service for a screenshot of the display and
     * scores it when it arrives. Screenshots are rate-limited by Android
     * (about one per second), so a tick while one is still pending — or
     * too soon after the last one — is skipped, never queued. The
     * callback runs on the screenshot worker thread; scanFrame() only
     * does bitmap work there and posts the inference to its own thread. */
    private fun requestShot() {
        if (shotPending) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotAt < SHOT_MIN_MS) return
        // screen off: nothing to see, and the shot would fail anyway
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return
        lastShotAt = now
        shotPending = true
        PowerBlockService.requestScreenshot { bmp ->
            shotPending = false
            if (destroyed) {
                try {
                    bmp?.recycle()
                } catch (_: Exception) {
                }
                return@requestScreenshot
            }
            if (bmp == null) return@requestScreenshot
            // the shot is full resolution: shrink it to the working size
            // the tiling expects (540 wide, like the old capture) before
            // scoring. scanFrame() owns and recycles the frame it gets.
            val small = downscaleShot(bmp)
            if (small !== bmp) {
                try {
                    bmp.recycle()
                } catch (_: Exception) {
                }
            }
            if (small != null) scanFrame(small)
        }
    }

    /** Shrinks a full-resolution screenshot to the working size (540 wide,
     * keeping the aspect ratio) — the size the old capture used, and what
     * the tiling expects. Returns null on failure. */
    private fun downscaleShot(bmp: Bitmap): Bitmap? {
        return try {
            val w = 540
            val h = (540f * bmp.height / bmp.width).toInt().coerceIn(240, 1200)
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun appLabel(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /** Splits a frame into overlapping square tiles along its long side, so
     * a small explicit region fills a tile instead of being diluted by the
     * whole screen: a small image downscaled to 224x224 scores low on
     * every model (the 19:27 log: L scored 0.13-0.39 on Albums content
     * that should have scored far higher). A near-square frame yields one
     * tile — the whole frame. */
    private fun tileRects(w: Int, h: Int): List<Rect> {
        val short = minOf(w, h)
        val long = maxOf(w, h)
        val portrait = h >= w
        val count = ((long.toFloat() / short * 2).roundToInt() - 1).coerceAtLeast(1)
        if (count == 1) return listOf(Rect(0, 0, w, h))
        val span = long - short
        val rects = ArrayList<Rect>(count)
        for (i in 0 until count) {
            val off = (span * i) / (count - 1)
            rects.add(if (portrait) Rect(0, off, w, off + short) else Rect(off, 0, off + short, h))
        }
        return rects
    }

    /** Tiles the frame and scores every tile (see tileRects). Takes
     * ownership of [bmp] and recycles it. */
    private fun scanFrame(bmp: Bitmap) {
        val meta = ImageGuardModel.info(Extra.igModel(this))
        // tile the frame (see tileRects): each tile is scored on its own
        // and the highest score wins, so a small explicit region is no
        // longer diluted by the rest of the screen
        val smalls = ArrayList<Bitmap>()
        try {
            for (r in tileRects(bmp.width, bmp.height)) {
                val tile = try {
                    Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                } catch (e: Exception) {
                    continue
                }
                val small = try {
                    prepareFrame(tile, meta)
                } catch (e: Exception) {
                    null
                } finally {
                    try {
                        tile.recycle()
                    } catch (_: Exception) {
                    }
                }
                if (small != null) smalls.add(small)
            }
        } finally {
            try {
                bmp.recycle()
            } catch (_: Exception) {
            }
        }
        if (smalls.isEmpty()) return
        if (inferBusy) {
            for (s in smalls) {
                try {
                    s.recycle()
                } catch (_: Exception) {
                }
            }
            return
        }
        val h = inferHandler
        if (h == null) {
            for (s in smalls) {
                try {
                    s.recycle()
                } catch (_: Exception) {
                }
            }
            return
        }
        inferBusy = true
        h.post { runInference(smalls) }
    }

    /** Prepares the exact bitmap the selected model checks: resized to its
     * input size, letterboxed (padded square) for NudeNet — the same
     * preprocessing its Python library uses (pad the long side with black,
     * top-left, then scale). The returned bitmap is also what the live
     * preview shows. */
    private fun prepareFrame(bmp: Bitmap, meta: ImageGuardModel.Info): Bitmap {
        val size = meta.inputSize
        if (meta.preprocess != ImageGuardModel.Preprocess.LETTERBOX_01) {
            return Bitmap.createScaledBitmap(bmp, size, size, true)
        }
        val m = maxOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(m, m, Bitmap.Config.ARGB_8888)
        Canvas(sq).drawBitmap(bmp, 0f, 0f, null)
        val out = Bitmap.createScaledBitmap(sq, size, size, true)
        try {
            sq.recycle()
        } catch (_: Exception) {
        }
        return out
    }

    /** Runs on the inference thread. Scores every tile; the highest score
     * wins. The preview shows the tile that scored highest — the region
     * the model found most suspicious. */
    private fun runInference(smalls: List<Bitmap>) {
        var bestTile: Bitmap? = null
        try {
            val m = getModel() ?: return
            val threshold = Extra.igThreshold(this)
            var best: NsfwModel.Scored? = null
            var bestIdx = -1
            for ((i, small) in smalls.withIndex()) {
                val scored = m.nsfwScore(small) ?: continue
                if (best == null || scored.score > best.score) {
                    best = scored
                    bestIdx = i
                }
            }
            if (best != null) {
                val score = best.score
                lastScore = score
                val hit = score >= threshold
                val s = String.format(Locale.US, "%.2f", score)
                val t = String.format(Locale.US, "%.2f", threshold)
                val app = PowerBlockService.foregroundPkg?.let { appLabel(it) } ?: "?"
                val mid = loadedModel ?: Extra.igModel(this)
                val detail = best.detail?.let { " ($it)" } ?: ""
                val tileInfo = if (smalls.size > 1) " [টাইল ${bestIdx + 1}/${smalls.size}]" else ""
                dbgSummary("[${stamp()}] $mid • $app: স্কোর $s (সীমা $t) — " + if (hit) "ধরা পড়েছে ✓" else "ধরা পড়েনি ✗" + detail + tileInfo)
                dbg("[$mid][$app] score=$s threshold=$t hit=$hit tile=${bestIdx + 1}/${smalls.size}" + detail)
                // the preview shows the highest-scoring tile (not recycled
                // below — it becomes lastFrame; the badge recycles the old
                // displayed copy when it switches to this one)
                bestTile = smalls[bestIdx]
                lastFrame = bestTile
                if (hit) handler.post { onDetected(score) }
            } else {
                dbg("inference failed (null score)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "inference failed", e)
        } finally {
            for (small in smalls) {
                if (small !== bestTile) {
                    try {
                        small.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
            inferBusy = false
        }
    }

    /** Lazy model load, on the inference thread. */
    private fun getModel(): NsfwModel? {
        val want = Extra.igModel(this)
        // the user switched models: drop the old session and load the new
        // one (the old file stays on the phone — switching never deletes).
        // A failed load must NOT poison the next model: the failure flag
        // is cleared when the wanted model differs from the one that
        // failed — the old code only cleared it when a session was loaded,
        // so one bad load killed every model until the service restarted.
        if (loadedModel != want) {
            try {
                model?.close()
            } catch (_: Exception) {
            }
            model = null
            loadedModel = null
            if (failedModel != want) modelFailed = false
        }
        model?.let { return it }
        // the same model failed before — but if its file was replaced
        // since (re-downloaded), the new file gets a fresh try
        if (modelFailed && failedModel == want) {
            val f = ImageGuardModel.file(this, want)
            if (f.exists() && f.lastModified() != failedFileTime) modelFailed = false
        }
        if (modelFailed) return null
        val t0 = SystemClock.uptimeMillis()
        val (m, reason) = NsfwModel.load(this, want)
        if (m == null) {
            modelFailed = true
            failedModel = want
            failedFileTime = try {
                ImageGuardModel.file(this, want).lastModified()
            } catch (_: Exception) {
                0L
            }
            dbgSummary("[${stamp()}] মডেল লোড হয়নি ($want): $reason")
            dbg("model load failed ($want): $reason")
            Store.settings(this).edit().putBoolean("ig_model_err", true).apply()
            handler.post { notifyModelError() }
            return null
        }
        val ms = SystemClock.uptimeMillis() - t0
        dbgSummary("[${stamp()}] মডেল লোড হয়েছে ($want, ${ms}ms)")
        dbg("model loaded ($want) in ${ms}ms")
        Store.settings(this).edit().putBoolean("ig_model_err", false).apply()
        model = m
        loadedModel = want
        failedModel = null
        return m
    }

    private fun notifyModelError() {
        try {
            ensureChannel(this)
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val b = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(L.t("Image guard model failed to load", "ছবি সুরক্ষার মডেল লোড হয়নি"))
                .setContentText(L.t("Turn image guard off and on again to re-download the model", "মডেল আবার ডাউনলোড করতে ছবি সুরক্ষা বন্ধ করে আবার চালু করুন"))
                .setContentIntent(pi)
                .setAutoCancel(true)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_MODEL_ERR_ID, b.build())
        } catch (e: Exception) {
            Log.e(TAG, "model error notify failed", e)
        }
    }

    // ---------- detection response ----------

    private fun onDetected(score: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDetectAt < DETECT_COOLDOWN_MS) return
        lastDetectAt = now
        Log.i(TAG, "image guard: NSFW detected score=$score")
        dbgSummary("[${stamp()}] ধরা পড়েছে! স্কোর " + String.format(Locale.US, "%.2f", score) + " — স্ক্রিন ঢাকা হয়েছে")
        dbg("DETECTED score=$score")
        try {
            Extra.logAttempt(this, "image")
        } catch (_: Exception) {
        }
        try {
            showCover()
        } catch (e: Exception) {
            Log.e(TAG, "showCover failed", e)
        }
        goHome()
        handler.postDelayed({ hideCover() }, COVER_HIDE_MS)
    }

    private fun goHome() {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "goHome failed", e)
        }
    }

    private fun ensureCover(): View {
        cover?.let { return it }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(250, 8, 16, 12))
        root.isClickable = true // touches must not reach the app underneath
        root.setPadding(dp(32), dp(32), dp(32), dp(32))
        val icon = IconView(this, Ic.SHIELD, C.primary)
        root.addView(icon, lp(dp(84), dp(84)))
        coverIcon = icon
        val title = tv(L.t("Inappropriate image detected", "অনুপযুক্ত ছবি শনাক্ত হয়েছে"), 22f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv(L.t("The screen is covered while the lock runs", "লক চলাকালীন স্ক্রিন ঢেকে দেওয়া হয়েছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        val btn = primaryBtn(L.t("Back to Home", "হোমে ফিরুন"))
        btn.setOnClickListener {
            hideCover()
            goHome()
        }
        root.addView(btn, lp(MATCH, dp(52), t = dp(28)))
        cover = root
        return root
    }

    private fun showCover() {
        if (coverAttached) return
        try {
            val v = ensureCover()
            v.alpha = 0f
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            coverAttached = true
            v.animate().alpha(1f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            coverIcon?.let { ic ->
                ic.scaleX = 0.6f
                ic.scaleY = 0.6f
                ic.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
        } catch (ex: Exception) {
            // overlay permission missing: the cover cannot show, but the
            // Home intent in onDetected still takes the user away
            Log.e(TAG, "showCover failed (overlay permission?)", ex)
            coverAttached = false
        }
    }

    private fun hideCover() {
        if (!coverAttached) return
        coverAttached = false
        try {
            val v = cover ?: return
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideCover failed", ex)
        }
    }

    // ---------- live score badge ----------

    /** Floating pill with the foreground app and its latest NSFW score,
     * while a lock runs and the user turned the live score on. Refreshed
     * every tick from the main thread; the score itself is written by the
     * inference thread. Never takes touches or focus. */
    private fun updateScoreBadge() {
        val showScore = Extra.liveScoreOn(this)
        val showPreview = Extra.previewOn(this)
        val show = (showScore || showPreview) && protecting() &&
            Settings.canDrawOverlays(this)
        if (!show) {
            hideScoreBadge()
            return
        }
        try {
            ensureScoreBadge()
        } catch (e: Exception) {
            Log.e(TAG, "score badge failed (overlay permission?)", e)
            return
        }
        val tv = scoreBadgeText ?: return
        val iv = scoreBadgeImage
        val fg = PowerBlockService.foregroundPkg
        val label = fg?.let {
            if (it == lastBadgePkg) lastBadgeLabel ?: it else {
                val l = appLabel(it)
                lastBadgePkg = it
                lastBadgeLabel = l
                l
            }
        } ?: "?"
        // the guard scans everywhere now (build 46) — the badge always
        // shows the live score while a lock runs, in any app
        val mid = Extra.igModel(this)
        tv.visibility = if (showScore) View.VISIBLE else View.GONE
        if (showScore) {
            val s = lastScore
            val threshold = Extra.igThreshold(this)
            val pct = if (s == null) "--" else "${(s * 100).toInt()}%"
            val thrPct = "${(threshold * 100).toInt()}%"
            // current score vs the threshold at which the screen blocks,
            // e.g. "Chrome 4% / 50% • L" — the screen blocks at 50%,
            // and L is the model doing the scoring
            tv.text = "$label $pct / $thrPct • $mid"
            tv.setTextColor(if (s != null && s >= threshold * 0.5f) C.warn else C.primary)
        }
        // live preview: the highest-scoring tile — the region the model
        // found most suspicious (with tiling, the model checks tiles, not
        // the whole frame at once)
        if (iv != null) {
            val frame = if (showPreview) lastFrame else null
            iv.visibility = if (frame != null) View.VISIBLE else View.GONE
            if (frame != null && frame !== shownFrame) {
                iv.setImageBitmap(frame)
                // recycle the old displayed copy only AFTER the view has
                // switched to the new one — never recycle a shown bitmap
                try {
                    shownFrame?.recycle()
                } catch (_: Exception) {
                }
                shownFrame = frame
            }
        }
    }

    private fun ensureScoreBadge() {
        if (scoreBadgeAttached) return
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(12), dp(6), dp(12), dp(6))
        root.background = rounded(Color.argb(200, 8, 16, 12), dp(16).toFloat())
        val text = tv("", 13f, C.primary, 1)
        root.addView(text)
        scoreBadgeText = text
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_XY
        img.visibility = View.GONE
        root.addView(img, lp(dp(120), dp(120), t = dp(6)))
        scoreBadgeImage = img
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp(12)
        params.y = dp(48)
        wm.addView(root, params)
        scoreBadge = root
        scoreBadgeAttached = true
    }

    private fun hideScoreBadge() {
        if (!scoreBadgeAttached) return
        scoreBadgeAttached = false
        try {
            val v = scoreBadge
            if (v != null) {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(v)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "hideScoreBadge failed", ex)
        }
        scoreBadge = null
        scoreBadgeText = null
        scoreBadgeImage = null
        recycleFrames()
    }

    /** Frees the preview bitmaps. Only call when the badge view is detached
     * (or never attached) — never recycle a bitmap an attached view shows. */
    private fun recycleFrames() {
        try {
            shownFrame?.recycle()
        } catch (_: Exception) {
        }
        try {
            // lastFrame may be a newer copy than the shown one
            if (lastFrame !== shownFrame) lastFrame?.recycle()
        } catch (_: Exception) {
        }
        shownFrame = null
        lastFrame = null
    }
}
