package com.example.powerlocks

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// =====================================================================
// DATA
// =====================================================================

data class Preset(val id: Long, val name: String, val min: Int, val strict: Boolean)

const val PLACE_MODE_INSIDE = 0 // lock while inside the place
const val PLACE_MODE_OUTSIDE = 1 // lock while outside the place

data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Int,
    val min: Int,
    val enabled: Boolean,
    val mode: Int = PLACE_MODE_INSIDE
)

/** One recorded point of today's route. */
data class RoutePoint(val lat: Double, val lng: Double, val t: Long)

/** One visit to a place: entered at [inTime], left at [outTime] (0 = still inside). */
data class PlaceEvent(val placeId: Long, val name: String, val inTime: Long, val outTime: Long)

object Extra {

    /** Build number, bumped on every push — shown in Settings so the user can
     * confirm which build is installed. A String because L.num takes a String. */
    const val BUILD_ID = "46"

    // ---------- presets ----------

    fun presets(ctx: Context): List<Preset> {
        val p = Store.settings(ctx)
        if (!p.contains("presets")) {
            val d = listOf(
                Preset(1, L.t("Night", "রাত"), 480, true),
                Preset(2, L.t("Study", "পড়াশোনা"), 120, false),
                Preset(3, L.t("Travel", "ভ্রমণ"), 360, false)
            )
            savePresets(ctx, d)
            return d
        }
        val out = ArrayList<Preset>()
        try {
            val arr = JSONArray(p.getString("presets", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Preset(o.getLong("id"), o.getString("n"), o.getInt("m"), o.getBoolean("s")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "presets read failed", e)
        }
        return out
    }

    fun savePresets(ctx: Context, list: List<Preset>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("n", x.name)
            o.put("m", x.min)
            o.put("s", x.strict)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString("presets", arr.toString()).apply()
    }

    // ---------- extend time (never reduce) ----------

    /** 0 = extended, 1 = no running session, 2 = would pass the 15 day limit. */
    fun extend(ctx: Context, addMs: Long): Int {
        if (addMs <= 0L) return 1
        if (!Store.isActive(ctx)) return 1
        val p = Store.lock(ctx)
        val start = p.getLong("start", 0)
        val end = p.getLong("end", 0)
        val newEnd = end + addMs
        if (newEnd - start > MAX_MIN * 60_000L) return 2
        p.edit().putLong("end", newEnd).putLong("total", newEnd - start).apply()
        Store.kickService(ctx)
        return 0
    }

    // ---------- blocked attempts ----------

    private val lastLog = HashMap<String, Long>()

    /** kind: "power", "settings", "focus", "appblock", "reels", "msgsearch" or "image". At most one entry per kind every 3 seconds. */
    @Synchronized
    fun logAttempt(ctx: Context, kind: String) {
        try {
            val now = System.currentTimeMillis()
            val last = lastLog[kind] ?: 0L
            if (now - last < 3000L) return
            lastLog[kind] = now
            val p = Store.lock(ctx)
            val old = try {
                JSONArray(p.getString("attempts", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            val out = JSONArray()
            val from = if (old.length() >= 1000) old.length() - 999 else 0
            for (i in from until old.length()) out.put(old.get(i))
            val o = JSONObject()
            o.put("t", now)
            o.put("k", kind)
            out.put(o)
            p.edit().putString("attempts", out.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "logAttempt failed", e)
        }
    }

    fun attempts(ctx: Context): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        try {
            val arr = JSONArray(Store.lock(ctx).getString("attempts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getLong("t"), o.getString("k")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "attempts read failed", e)
        }
        return out
    }

    // ---------- focus mode ----------

    fun focusApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("focus_apps", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setFocusApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("focus_apps", apps.joinToString(",")).apply()
    }

    // ---------- app block (Protection) ----------

    /** Master switch for the Protection section's app block. On by default. */
    fun appBlockOn(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("ab_on", true)

    /** Packages that are fully blocked: opening one jumps straight back to Home. */
    fun appBlockFull(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_full", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setAppBlockFull(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_full", apps.joinToString(",")).apply()
    }

    /** Packages whose Reels / Shorts feed is blocked; the app itself stays usable. */
    fun appBlockReels(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_reels", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setAppBlockReels(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_reels", apps.joinToString(",")).apply()
    }

    /** Packages whose in-app search is blocked: opening search shows the protection screen. */
    fun appBlockMsg(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_msg", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setAppBlockMsg(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_msg", apps.joinToString(",")).apply()
    }

    /** Packages added by typing the package name (not from the picker): they
     * stay in the block list even while the app is not installed, so
     * installing the app later still blocks it. */
    fun manualPackages(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_manual", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setManualPackages(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_manual", apps.joinToString(",")).apply()
    }

    // ---------- image guard (on-screen NSFW detection) ----------

    /** Master switch for image guard. Off by default; only scans while a lock runs. */
    fun imageGuardOn(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("ig_on", false)

    fun setImageGuardOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("ig_on", on).apply()
    }

    /** Packages covered by the Image Guard lockdown: when the screen-capture
     * grant ends mid-lock, only these apps get the protection screen until
     * permission is given again — every other app opens normally. */
    fun lockdownApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ig_lockdown", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setLockdownApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ig_lockdown", apps.joinToString(",")).apply()
    }

    /** Live score badge: while a lock runs, a small floating pill shows the
     * foreground app and its latest NSFW score in percent. Off by default. */
    fun liveScoreOn(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("ig_live_score", false)

    fun setLiveScoreOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("ig_live_score", on).apply()
    }

    /** Live preview: while a lock runs, a small floating preview shows the
     * exact frame the model is checking. Off by default. */
    fun previewOn(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("ig_live_preview", false)

    fun setPreviewOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("ig_live_preview", on).apply()
    }

    /** Image-guard model: XS, S, M or L (OwenElliott family), NUDE
     * (NudeNet 320n detector), MOB (MobileNetV4 classifier) or YAHOO
     * (Yahoo OpenNSFW). Default L (the original, most accurate). Unknown
     * values fall back to L. */
    fun igModel(ctx: Context): String {
        val m = (Store.settings(ctx).getString("ig_model", "L") ?: "L").uppercase()
        return if (m in setOf("XS", "S", "M", "L", "NUDE", "MOB", "YAHOO")) m else "L"
    }

    fun setIgModel(ctx: Context, model: String) {
        val m = model.uppercase()
        val v = if (m in setOf("XS", "S", "M", "L", "NUDE", "MOB", "YAHOO")) m else "L"
        Store.settings(ctx).edit().putString("ig_model", v).apply()
    }

    /** Sensitivity: 0 = high (catches more), 1 = medium, 2 = low (fewer false alarms). */
    fun igLevel(ctx: Context): Int =
        Store.settings(ctx).getInt("ig_level", 1).coerceIn(0, 2)

    fun setIgLevel(ctx: Context, level: Int) {
        Store.settings(ctx).edit().putInt("ig_level", level.coerceIn(0, 2)).apply()
    }

    /** NSFW score (0..1) at which the screen is covered. */
    fun igThreshold(ctx: Context): Float = when (igLevel(ctx)) {
        0 -> 0.5f
        2 -> 0.85f
        else -> 0.7f
    }

    /** True when the downloaded model file failed to load (corrupt download). */
    fun igModelError(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("ig_model_err", false)

    /** All launchable apps (label + icon), loaded once per session: loading ~150 icons is slow. */
    var appListCache: List<Triple<String, String, Drawable>>? = null

    /** Packages with a Reels / Shorts / Status feed — only these are offered for Reels block. */
    val reelsPackages = setOf(
        "com.instagram.android",      // Instagram Reels
        "com.facebook.katana",        // Facebook Reels
        "com.facebook.lite",          // Facebook Lite Reels
        "com.google.android.youtube", // YouTube Shorts
        "com.zhiliaoapp.musically",   // TikTok
        "com.ss.android.ugc.trill",   // TikTok (Asia)
        "com.snapchat.android",       // Snapchat Spotlight / Stories
        "com.whatsapp",               // WhatsApp Status
        "com.whatsapp.w4b",           // WhatsApp Business Status
        "org.telegram.messenger",     // Telegram Stories
        "com.twitter.android",        // X short videos
        "in.mohalla.sharechat",       // ShareChat short videos
        "com.versebrain.josh"         // Josh short videos
    )

    // ---------- places ----------

    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Place(
                        o.getLong("id"), o.getString("n"), o.getDouble("la"), o.getDouble("lo"),
                        o.getInt("r"), o.getInt("m"), o.getBoolean("on"),
                        o.optInt("md", PLACE_MODE_INSIDE)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "places read failed", e)
        }
        return out
    }

    fun savePlaces(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("n", x.name)
            o.put("la", x.lat)
            o.put("lo", x.lng)
            o.put("r", x.radius)
            o.put("m", x.min)
            o.put("on", x.enabled)
            o.put("md", x.mode)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString("places", arr.toString()).apply()
    }

    /** Best recent location, or a fresh single fix (up to 20 s). Calls back on the main thread. */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context, cb: (Location?) -> Unit) {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = m.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            }
        } catch (e: Exception) {
            Log.e(TAG, "last location failed", e)
        }
        val last: Location? = best
        if (last != null && System.currentTimeMillis() - last.time < 120_000L) {
            cb(last)
            return
        }
        var done = false
        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                try {
                    m.removeUpdates(this)
                } catch (e: Exception) {
                    Log.e(TAG, "remove updates failed", e)
                }
                cb(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }
        }
        var asked = false
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(p)) {
                    m.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    asked = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "request location failed", e)
        }
        if (!asked) {
            cb(last)
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                try {
                    m.removeUpdates(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "remove updates failed", e)
                }
                cb(last)
            }
        }, 20_000L)
    }
}

// =====================================================================
// LOCATION LOCK ENGINE (runs inside the always-on foreground service)
// =====================================================================

object PlaceWatcher {
    private var appCtx: Context? = null
    private var lm: LocationManager? = null
    private var on = false

    // per-place memory for history, boundary alerts and ETA
    private val lastInside = HashMap<Long, Boolean>()
    private val lastDist = HashMap<Long, Float>()
    private val lastAlert = HashMap<Long, String>() // "enter" or "leave"

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val c = appCtx ?: return
            handle(c, location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    /** Starts or stops location updates to match the settings and permissions. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val want = Store.settings(app).getBoolean("loc_on", false) &&
                Extra.places(app).any { it.enabled } &&
                Perm.locationOn(app)
        if (want && !on) start(app) else if (!want && on) stop()
        // not watching means not protected: master off, no enabled place, or no permission
        if (!want && Store.isLocProtecting(app)) Store.setLocProtecting(app, false)
    }

    /** One-shot check right now (used when the master switch or a place is turned on). */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!Store.settings(app).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(app)) return
        Extra.currentLocation(app) { loc ->
            if (loc != null) handle(app, loc)
        }
    }

    /** True when any enabled place is currently demanding protection. Null when no location is available. */
    fun isAnyPlaceActiveNow(ctx: Context, cb: (Boolean?) -> Unit) {
        Extra.currentLocation(ctx.applicationContext) { loc ->
            if (loc == null || (loc.hasAccuracy() && loc.accuracy > 200f)) { cb(null); return@currentLocation }
            var protect = false
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                if (pl.mode == PLACE_MODE_OUTSIDE) { if (d > pl.radius * 1.3f) protect = true }
                else if (d <= pl.radius) protect = true
            }
            cb(protect)
        }
    }

    /** True when this place is currently demanding protection. Null when no location is available. */
    fun isPlaceActiveNow(ctx: Context, pl: Place, cb: (Boolean?) -> Unit) {
        Extra.currentLocation(ctx.applicationContext) { loc ->
            if (loc == null || (loc.hasAccuracy() && loc.accuracy > 200f)) { cb(null); return@currentLocation }
            val res = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
            val d = res[0]
            cb(if (pl.mode == PLACE_MODE_OUTSIDE) d > pl.radius * 1.3f else d <= pl.radius)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(app: Context) {
        try {
            val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 20f, listener, Looper.getMainLooper())
            }
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 30f, listener, Looper.getMainLooper())
            }
            on = true
        } catch (e: Exception) {
            Log.e(TAG, "place watcher start failed", e)
            on = false
        }
    }

    fun stop() {
        try {
            lm?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.e(TAG, "place watcher stop failed", e)
        }
        on = false
    }

    private fun handle(ctx: Context, loc: Location) {
        try {
            if (loc.hasAccuracy() && loc.accuracy > 200f) return // too vague to trust
            // Presence-based protection: at a saved place the power menu is blocked, away it is not.
            // No Home session is involved at all — the single location switch is the only setting.
            var protect = false
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                val inside = d <= pl.radius
                val outside = d > pl.radius * 1.3f
                if (pl.mode == PLACE_MODE_OUTSIDE) {
                    // Lock while away: outside the place = blocked, back inside = unblocked.
                    if (outside) protect = true
                } else {
                    // Lock while here: inside the place = blocked, left = unblocked.
                    if (inside) protect = true
                }
                // ---- entry / exit history (hysteresis so GPS jitter at the edge does not flap) ----
                val prevIn = lastInside[pl.id]
                val nowIn = if (prevIn == true) d <= pl.radius * 1.3f else d <= pl.radius
                if (prevIn == null) lastInside[pl.id] = nowIn
                else if (prevIn != nowIn) {
                    lastInside[pl.id] = nowIn
                    if (nowIn) Store.recordEnter(ctx, pl) else Store.recordExit(ctx, pl)
                }
                // ---- boundary alert: 10 m before entering / leaving ----
                val edge = 10f
                val aboutEnter = d > pl.radius && d <= pl.radius + edge
                val aboutLeave = d >= pl.radius - edge && d <= pl.radius
                val prevD = lastDist[pl.id]
                lastDist[pl.id] = d
                val movingIn = prevD != null && d < prevD
                val movingOut = prevD != null && d > prevD
                val speed = if (loc.hasSpeed()) loc.speed else 0f
                if (aboutEnter && movingIn) {
                    if (lastAlert[pl.id] != "enter") {
                        lastAlert[pl.id] = "enter"
                        val eta = if (speed > 0.5f) ((d - pl.radius) / speed / 60f).toInt().coerceAtLeast(1) else null
                        notifyBoundary(ctx, pl, entering = true, turningOn = pl.mode != PLACE_MODE_OUTSIDE, etaMin = eta)
                    }
                } else if (aboutLeave && movingOut) {
                    if (lastAlert[pl.id] != "leave") {
                        lastAlert[pl.id] = "leave"
                        val eta = if (speed > 0.5f) ((pl.radius - d) / speed / 60f).toInt().coerceAtLeast(1) else null
                        notifyBoundary(ctx, pl, entering = false, turningOn = pl.mode == PLACE_MODE_OUTSIDE, etaMin = eta)
                    }
                } else if (d > pl.radius + edge || d < pl.radius - edge) {
                    lastAlert.remove(pl.id) // away from the edge: arm the alert again
                }
            }
            if (Store.isLocProtecting(ctx) != protect) Store.setLocProtecting(ctx, protect)
            // today's route: record where you went while the location lock watches
            Store.addRoutePoint(ctx, loc.latitude, loc.longitude)
        } catch (e: Exception) {
            Log.e(TAG, "place check failed", e)
        }
    }

    /** "বাড়ির সীমানায় ঢুকছো — লক চালু হবে" (10 m before the boundary), with ETA when moving. */
    private fun notifyBoundary(ctx: Context, pl: Place, entering: Boolean, turningOn: Boolean, etaMin: Int?) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dir = if (entering) L.t("entering the boundary", "সীমানায় ঢুকছো") else L.t("leaving the boundary", "সীমানা ছাড়ছো")
            val action = if (turningOn) L.t("lock turns on", "লক চালু হবে") else L.t("lock turns off", "লক বন্ধ হবে")
            val title = pl.name + ": " + dir + " — " + action
            val text = if (etaMin != null) {
                if (entering) L.t("Arriving in ~$etaMin min", "পৌঁছাতে ~$etaMin মিনিট")
                else L.t("Leaving in ~$etaMin min", "ছাড়তে ~$etaMin মিনিট")
            } else {
                L.t("Boundary within 10 m", "সীমানা ১০ মিটারের মধ্যে")
            }
            val openApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nb = Notification.Builder(ctx, "powerlock_alert")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(L.num(title))
                .setContentText(L.num(text))
                .setAutoCancel(true)
                .setContentIntent(openApp)
            nm.notify(2000 + (pl.id % 1000).toInt(), nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "boundary notify failed", e)
        }
    }
}

// =====================================================================
// UI: extend time
// =====================================================================

private fun MainActivity.addLabel(min: Int): String = "+ " + fmtDur(min * 60_000L)

fun MainActivity.extendFlow(done: () -> Unit) {
    if (!Store.isActive(this)) return
    val mins = intArrayOf(10, 30, 60, 180, 1440, -1)
    val labels = Array(mins.size) { i ->
        if (mins[i] > 0) addLabel(mins[i]) else L.t("Custom…", "কাস্টম…")
    }
    dlg()
        .setTitle(L.t("Add time", "সময় বাড়ান"))
        .setItems(labels) { _, which ->
            if (mins[which] > 0) confirmExtend(mins[which], done) else customExtend(done)
        }
        .show()
}

private fun MainActivity.confirmExtend(min: Int, done: () -> Unit) {
    val newEnd = Store.lock(this).getLong("end", 0) + min * 60_000L
    val at = fmtClock(newEnd)
    dlg()
        .setTitle(addLabel(min))
        .setMessage(
            L.t(
                "The lock will end at $at. Time can only be added, never reduced.",
                "লক চলবে $at পর্যন্ত। সময় শুধু বাড়ানো যায়, কমানো যায় না।"
            )
        )
        .setPositiveButton(L.t("Add", "বাড়ান")) { _, _ ->
            when (Extra.extend(this, min * 60_000L)) {
                0 -> toast(L.t("Time added", "সময় বাড়ানো হয়েছে"))
                2 -> toast(L.t("Maximum 15 days", "সর্বোচ্চ ১৫ দিন"))
                else -> toast(L.t("No running session", "কোনো সেশন চলছে না"))
            }
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.customExtend(done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val r = LinearLayout(this)
    val dEt = edit(L.t("Days", "দিন"), true)
    val hEt = edit(L.t("Hours", "ঘণ্টা"), true)
    val mEt = edit(L.t("Minutes", "মিনিট"), true)
    r.addView(dEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(hEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(mEt, lp(0, WRAP, weight = 1f))
    box.addView(r)
    val d = dlg()
        .setTitle(L.t("Add custom time", "নিজের সময় যোগ"))
        .setView(box)
        .setPositiveButton(L.t("Next", "পরবর্তী"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                (mEt.text.toString().toLongOrNull() ?: 0L)
        if (total < 1L || total > MAX_MIN.toLong()) {
            mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
        } else {
            d.dismiss()
            confirmExtend(total.toInt(), done)
        }
    }
}

// =====================================================================
// UI: presets
// =====================================================================

fun MainActivity.presetsCard(onApplied: () -> Unit): View {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.addView(heading(L.t("Presets", "প্রিসেট")), lp(MATCH, WRAP, t = dp(26), b = dp(2)))
    val scroll = HorizontalScrollView(this)
    scroll.isHorizontalScrollBarEnabled = false
    val row = LinearLayout(this)
    scroll.addView(row)
    val strictNow = Store.settings(this).getBoolean("strict", false)
    for (p in Extra.presets(this)) {
        val sel = p.min == selectedMin && p.strict == strictNow
        var label = p.name + " · " + fmtDur(p.min * 60_000L)
        if (p.strict) label += " · " + L.t("Strict", "স্ট্রিক্ট")
        val c = chip(label, sel)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.setOnClickListener { applyPreset(p, onApplied) }
        c.setOnLongClickListener {
            presetDialog(p, onApplied)
            true
        }
        row.addView(c, lp(WRAP, dp(52), r = dp(10), t = dp(10)))
    }
    val add = chip("＋ " + L.t("New", "নতুন"), false)
    add.setPadding(dp(16), dp(14), dp(16), dp(14))
    add.setOnClickListener { presetDialog(null, onApplied) }
    row.addView(add, lp(WRAP, dp(52), t = dp(10)))
    box.addView(scroll)
    box.addView(
        tv(L.t("Tap to apply · long-press to edit", "ট্যাপে প্রয়োগ · চেপে ধরলে সম্পাদনা"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(8))
    )
    return box
}

private fun MainActivity.applyPreset(p: Preset, done: () -> Unit) {
    if (Store.isActive(this)) {
        toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        return
    }
    selectedMin = p.min
    Store.settings(this).edit().putBoolean("strict", p.strict).apply()
    toast(L.t("Preset applied: ", "প্রিসেট প্রয়োগ: ") + p.name)
    done()
}

private fun MainActivity.presetDialog(existing: Preset?, done: () -> Unit) {
    if (Store.isActive(this)) {
        toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        return
    }
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val nameEt = edit(L.t("Name", "নাম"), false)
    nameEt.setText(existing?.name ?: "")
    box.addView(nameEt)
    val m0 = existing?.min ?: 60
    val r = LinearLayout(this)
    val dEt = edit(L.t("Days", "দিন"), true)
    val hEt = edit(L.t("Hours", "ঘণ্টা"), true)
    val mEt = edit(L.t("Minutes", "মিনিট"), true)
    dEt.setText((m0 / 1440).toString())
    hEt.setText(((m0 % 1440) / 60).toString())
    mEt.setText((m0 % 60).toString())
    r.addView(dEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(hEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(mEt, lp(0, WRAP, weight = 1f))
    box.addView(r, lp(MATCH, WRAP, t = dp(10)))
    var strict = existing?.strict ?: false
    val sw = toggle(strict) { v -> strict = v }
    box.addView(
        row(L.t("Strict mode", "স্ট্রিক্ট মোড"), L.t("No early stop", "আগে বন্ধ করা যাবে না"), sw),
        lp(MATCH, WRAP, t = dp(8))
    )
    val b = dlg()
        .setTitle(if (existing == null) L.t("New preset", "নতুন প্রিসেট") else L.t("Edit preset", "প্রিসেট সম্পাদনা"))
        .setView(box)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
    if (existing != null) b.setNeutralButton(L.t("Delete", "মুছুন"), null)
    val d = b.create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                (mEt.text.toString().toLongOrNull() ?: 0L)
        if (name.isEmpty()) {
            nameEt.error = L.t("Enter a name", "নাম লিখুন")
        } else if (total < 1L || total > MAX_MIN.toLong()) {
            mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
        } else {
            val all = Extra.presets(this)
            val updated: List<Preset> = if (existing == null) {
                all + Preset(System.currentTimeMillis(), name, total.toInt(), strict)
            } else {
                all.map { if (it.id == existing.id) Preset(it.id, name, total.toInt(), strict) else it }
            }
            Extra.savePresets(this, updated)
            d.dismiss()
            done()
        }
    }
    if (existing != null) {
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            Extra.savePresets(this, Extra.presets(this).filter { it.id != existing.id })
            d.dismiss()
            done()
        }
    }
}

// =====================================================================
// UI: focus mode (Settings)
// =====================================================================

fun MainActivity.focusCard(): View {
    val c = card()
    c.addView(section(L.t("Focus mode", "ফোকাস মোড")))
    val st = Store.settings(this)
    val locked = Store.isActive(this)
    val sw = toggle(st.getBoolean("focus_on", false)) { v -> st.edit().putBoolean("focus_on", v).apply() }
    if (locked) sw.isEnabled = false
    c.addView(
        row(
            L.t("Block selected apps", "নির্বাচিত অ্যাপ ব্লক"),
            L.t("While the lock runs, these apps jump straight back to Home.", "লক চলাকালীন এই অ্যাপ খুললেই সঙ্গে সঙ্গে হোমে ফিরে যাবে।"),
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val count = Extra.focusApps(this).size
    val pick = outlineBtn(L.t("Choose apps ($count selected)", "অ্যাপ বাছুন ($count টি নির্বাচিত)"), C.primary)
    pick.setOnClickListener {
        if (Store.isActive(this)) {
            toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        } else {
            appPicker { go(3) }
        }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    c.addView(
        tv(
            if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
            else L.t("Phone, dialer and emergency apps are never listed. Changes apply from the next session.", "ফোন, ডায়ালার ও জরুরি অ্যাপ তালিকায় আসে না। পরিবর্তন পরের সেশন থেকে কার্যকর।"),
            12f, if (locked) C.warn else C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

private fun MainActivity.appPicker(done: () -> Unit) {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    val skip = listOf("dialer", "incallui", "emergency", "telecom", "contacts")
    val apps = ArrayList<Triple<String, String, Drawable>>()
    val seen = HashSet<String>()
    try {
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            if (skip.any { pkg.contains(it) }) continue
            apps.add(Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
    } catch (e: Exception) {
        Log.e(TAG, "app list failed", e)
    }
    apps.sortBy { it.second.lowercase() }
    if (apps.isEmpty()) {
        toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
        return
    }
    val chosen = HashSet<String>(Extra.focusApps(this))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    list.setPadding(dp(20), dp(4), dp(20), dp(4))
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val pkg = info.first
        r.addView(toggle(chosen.contains(pkg)) { v ->
            if (v) chosen.add(pkg) else chosen.remove(pkg)
        })
        list.addView(r)
    }
    val sv = ScrollView(this)
    sv.addView(list)
    dlg()
        .setTitle(L.t("Choose apps to block", "ব্লক করার অ্যাপ বাছুন"))
        .setView(sv)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
            Extra.setFocusApps(this, chosen)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: app block (Protection)
// =====================================================================

/** Master switch + how app block works. */
fun MainActivity.protectionIntroCard(): View {
    val c = card()
    c.addView(section(L.t("App block", "অ্যাপ ব্লক")))
    val st = Store.settings(this)
    val locked = Store.isActive(this)
    val sw = toggle(Extra.appBlockOn(this)) { v -> st.edit().putBoolean("ab_on", v).apply() }
    if (locked) sw.isEnabled = false
    c.addView(
        row(
            L.t("Block apps while the lock runs", "লক চলাকালীন অ্যাপ ব্লক"),
            L.t("Choose apps below. Full block covers the app with a protection screen at once; Reels block only stops the Reels / Shorts feed; search block closes search as soon as you start typing and clears what was typed.", "নিচে অ্যাপ বাছুন। সম্পূর্ণ ব্লক সঙ্গে সঙ্গে সুরক্ষা স্ক্রিন দেখায়; রিলস ব্লক শুধু রিলস / শর্টস ফিড আটকায়; সার্চ ব্লক লিখতে শুরু করলেই সার্চ বন্ধ করে লেখা মুছে দেয়।"),
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val accOn = Perm.accessibilityOn(this)
    c.addView(
        tv(
            when {
                locked -> L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
                !accOn -> L.t("Accessibility is off — app block needs it. Turn it on from Settings.", "Accessibility বন্ধ — অ্যাপ ব্লকের জন্য এটা লাগবে। সেটিংস থেকে চালু করুন।")
                else -> L.t("Blocks apply while a lock session (or location lock) is running.", "লক সেশন (বা লোকেশন লক) চলাকালীন ব্লক কাজ করবে।")
            },
            12f, if (locked || !accOn) C.warn else C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

/** One mode card: [mode] is "full", "reels" or "msg". */
fun MainActivity.appBlockModeCard(mode: String): View {
    val c = card()
    c.addView(
        section(
            when (mode) {
                "full" -> L.t("Full app block", "সম্পূর্ণ অ্যাপ ব্লক")
                "msg" -> L.t("Search block", "সার্চ ব্লক")
                else -> L.t("Reels block", "রিলস ব্লক")
            }
        )
    )
    c.addView(
        tv(
            when (mode) {
                "full" -> L.t("Opening a selected app instantly shows a protection screen — the app stays covered.", "নির্বাচিত অ্যাপ খুললেই সঙ্গে সঙ্গে সুরক্ষা স্ক্রিন আসবে — অ্যাপের ভেতরে ঢোকা যাবে না।")
                "msg" -> L.t("Search in a selected app opens normally, but as soon as you start typing it closes and clears what was typed — the app itself stays usable. Searching inside a chat or channel is not blocked. Choose any app, e.g. Telegram or WhatsApp.", "নির্বাচিত অ্যাপে সার্চ খুলবে, কিন্তু লিখতে শুরু করলেই তা বন্ধ হয়ে যাবে আর লেখা মুছে যাবে — অ্যাপ ব্যবহার করা যাবে। চ্যাট বা চ্যানেলের ভিতরে খুঁজলে ব্লক হবে না। যেকোনো অ্যাপ বাছা যাবে, যেমন Telegram বা WhatsApp।")
                else -> L.t("The app stays usable, but its Reels / Shorts feed is closed. Only apps with Reels, Shorts or Status are listed.", "অ্যাপ ব্যবহার করা যাবে, কিন্তু এর রিলস / শর্টস ফিড বন্ধ হয়ে যাবে। শুধু রিলস, শর্টস বা স্ট্যাটাস আছে এমন অ্যাপ তালিকায় আসবে।")
            },
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val count = when (mode) {
        "full" -> Extra.appBlockFull(this).size
        "msg" -> Extra.appBlockMsg(this).size
        else -> Extra.appBlockReels(this).size
    }
    val locked = Store.isActive(this)
    val pick = outlineBtn(L.t("Choose apps ($count selected)", "অ্যাপ বাছুন ($count টি নির্বাচিত)"), C.primary)
    pick.setOnClickListener {
        // adding only strengthens the lock, so it stays allowed during a
        // session — the picker just won't let an already-blocked app be unchecked
        appBlockPicker(mode, Store.isActive(this)) { go(4) }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    val pkgBtn = outlineBtn(L.t("Add by package name", "প্যাকেজ নাম দিয়ে অ্যাড করুন"), C.primary)
    pkgBtn.setOnClickListener { addPackageDialog(mode) { go(4) } }
    c.addView(pkgBtn, lp(MATCH, dp(48), t = dp(8)))
    if (locked) {
        c.addView(tv(L.t("While a session runs, apps can only be added, not removed.", "সেশন চলাকালীন শুধু অ্যাপ যোগ করা যাবে, সরানো যাবে না।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

/** Add a package name by typing it — the app need not be installed yet, it is
 * blocked as soon as it is installed. [mode] is "full", "reels", "msg" or "lockdown". */
private fun MainActivity.addPackageDialog(mode: String, done: () -> Unit) {
    val input = edit(L.t("Package name, e.g. com.facebook.katana", "প্যাকেজ নাম, যেমন com.facebook.katana"), false)
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(input, lp(MATCH, WRAP))
    body.addView(
        tv(L.t("The app does not need to be installed yet — once it is installed, the block applies automatically.", "অ্যাপ এখন ইনস্টল না থাকলেও চলবে — ইনস্টল করলেই ব্লক কাজ করবে।"), 13f, C.muted),
        lp(MATCH, WRAP, t = dp(10))
    )
    dlg()
        .setTitle(L.t("Add by package name", "প্যাকেজ নাম দিয়ে অ্যাড করুন"))
        .setView(body)
        .setPositiveButton(L.t("Add", "যোগ করুন")) { _, _ ->
            val pkg = input.text.toString().trim().lowercase()
            if (pkg.isEmpty()) {
                toast(L.t("Type a package name", "প্যাকেজ নাম লিখুন"))
                return@setPositiveButton
            }
            if (!pkg.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$"))) {
                toast(L.t("Not a valid package name", "সঠিক প্যাকেজ নাম নয়"))
                return@setPositiveButton
            }
            // an app can only be in one list: full block wins over reels block;
            // the search list and the lockdown list are independent
            when (mode) {
                "full" -> {
                    Extra.setAppBlockFull(this, Extra.appBlockFull(this) + pkg)
                    Extra.setAppBlockReels(this, Extra.appBlockReels(this) - pkg)
                }
                "msg" -> Extra.setAppBlockMsg(this, Extra.appBlockMsg(this) + pkg)
                "lockdown" -> Extra.setLockdownApps(this, Extra.lockdownApps(this) + pkg)
                else -> {
                    Extra.setAppBlockReels(this, Extra.appBlockReels(this) + pkg)
                    Extra.setAppBlockFull(this, Extra.appBlockFull(this) - pkg)
                }
            }
            Extra.setManualPackages(this, Extra.manualPackages(this) + pkg)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.appBlockPicker(mode: String, addOnly: Boolean, done: () -> Unit) {
    // the app list (with icons) is loaded once per session on a background
    // thread: loading ~150 icons on the main thread froze the UI
    val cached = Extra.appListCache
    if (cached != null) {
        showAppBlockPicker(mode, cached, addOnly, done)
        return
    }
    val loading = dlg()
        .setTitle(L.t("Loading apps…", "অ্যাপ লোড হচ্ছে…"))
        .setView(tv(L.t("Please wait…", "একটু অপেক্ষা করুন…"), 14f, C.muted).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        })
        .setCancelable(false)
        .show()
    Thread {
        val apps = loadLaunchableApps()
        runOnUiThread {
            try { loading.dismiss() } catch (_: Exception) { }
            if (apps.isEmpty()) {
                toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
                return@runOnUiThread
            }
            Extra.appListCache = apps
            showAppBlockPicker(mode, apps, addOnly, done)
        }
    }.start()
}

/** All launchable apps (label + icon), sorted by name. Runs off the main thread. */
private fun MainActivity.loadLaunchableApps(): List<Triple<String, String, Drawable>> {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    val skip = listOf("dialer", "incallui", "emergency", "telecom", "contacts")
    val apps = ArrayList<Triple<String, String, Drawable>>()
    val seen = HashSet<String>()
    try {
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            if (skip.any { pkg.contains(it) }) continue
            apps.add(Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
    } catch (e: Exception) {
        Log.e(TAG, "app list failed", e)
    }
    apps.sortBy { it.second.lowercase() }
    return apps
}

private fun MainActivity.showAppBlockPicker(
    mode: String, allApps: List<Triple<String, String, Drawable>>, addOnly: Boolean, done: () -> Unit
) {
    // Reels block is only offered for apps that actually have Reels / Shorts / Status;
    // search block lists every app, so all Telegram-type apps (and any other) show up
    val apps = if (mode == "reels") allApps.filter { it.first in Extra.reelsPackages } else allApps
    if (apps.isEmpty()) {
        toast(
            when (mode) {
                "reels" -> L.t("No app with Reels / Shorts / Status found", "রিলস / শর্টস / স্ট্যাটাস আছে এমন কোনো অ্যাপ পাওয়া যায়নি")
                else -> L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি")
            }
        )
        return
    }
    val chosen = HashSet<String>(
        when (mode) {
            "full" -> Extra.appBlockFull(this)
            "msg" -> Extra.appBlockMsg(this)
            "lockdown" -> Extra.lockdownApps(this)
            else -> Extra.appBlockReels(this)
        }
    )
    var query = ""
    // rows are built once; the search filter only toggles visibility (fast typing)
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    val rows = ArrayList<Pair<View, String>>() // (row, lowercase label)
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val pkg = info.first
        val wasChosen = chosen.contains(pkg)
        val sw = toggle(wasChosen) { v ->
            if (v) chosen.add(pkg) else if (!(addOnly && wasChosen)) chosen.remove(pkg)
        }
        if (addOnly && wasChosen) {
            // add-only during a lock: an already-blocked app cannot be unchecked
            sw.isEnabled = false
            r.alpha = 0.6f
        }
        r.addView(sw)
        list.addView(r)
        rows.add(r to info.second.lowercase())
    }
    val emptyView = tv(L.t("No matching apps", "মিলছে এমন কোনো অ্যাপ নেই"), 13f, C.muted)
    list.addView(emptyView, lp(MATCH, WRAP, t = dp(8)))
    emptyView.visibility = View.GONE
    // A-Z fast-scroll index beside the list: touch or drag a letter to jump
    // to the first app starting with it (only for long lists, and hidden
    // while searching)
    val showIndex = apps.size > 20
    val lettersCol = LinearLayout(this)
    lettersCol.orientation = LinearLayout.VERTICAL
    lettersCol.gravity = Gravity.CENTER
    val letters = ('A'..'Z').toList()
    val letterViews = ArrayList<TextView>()
    for (ch in letters) {
        val lt = tv(ch.toString(), 10f, C.muted)
        lt.gravity = Gravity.CENTER
        lt.setPadding(0, dp(1), 0, dp(1))
        lettersCol.addView(lt, lp(MATCH, WRAP))
        letterViews.add(lt)
    }
    fun applyFilter() {
        val q = query.trim().lowercase()
        var shown = 0
        for ((row, label) in rows) {
            val vis = q.isEmpty() || label.contains(q)
            row.visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) shown++
        }
        emptyView.visibility = if (shown == 0) View.VISIBLE else View.GONE
        lettersCol.visibility = if (showIndex && q.isEmpty()) View.VISIBLE else View.GONE
    }
    val sv = ScrollView(this)
    sv.addView(list)
    // smooth animated scroll to a letter (a motion animation, not a jump):
    // the previous animation is cancelled so a fast drag chases the finger
    var scrollAnimator: ObjectAnimator? = null
    // animate on a tap (smooth motion); instant while dragging so the list
    // follows the finger in real time instead of lagging behind it
    fun jumpToLetter(ch: Char, animate: Boolean) {
        // first app whose name starts with this letter (or the next letter that has apps)
        val target = ch.lowercaseChar()
        for ((row, label) in rows) {
            val first = label.firstOrNull() ?: continue
            if (first >= target) {
                scrollAnimator?.cancel()
                scrollAnimator = null
                if (animate) {
                    scrollAnimator = ObjectAnimator.ofInt(sv, "scrollY", row.top).apply {
                        duration = 200
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                } else {
                    sv.scrollTo(0, row.top)
                }
                return
            }
        }
    }
    // the letter under the finger pops a bit bigger (animated) while touched
    var enlargedIdx = -1
    fun setLetterPop(idx: Int, popped: Boolean) {
        val v = letterViews.getOrNull(idx) ?: return
        v.setTextColor(if (popped) C.primary else C.muted)
        val s = if (popped) 1.5f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }
    // big letter bubble in the middle while dragging the index (like Contacts)
    val bubble = tv("", 30f, C.onPrimary)
    bubble.gravity = Gravity.CENTER
    bubble.background = rounded(C.primary, dp(32).toFloat())
    bubble.visibility = View.GONE
    bubble.alpha = 0f
    fun showBubble(ch: Char) {
        bubble.text = ch.toString()
        if (bubble.visibility != View.VISIBLE) {
            bubble.animate().cancel()
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.scaleX = 0.6f
            bubble.scaleY = 0.6f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }
    fun hideBubble() {
        if (bubble.visibility != View.VISIBLE) return
        bubble.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150).withEndAction {
            bubble.visibility = View.GONE
        }.start()
    }
    lettersCol.setOnTouchListener { _, ev ->
        when (ev.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val h = lettersCol.height
                if (h > 0) {
                    val idx = ((ev.y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
                    if (idx != enlargedIdx) {
                        if (enlargedIdx >= 0) setLetterPop(enlargedIdx, false)
                        setLetterPop(idx, true)
                        enlargedIdx = idx
                    }
                    showBubble(letters[idx])
                    jumpToLetter(letters[idx], ev.action == MotionEvent.ACTION_DOWN)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (enlargedIdx >= 0) {
                    setLetterPop(enlargedIdx, false)
                    enlargedIdx = -1
                }
                hideBubble()
                true
            }
            else -> false
        }
    }
    val search = edit(L.t("Search apps…", "অ্যাপ খুঁজুন…"), false)
    search.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            query = s?.toString() ?: ""
            applyFilter()
        }
    })
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(search, lp(MATCH, WRAP, b = dp(8)))
    val contentRow = LinearLayout(this)
    contentRow.orientation = LinearLayout.HORIZONTAL
    contentRow.addView(sv, lp(0, MATCH, weight = 1f))
    contentRow.addView(lettersCol, lp(dp(22), MATCH, l = dp(4)))
    val contentFrame = FrameLayout(this)
    contentFrame.addView(contentRow, FrameLayout.LayoutParams(MATCH, MATCH))
    val bubbleSize = dp(64)
    val bubbleParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
    bubbleParams.gravity = Gravity.CENTER
    contentFrame.addView(bubble, bubbleParams)
    body.addView(contentFrame, lp(MATCH, dp(400)))
    applyFilter()
    dlg()
        .setTitle(
            when (mode) {
                "full" -> L.t("Choose apps to block fully", "সম্পূর্ণ ব্লক করার অ্যাপ বাছুন")
                "msg" -> L.t("Choose apps for search block", "সার্চ ব্লকের অ্যাপ বাছুন")
                "lockdown" -> L.t("Choose apps for lockdown", "লকডাউন অ্যাপ বাছুন")
                else -> L.t("Choose apps for Reels block", "রিলস ব্লকের অ্যাপ বাছুন")
            }
        )
        .setView(body)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
            // an app can only be in one list: full block wins over reels block;
            // the search list is independent (an app can block both its feed and its search),
            // and so is the lockdown list (an app can be locked down as well as blocked).
            // manually added package names (not installed, so not in the picker)
            // are kept — saving must not wipe them
            val manual = Extra.manualPackages(this)
            when (mode) {
                "full" -> {
                    val keep = Extra.appBlockFull(this).filter { it in manual }
                    Extra.setAppBlockFull(this, chosen + keep)
                    Extra.setAppBlockReels(this, Extra.appBlockReels(this) - chosen)
                }
                "msg" -> {
                    val keep = Extra.appBlockMsg(this).filter { it in manual }
                    Extra.setAppBlockMsg(this, chosen + keep)
                }
                "lockdown" -> {
                    val keep = Extra.lockdownApps(this).filter { it in manual }
                    Extra.setLockdownApps(this, chosen + keep)
                }
                else -> {
                    val keep = Extra.appBlockReels(this).filter { it in manual }
                    Extra.setAppBlockReels(this, chosen + keep)
                    Extra.setAppBlockFull(this, Extra.appBlockFull(this) - chosen)
                }
            }
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: image guard (on-screen NSFW detection)
// =====================================================================

/** Image guard card: on-device AI watches the screen during a lock and covers
 * NSFW photos / videos. Experimental: it can be wrong and uses more battery. */
fun MainActivity.imageGuardCard(): View {
    val c = card()
    c.addView(section(L.t("Image guard", "ছবি সুরক্ষা")))
    c.addView(
        tv(
            L.t(
                "While a lock runs, on-device AI watches the screen in every app and covers nude photos or videos the moment they appear. Experimental — it can sometimes be wrong, and it uses more battery. No image ever leaves the phone. The powerful model downloads once (~106 MB — Wi-Fi recommended).",
                "লক চলাকালীন ফোনের ভেতরেই AI সব অ্যাপের স্ক্রিন দেখবে, আর নগ্ন ছবি বা ভিডিও আসলেই তা ঢেকে দেবে। পরীক্ষামূলক — মাঝে মাঝে ভুল হতে পারে, আর ব্যাটারি একটু বেশি খাবে। কোনো ছবি ফোনের বাইরে যাবে না। শক্তিশালী মডেল একবার ডাউনলোড হবে (~১০৬ MB — Wi-Fi দিয়ে করা ভালো)।"
            ),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val locked = Store.isActive(this)
    val on = Extra.imageGuardOn(this)
    val sw = toggle(on) { v ->
        if (v) enableImageGuard()
        else {
            Extra.setImageGuardOn(this, false)
            ImageGuardService.sync(this)
            go(4)
        }
    }
    // only strengthening during a session: the guard cannot be turned off
    if (locked && on) sw.isEnabled = false
    c.addView(
        row(
            L.t("Watch the screen during a lock", "লক চলাকালীন স্ক্রিন পর্যবেক্ষণ"),
            null,
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // sensitivity: high / medium / low
    val level = Extra.igLevel(this)
    val seg = SegmentedControl(
        this,
        listOf(L.t("High", "বেশি"), L.t("Medium", "মাঝারি"), L.t("Low", "কম")),
        level,
        onPick = { i ->
            if (locked && i > level) {
                // weakening during a session is not allowed
                toast(L.t("Sensitivity can only be increased during a session", "সেশন চলাকালীন সংবেদনশীলতা শুধু বাড়ানো যাবে"))
                go(4)
            } else {
                Extra.setIgLevel(this, i)
            }
        },
        onDone = { go(4) }
    )
    c.addView(seg, lp(MATCH, WRAP, t = dp(10)))
    c.addView(
        tv(
            L.t("High catches more, but may cover harmless photos too.", "বেশি সংবেদনশীল হলে বেশি ধরবে, তবে সাধারণ ছবিও ভুল করে ঢাকতে পারে।"),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // model switcher: XS / S / M / L / NUDE / MOB / YAHOO — compare which
    // model scores best on the same content. Locked during a session:
    // switching changes what the guard detects, so it is not allowed
    // mid-lock.
    val modelIds = listOf("XS", "S", "M", "L", "NUDE", "MOB", "YAHOO")
    val modelIdx = modelIds.indexOf(Extra.igModel(this)).coerceAtLeast(0)
    val modelSeg = SegmentedControl(
        this,
        modelIds,
        modelIdx,
        onPick = { i ->
            val picked = modelIds[i]
            if (locked) {
                toast(L.t("The model cannot be changed during a session", "সেশন চলাকালীন মডেল বদলানো যাবে না"))
                go(4)
            } else if (picked == Extra.igModel(this)) {
                // already selected (the control also ignores same-index taps)
            } else if (ImageGuardModel.ready(this, picked)) {
                Extra.setIgModel(this, picked)
                ImageGuardService.noteModelSwitch(picked)
                ImageGuardService.sync(this)
                go(4)
            } else {
                downloadModel(picked)
            }
        },
        onDone = { go(4) }
    )
    c.addView(modelSeg, lp(MATCH, WRAP, t = dp(10)))
    c.addView(
        tv(
            L.t("Model XS/S/M/L — OwenElliott, bigger is more accurate (XS 97.8%, S 98.0%, M 98.1%, L 98.2%). NUDE = NudeNet detector (finds exposed body parts), MOB = MobileNetV4 classifier, YAHOO = Yahoo OpenNSFW. Downloaded models stay on the phone; a new one downloads on Wi-Fi, or on mobile data if you confirm.", "মডেল XS/S/M/L — OwenElliott, বড়টা বেশি নির্ভুল (XS ৯৭.৮%, S ৯৮.০%, M ৯৮.১%, L ৯৮.২%)। NUDE = NudeNet ডিটেক্টর (খোলা শরীরের অংশ খুঁজে পায়), MOB = MobileNetV4 ক্লাসিফায়ার, YAHOO = Yahoo OpenNSFW। ডাউনলোড করা মডেল ফোনেই থাকবে; নতুন মডেল Wi-Fi-তে ডাউনলোড হয়, মোবাইল ডেটায় চাইলে অনুমতি চাইবে।"),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // live score badge: a small floating pill with the current app's latest
    // NSFW score, while a lock runs. Display only — it never changes what
    // the guard blocks, so it can be toggled any time.
    val liveSw = toggle(Extra.liveScoreOn(this)) { v ->
        Extra.setLiveScoreOn(this, v)
    }
    c.addView(
        row(
            L.t("Show live score during a lock", "লক চলাকালীন লাইভ স্কোর দেখান"),
            L.t("A small floating badge shows the current app and its latest bad-image score in percent.", "ছোট ভাসমান ব্যাজে এখনকার অ্যাপ আর তার সর্বশেষ খারাপ ছবির স্কোর শতাংশে দেখাবে।"),
            liveSw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // live preview: the exact frame the model is checking, as a small
    // floating thumbnail — display only, toggleable any time.
    val prevSw = toggle(Extra.previewOn(this)) { v ->
        Extra.setPreviewOn(this, v)
    }
    c.addView(
        row(
            L.t("Show what the model sees", "মডেল যা দেখছে তা দেখান"),
            L.t("A small floating preview shows the exact frame the model is checking during a lock.", "লক চলাকালীন মডেল যে ছবিটা পরীক্ষা করছে তার ছোট প্রিভিউ ভাসবে।"),
            prevSw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // status line
    val modelErr = Extra.igModelError(this)
    // the error flag is stale history (it survives app updates); the file
    // check is current truth, so it comes first — otherwise a stale flag
    // hides the real state and the guard can never start again after an
    // update (dead end: no service, no log, and the only offered way out
    // re-downloads a good model)
    val unsupported = Build.VERSION.SDK_INT < 28
    val noAccess = !ImageGuardService.accessibilityOn(this)
    val status = when {
        !on -> L.t("Off", "বন্ধ")
        unsupported -> L.t("Not supported on this phone — Android 9 or newer is needed", "এই ফোনে চলবে না — Android 9 বা তার ওপরের ভার্সন লাগবে")
        !ImageGuardModel.ready(this) -> L.t("Model ${Extra.igModel(this)} not downloaded yet — tap here to download", "মডেল ${Extra.igModel(this)} এখনো ডাউনলোড হয়নি — ডাউনলোড করতে এখানে চাপুন")
        noAccess -> L.t("Accessibility service is off — tap here to turn it on", "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ — চালু করতে এখানে চাপুন")
        modelErr -> L.t("Model failed to load — turn it off and on again to re-download", "মডেল লোড হয়নি — আবার ডাউনলোড করতে বন্ধ করে চালু করুন")
        Store.isActive(this) || Store.isLocProtecting(this) -> L.t("Lock running — watching the screen", "লক চলছে — স্ক্রিন পর্যবেক্ষণ চলছে")
        else -> L.t("On — active while a lock runs", "চালু আছে — লক চললে সক্রিয় হবে")
    }
    val statusTv = tv(status, 12f, if (!on) C.muted else C.primary)
    if (on && !ImageGuardModel.ready(this)) {
        // the selected model is missing (deleted, or its download failed):
        // tapping re-downloads it (asks before using mobile data, like the switcher)
        statusTv.setOnClickListener { downloadModel(Extra.igModel(this)) }
    } else if (on && noAccess) {
        statusTv.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Log.e(TAG, "accessibility settings failed", e)
            }
        }
    }
    c.addView(statusTv, lp(MATCH, WRAP, t = dp(8)))
    // diagnostic log: what the guard actually did on the phone — open it
    // and copy it to find out why a photo was not blocked
    val logRow = LinearLayout(this)
    logRow.orientation = LinearLayout.HORIZONTAL
    val viewLogBtn = outlineBtn(L.t("View log", "লগ দেখুন"), C.primary)
    viewLogBtn.setOnClickListener {
        val summary = ImageGuardService.debugSummary
        val text = if (summary.isEmpty()) L.t("No log yet — start a lock with image guard on first.", "এখনো লগ নেই — আগে ছবি সুরক্ষা চালু করে লক চালু করুন।") else synchronized(summary) { summary.joinToString("\n") }
        val detail = synchronized(ImageGuardService.debugLog) { ImageGuardService.debugLog.joinToString("\n") }
        val copyText = if (detail.isEmpty()) text else text + "\n\n--- " + L.t("Details", "বিস্তারিত") + " ---\n" + detail
        val tvLog = tv(text, 11f, C.text)
        val svLog = ScrollView(this)
        svLog.addView(tvLog)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(12), dp(20), dp(4))
        body.addView(svLog, lp(MATCH, dp(400)))
        dlg()
            .setTitle(L.t("Image guard log", "ছবি সুরক্ষা লগ"))
            .setView(body)
            .setPositiveButton(L.t("Copy", "কপি করুন")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PowerLock image guard", copyText))
                Toast.makeText(this, L.t("Copied", "কপি হয়েছে"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("Close", "বন্ধ"), null)
            .show()
    }
    logRow.addView(viewLogBtn, lp(0, WRAP, r = dp(8), weight = 1f))
    val clearLogBtn = outlineBtn(L.t("Clear", "মুছুন"), C.primary)
    clearLogBtn.setOnClickListener {
        ImageGuardService.clearDebug()
        Toast.makeText(this, L.t("Log cleared", "লগ মুছে ফেলা হয়েছে"), Toast.LENGTH_SHORT).show()
    }
    logRow.addView(clearLogBtn, lp(0, WRAP, weight = 1f))
    c.addView(logRow, lp(MATCH, WRAP, t = dp(10)))
    // overlay permission: without it the cover cannot show (Home still works)
    if (!Settings.canDrawOverlays(this)) {
        val note = tv(
            L.t("Overlay permission is off — allow it so the cover screen can appear. Tap here to open the setting.", "ওভারলে অনুমতি বন্ধ — কভার স্ক্রিন দেখাতে এটা চালু করুন। সেটিংস খুলতে এখানে চাপুন।"),
            12f, C.warn
        )
        note.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Log.e(TAG, "overlay settings failed", e)
            }
        }
        c.addView(note, lp(MATCH, WRAP, t = dp(8)))
    }
    if (locked) {
        c.addView(tv(L.t("While a session runs, the guard cannot be turned off, sensitivity can only be increased, and the model cannot be changed.", "সেশন চলাকালীন সুরক্ষা বন্ধ করা যাবে না, সংবেদনশীলতা শুধু বাড়ানো যাবে, আর মডেল বদলানো যাবে না।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

/** Enable flow: download the model first (once), then switch the guard
 * on — no permission step any more (build 45: screenshots come from the
 * accessibility service). ig_on is only written once the model is ready;
 * a failed download leaves the guard off. */
private fun MainActivity.enableImageGuard() {
    if (Build.VERSION.SDK_INT < 28) {
        toast(L.t("Image guard needs Android 9 or newer", "ছবি সুরক্ষার জন্য Android 9 বা তার ওপরের ভার্সন লাগবে"))
        go(4)
        return
    }
    // only download when the file is genuinely missing or incomplete — a
    // stale load-error flag must never delete a good model and spend
    // another 106 MB; a complete file gets its load retried by the service
    if (!ImageGuardModel.ready(this)) {
        val prog = tv("0%", 18f, C.text, 1)
        prog.gravity = Gravity.CENTER
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(12), dp(20), dp(4))
        body.addView(
            tv(L.t("Downloading the detection model (~106 MB, once only)…", "শনাক্তকরণের মডেল ডাউনলোড হচ্ছে (~১০৬ MB, শুধু একবার)…"), 14f, C.muted),
            lp(MATCH, WRAP, b = dp(10))
        )
        body.addView(prog, lp(MATCH, WRAP))
        val d = dlg()
            .setTitle(L.t("Downloading model…", "মডেল ডাউনলোড হচ্ছে…"))
            .setView(body)
            .setCancelable(false)
            .show()
        Thread {
            val result = ImageGuardModel.download(this) { pct ->
                runOnUiThread {
                    try {
                        prog.text = L.num(pct.toString()) + "%"
                    } catch (_: Exception) {
                    }
                }
            }
            runOnUiThread {
                try {
                    d.dismiss()
                } catch (_: Exception) {
                }
                if (!result.ok) {
                    val why = if (result.reason.isNotEmpty()) " (" + result.reason + ")" else ""
                    toast(L.t("Model download failed — check your internet and try again", "মডেল ডাউনলোড হয়নি — ইন্টারনেট দেখে আবার চেষ্টা করুন") + why)
                    go(4)
                    return@runOnUiThread
                }
                Extra.setImageGuardOn(this, true)
                ImageGuardService.sync(this)
                toast(L.t("Image guard is on", "ছবি সুরক্ষা চালু হয়েছে"))
                go(4)
            }
        }.start()
    } else {
        Extra.setImageGuardOn(this, true)
        ImageGuardService.sync(this)
        toast(L.t("Image guard is on", "ছবি সুরক্ষা চালু হয়েছে"))
        go(4)
    }
}

/** Downloads a model the user picked in the switcher, then selects it. The
 * current model keeps working until the new one is ready — the preference
 * only changes after a successful download, so a failed download never
 * leaves the guard without a model. On Wi-Fi the download starts at once;
 * on mobile data it asks first (showing the size) — the download is
 * resumable, so a dropped connection costs nothing extra. */
private fun MainActivity.downloadModel(modelId: String) {
    val meta = ImageGuardModel.info(modelId)
    if (!ImageGuardModel.isWifi(this)) {
        dlg()
            .setTitle(L.t("No Wi-Fi", "Wi-Fi নেই"))
            .setMessage(L.t("Model $modelId (${meta.sizeLabel}) will download on mobile data, once only. If the download drops, it resumes where it stopped instead of starting over. Download it?", "মডেল $modelId (${meta.sizeLabel}) মোবাইল ডেটা দিয়ে ডাউনলোড হবে, শুধু একবার। ডাউনলোড আটকে গেলে আবার শুরু থেকে হবে না — যেখানে থেমেছে সেখান থেকে চলবে। ডাউনলোড করবে?"))
            .setPositiveButton(L.t("Download", "ডাউনলোড করো")) { _, _ -> startModelDownload(modelId, meta) }
            .setNegativeButton(L.t("Cancel", "বাতিল")) { _, _ -> go(4) }
            .setOnCancelListener { go(4) }
            .show()
        return
    }
    startModelDownload(modelId, meta)
}

/** The actual download: progress dialog + background thread. Called from
 * downloadModel() after the Wi-Fi check / confirmation. */
private fun MainActivity.startModelDownload(modelId: String, meta: ImageGuardModel.Info) {
    val prog = tv("0%", 18f, C.text, 1)
    prog.gravity = Gravity.CENTER
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(
        tv(L.t("Downloading model $modelId (${meta.sizeLabel}, once only)…", "মডেল $modelId ডাউনলোড হচ্ছে (${meta.sizeLabel}, শুধু একবার)…"), 14f, C.muted),
        lp(MATCH, WRAP, b = dp(10))
    )
    body.addView(prog, lp(MATCH, WRAP))
    val d = dlg()
        .setTitle(L.t("Downloading model…", "মডেল ডাউনলোড হচ্ছে…"))
        .setView(body)
        .setCancelable(false)
        .show()
    Thread {
        val result = ImageGuardModel.download(this, modelId) { pct ->
            runOnUiThread {
                try {
                    prog.text = L.num(pct.toString()) + "%"
                } catch (_: Exception) {
                }
            }
        }
        runOnUiThread {
            try {
                d.dismiss()
            } catch (_: Exception) {
            }
            if (!result.ok) {
                val why = if (result.reason.isNotEmpty()) " (" + result.reason + ")" else ""
                toast(L.t("Model download failed — check your internet and try again", "মডেল ডাউনলোড হয়নি — ইন্টারনেট দেখে আবার চেষ্টা করুন") + why)
                go(4)
                return@runOnUiThread
            }
            Extra.setIgModel(this, modelId)
            ImageGuardService.noteModelSwitch(modelId)
            ImageGuardService.sync(this)
            go(4)
        }
    }.start()
}

/** Diagnostic card: what the service sees on a reels screen, so a missed block
 * can be traced instead of guessed. The log is viewed here and copied to the
 * clipboard to be sent for analysis. */
fun MainActivity.diagnosticCard(): View {
    val c = card()
    c.addView(section(L.t("Diagnostic", "ডায়াগনস্টিক")), lp(MATCH, WRAP, b = dp(6)))
    c.addView(tv(L.t("When a reels app is open during a lock, the service records a simple summary of what it sees on screen. Open the log, copy it and send it to find out why a reel was not blocked.", "লক চলাকালীন রিলস অ্যাপ খুললে সার্ভিস স্ক্রিনে কী দেখছে তার সহজ সারাংশ এখানে দেখা যাবে। লগ দেখে কপি করে পাঠালে বোঝা যাবে কেন রিলস ব্লক হয়নি।"), 13f, C.muted), lp(MATCH, WRAP, b = dp(10)))
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val viewBtn = outlineBtn(L.t("View log", "লগ দেখুন"), C.primary)
    viewBtn.setOnClickListener {
        // the dialog shows the plain summary; copying includes the full
        // technical log too, so the details are available when sent for analysis
        val summary = PowerBlockService.debugSummary
        val text = if (summary.isEmpty()) L.t("No log yet — open a reels app during a lock first.", "এখনো লগ নেই — আগে লক চলাকালীন রিলস অ্যাপ খুলুন।") else summary.joinToString("\n")
        val detail = PowerBlockService.debugLog.joinToString("\n")
        val copyText = if (detail.isEmpty()) text else text + "\n\n--- " + L.t("Details", "বিস্তারিত") + " ---\n" + detail
        val tvLog = tv(text, 11f, C.text)
        val svLog = ScrollView(this)
        svLog.addView(tvLog)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(12), dp(20), dp(4))
        body.addView(svLog, lp(MATCH, dp(400)))
        dlg()
            .setTitle(L.t("Diagnostic log", "ডায়াগনস্টিক লগ"))
            .setView(body)
            .setPositiveButton(L.t("Copy", "কপি করুন")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PowerLock debug", copyText))
                Toast.makeText(this, L.t("Copied", "কপি হয়েছে"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("Close", "বন্ধ"), null)
            .show()
    }
    row.addView(viewBtn, lp(0, WRAP, r = dp(8), weight = 1f))
    val clearBtn = outlineBtn(L.t("Clear", "মুছুন"), C.primary)
    clearBtn.setOnClickListener {
        PowerBlockService.debugLog.clear()
        PowerBlockService.debugSummary.clear()
        Toast.makeText(this, L.t("Log cleared", "লগ মুছে ফেলা হয়েছে"), Toast.LENGTH_SHORT).show()
    }
    row.addView(clearBtn, lp(0, WRAP, weight = 1f))
    c.addView(row)
    return c
}

/** The live list of locked apps for one mode: [mode] is "full", "reels" or "msg". */
fun MainActivity.lockedAppsCard(mode: String): View {
    val c = card()
    c.addView(
        section(
            when (mode) {
                "full" -> L.t("Fully blocked apps", "সম্পূর্ণ ব্লক করা অ্যাপ")
                "msg" -> L.t("Search blocked apps", "সার্চ ব্লক করা অ্যাপ")
                "lockdown" -> L.t("Lockdown apps", "লকডাউন অ্যাপ")
                else -> L.t("Reels blocked apps", "রিলস ব্লক করা অ্যাপ")
            }
        )
    )
    val locked = Store.isActive(this)
    val pm = packageManager
    var pkgs = when (mode) {
        "full" -> Extra.appBlockFull(this)
        "msg" -> Extra.appBlockMsg(this)
        "lockdown" -> Extra.lockdownApps(this)
        else -> Extra.appBlockReels(this)
    }
    if (pkgs.isEmpty()) {
        c.addView(
            tv(L.t("No apps here yet. Choose apps above.", "এখানে এখনো কোনো অ্যাপ নেই। ওপরে অ্যাপ বাছুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(6))
        )
        return c
    }
    // drop apps that were uninstalled since they were blocked — except
    // manually added package names, which stay blocked for a later install
    val manual = Extra.manualPackages(this)
    val missing = pkgs.filter { pkg ->
        pkg !in manual && try {
            pm.getApplicationInfo(pkg, 0)
            false
        } catch (e: Exception) {
            true
        }
    }.toSet()
    if (missing.isNotEmpty()) {
        pkgs -= missing
        when (mode) {
            "full" -> Extra.setAppBlockFull(this, pkgs)
            "msg" -> Extra.setAppBlockMsg(this, pkgs)
            "lockdown" -> Extra.setLockdownApps(this, pkgs)
            else -> Extra.setAppBlockReels(this, pkgs)
        }
    }
    data class Row(val pkg: String, val label: String, val icon: Drawable?, val installed: Boolean)
    val rows = ArrayList<Row>()
    for (pkg in pkgs) {
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            rows.add(Row(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), true))
        } catch (e: Exception) {
            // a manually added package that is not installed (yet)
            if (pkg in manual) rows.add(Row(pkg, pkg, null, false))
        }
    }
    rows.sortBy { it.label.lowercase() }
    for (row in rows) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        if (row.icon != null) {
            val icon = ImageView(this)
            icon.setImageDrawable(row.icon)
            r.addView(icon, lp(dp(36), dp(36), r = dp(12)))
        } else {
            r.addView(View(this), lp(dp(36), dp(36), r = dp(12)))
        }
        val labelCol = LinearLayout(this)
        labelCol.orientation = LinearLayout.VERTICAL
        labelCol.addView(tv(row.label, 15f, C.text))
        if (!row.installed) {
            labelCol.addView(tv(L.t("Not installed — blocked once installed", "ইনস্টল হয়নি — ইনস্টল করলেই ব্লক হবে"), 12f, C.muted))
        }
        r.addView(labelCol, lp(0, WRAP, weight = 1f))
        val remove = tv(L.t("Remove", "সরান"), 13f, C.warn)
        remove.setPadding(dp(8), dp(8), dp(8), dp(8))
        remove.pressFeedback()
        remove.setOnClickListener {
            if (Store.isActive(this)) {
                toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
            } else {
                when (mode) {
                    "full" -> Extra.setAppBlockFull(this, Extra.appBlockFull(this) - row.pkg)
                    "msg" -> Extra.setAppBlockMsg(this, Extra.appBlockMsg(this) - row.pkg)
                    "lockdown" -> Extra.setLockdownApps(this, Extra.lockdownApps(this) - row.pkg)
                    else -> Extra.setAppBlockReels(this, Extra.appBlockReels(this) - row.pkg)
                }
                Extra.setManualPackages(this, Extra.manualPackages(this) - row.pkg)
                go(4)
            }
        }
        r.addView(remove)
        c.addView(r, lp(MATCH, WRAP, t = dp(4)))
    }
    if (locked) {
        c.addView(tv(L.t("While a session runs, apps cannot be removed here — but new ones can be added above.", "সেশন চলাকালীন এখান থেকে সরানো যাবে না — তবে ওপরে নতুন যোগ করা যাবে।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

// =====================================================================
// UI: location lock (Settings)
// =====================================================================

private fun MainActivity.requestLocationPerm() {
    if (!Perm.locationOn(this)) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
    } else if (Build.VERSION.SDK_INT >= 29 && !Perm.backgroundLocationOn(this)) {
        requestPermissions(arrayOf("android.permission.ACCESS_BACKGROUND_LOCATION"), 12)
    }
}

/** Runs [block] only when this place is not locking right now; otherwise explains why. */
private fun MainActivity.ifPlaceNotLocking(pl: Place, block: () -> Unit) {
    if (!Store.isLocProtecting(this)) { block(); return }
    PlaceWatcher.isPlaceActiveNow(this, pl) { active ->
        when (active) {
            true -> toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
            false -> block()
            null -> toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
        }
    }
}

fun MainActivity.locationCard(): View {
    val c = card()
    val st = Store.settings(this)
    // header first: title on the left, master on/off on the right
    lateinit var sw: PremiumSwitch
    sw = toggle(st.getBoolean("loc_on", false)) { v ->
        if (!v && Store.isLocProtecting(this)) {
            // locked right now: only leaving the place turns it off
            PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
                if (active == true) {
                    toast(L.t("You are at a saved place — the lock turns off when you leave.", "সেভ করা জায়গায় আছেন — জায়গা ছেড়ে গেলে লক নিজে নিজে বন্ধ হবে।"))
                    sw.setCheckedImmediate(true)
                } else if (active == false) {
                    st.edit().putBoolean("loc_on", false).apply()
                    PlaceWatcher.sync(this)
                    PlaceWatcher.checkNow(this)
                } else {
                    toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
                    sw.setCheckedImmediate(true)
                }
            }
            return@toggle
        }
        st.edit().putBoolean("loc_on", v).apply()
        PlaceWatcher.sync(this)
        if (v) {
            if (!Perm.locationOn(this)) requestLocationPerm()
            PlaceWatcher.checkNow(this)
        }
    }
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(section(L.t("Location lock", "লোকেশন লক")), lp(0, WRAP, weight = 1f))
    head.addView(sw)
    c.addView(head)
    c.addView(
        tv(
            L.t(
                "Only this switch. While you are at a saved place the power menu is blocked; when you leave, it opens again. The Home lock does not need to be on.",
                "শুধু এই একটাই সুইচ। সেভ করা জায়গায় থাকলেই পাওয়ার মেনু ব্লক হবে; বাইরে গেলে আবার খুলে যাবে। হোমের লক অন করতে হবে না।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )

    val places = Extra.places(this)
    for (pl in places) {
        lateinit var rowSw: PremiumSwitch
        rowSw = toggle(pl.enabled) { v ->
            if (!v && Store.isLocProtecting(this)) {
                // this place may be locking right now: only leaving turns it off
                PlaceWatcher.isPlaceActiveNow(this, pl) { active ->
                    if (active == true) {
                        toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
                        rowSw.setCheckedImmediate(true)
                    } else if (active == false) {
                        Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = false) else it })
                        PlaceWatcher.sync(this)
                        PlaceWatcher.checkNow(this)
                    } else {
                        toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
                        rowSw.setCheckedImmediate(true)
                    }
                }
                return@toggle
            }
            Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = v) else it })
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
        }
        val modeTxt = if (pl.mode == PLACE_MODE_OUTSIDE) L.t("Lock when away", "বাইরে গেলে লক") else L.t("Lock when here", "এখানে থাকলে লক")
        val sub = L.num("${pl.radius} " + L.t("m radius", "মি. ব্যাসার্ধ")) + " · " + modeTxt
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(pl.name, 15f, C.text, 1))
        col.addView(tv(sub, 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        // visible delete button (long-press still works too)
        val del = tv("✕", 16f, C.danger, 1)
        del.gravity = Gravity.CENTER
        del.background = withRipple(rounded(C.card2, dp(18).toFloat()))
        del.pressFeedback()
        del.setOnClickListener { ifPlaceNotLocking(pl) { deletePlaceDialog(pl) { go(3) } } }
        r.addView(del, lp(dp(36), dp(36), r = dp(10)))
        r.addView(rowSw)
        r.setOnLongClickListener {
            ifPlaceNotLocking(pl) { deletePlaceDialog(pl) { go(3) } }
            true
        }
        c.addView(r)
    }

    val mapBtn = outlineBtn("🗺 " + L.t("Open map", "ম্যাপ খুলুন"), C.primary)
    mapBtn.setOnClickListener { startActivity(Intent(this, MapActivity::class.java)) }
    c.addView(mapBtn, lp(MATCH, dp(48), t = dp(12)))

    val add = outlineBtn("＋ " + L.t("Save my current place", "আমার এই জায়গা সেভ করুন"), C.primary)
    add.setOnClickListener { addPlaceFlow { go(3) } }
    c.addView(add, lp(MATCH, dp(48), t = dp(12)))

    val locOk = Perm.locationOn(this)
    val bgOk = Perm.backgroundLocationOn(this)
    c.addView(
        row(
            L.t("Location permission", "লোকেশন পারমিশন"),
            if (locOk) L.t("Granted", "অনুমোদিত") else L.t("Tap to allow", "অনুমতি দিতে চাপুন"),
            tv(if (locOk) "✔" else "✘", 18f, if (locOk) C.primary else C.danger, 1)
        ) { if (!locOk) requestLocationPerm() },
        lp(MATCH, WRAP, t = dp(6))
    )
    c.addView(
        row(
            L.t("Allow all the time", "সবসময় অনুমতি"),
            if (bgOk) L.t("Granted", "অনুমোদিত") else L.t("Needed so it works with the screen off", "স্ক্রিন বন্ধ থাকলেও কাজ করার জন্য দরকার"),
            tv(if (bgOk) "✔" else "✘", 18f, if (bgOk) C.primary else C.danger, 1)
        ) { if (!bgOk && locOk) requestLocationPerm() }
    )
    c.addView(
        tv(
            L.t(
                "Uses a little extra battery. Indoors the position can be off by 10-50 m, so use a radius of 100 m or more.",
                "ব্যাটারি একটু বেশি খরচ হয়। ঘরের ভেতরে অবস্থান ১০-৫০ মিটার ভুল হতে পারে, তাই ব্যাসার্ধ ১০০ মিটার বা বেশি রাখুন।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

/** Route (30 days, drawn on the map) + entry/exit history (30 days). */
fun MainActivity.historyCard(): View {
    val c = card()
    c.addView(section(L.t("Route & history (30 days)", "রুট ও হিস্ট্রি (৩০ দিন)")))
    // today's route: total distance + open the map
    val distM = Store.todayDistanceM(this)
    val pts = Store.todayRoute(this).size
    c.addView(
        row(
            L.t("Today's route", "আজকের রুট"),
            L.num(pts.toString()) + " " + L.t("points", "পয়েন্ট") + " · " + fmtDistM(distM),
            tv("🗺", 18f, C.primary, 1)
        ) { startActivity(Intent(this, MapActivity::class.java)) },
        lp(MATCH, WRAP, t = dp(4))
    )
    // this month's total distance (all stored days)
    c.addView(
        row(
            L.t("This month in total", "এই মাসে মোট"),
            fmtDistM(Store.monthDistanceM(this)),
            tv("🗓", 18f, C.primary, 1)
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    // entry / exit history
    c.addView(tv(L.t("Entry / exit history", "ঢোকা / বেরোনোর হিস্ট্রি"), 13f, C.muted), lp(MATCH, WRAP, t = dp(10)))
    val events = Store.placeEvents(this)
    if (events.isEmpty()) {
        c.addView(
            tv(
                L.t("No history yet — it appears when you enter or leave a saved place.", "এখনো কোনো হিস্ট্রি নেই — সেভ করা জায়গায় ঢুকলে বা বেরোলে এখানে দেখাবে।"),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = dp(4))
        )
    } else {
        for (e in events.take(30)) {
            val line1 = e.name + " · " + L.t("entered", "ঢুকেছে") + " " + fmtWhen(e.inTime)
            val line2 = if (e.outTime > 0) {
                L.t("left", "বেরিয়েছে") + " " + fmtWhen(e.outTime) + " · " +
                    L.t("inside for", "ভেতরে ছিল") + " " + fmtDur(e.outTime - e.inTime)
            } else {
                L.t("still inside", "এখনো ভেতরে") + " · " + fmtDur(System.currentTimeMillis() - e.inTime)
            }
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv(line1, 14f, C.text, 1))
            col.addView(tv(line2, 12f, C.muted))
            c.addView(col, lp(MATCH, WRAP, t = dp(8)))
        }
        val clear = tv(L.t("Clear history", "হিস্ট্রি মুছুন"), 13f, C.danger, 1)
        clear.setOnClickListener { Store.clearEvents(this); go(3) }
        c.addView(clear, lp(MATCH, WRAP, t = dp(10)))
    }
    return c
}

/** "3.4 km" / "250 m" for a distance in metres. */
private fun fmtDistM(distM: Float): String {
    return if (distM >= 1000f) {
        val km10 = (distM / 100).toInt() // tenths of a km
        L.num("${km10 / 10}.${km10 % 10}") + " " + L.t("km", "কিমি")
    } else {
        L.num(distM.toInt().toString()) + " " + L.t("m", "মি")
    }
}

/** "10:30 AM" today, "19 Nov 10:30 AM" on older days. */
private fun fmtWhen(ms: Long): String {
    return if (isToday(ms)) fmtClock(ms) else fmtDayShort(ms) + " " + fmtClock(ms)
}

private fun isToday(ms: Long): Boolean {
    val a = Calendar.getInstance()
    val b = Calendar.getInstance()
    b.timeInMillis = ms
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** "19 Nov" (day + short month). */
private fun fmtDayShort(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return L.num(c.get(Calendar.DAY_OF_MONTH).toString()) + " " + L.month(c.get(Calendar.MONTH))
}

private fun MainActivity.deletePlaceDialog(pl: Place, done: () -> Unit) {
    dlg()
        .setTitle(L.t("Delete this place?", "এই জায়গা মুছবেন?"))
        .setMessage(pl.name)
        .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
            Extra.savePlaces(this, Extra.places(this).filter { it.id != pl.id })
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.addPlaceFlow(done: () -> Unit) {
    if (!Perm.locationOn(this)) {
        requestLocationPerm()
        return
    }
    toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
    Extra.currentLocation(this) { loc ->
        if (loc == null) {
            toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
        } else {
            placeDialog(loc.latitude, loc.longitude, done)
        }
    }
}

private fun MainActivity.placeDialog(lat: Double, lng: Double, done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val nameEt = edit(L.t("Place name (e.g. Office)", "জায়গার নাম (যেমন অফিস)"), false)
    box.addView(nameEt)

    var radius = 200
    val radii = intArrayOf(100, 200, 500, 1000, 5000, 10000)
    val chips = ArrayList<TextView>()
    val seek = SeekBar(this)
    seek.max = 995 // (10000 - 50) / 10: 50 m to 10000 m in 10 m steps
    // header: label on the left, live value on the right
    val rHead = LinearLayout(this)
    rHead.orientation = LinearLayout.HORIZONTAL
    rHead.gravity = Gravity.CENTER_VERTICAL
    rHead.addView(tv(L.t("Radius", "ব্যাসার্ধ"), 13f, C.muted), lp(0, WRAP, weight = 1f))
    val radiusVal = tv(L.num("$radius " + L.t("m", "মি.")), 16f, C.primary, 1)
    rHead.addView(radiusVal)
    box.addView(rHead, lp(MATCH, WRAP, t = dp(14)))
    // quick chips, two rows: 100 / 200 / 500, then 1000 / 5000 / 10000
    for (rowRadii in listOf(intArrayOf(100, 200, 500), intArrayOf(1000, 5000, 10000))) {
        val rr = LinearLayout(this)
        for ((i, rv) in rowRadii.withIndex()) {
            val ch = chip(L.num("$rv " + L.t("m", "মি.")), rv == radius)
            ch.setOnClickListener { seek.progress = (rv - 50) / 10 }
            chips.add(ch)
            rr.addView(ch, lp(0, WRAP, r = if (i < rowRadii.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
        }
        box.addView(rr)
    }
    // smooth slider: drag to grow or shrink the area
    seek.progressTintList = ColorStateList.valueOf(C.primary)
    seek.progressBackgroundTintList = ColorStateList.valueOf(C.card2)
    seek.thumbTintList = ColorStateList.valueOf(C.mint)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            radius = 50 + p * 10
            radiusVal.setTextAnimated(L.num("$radius " + L.t("m", "মি.")))
            for (j in chips.indices) styleChip(chips[j], radii[j] == radius)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    seek.progress = (radius - 50) / 10
    box.addView(seek, lp(MATCH, WRAP, t = dp(10)))
    val range = LinearLayout(this)
    range.orientation = LinearLayout.HORIZONTAL
    range.addView(tv(L.num("50 " + L.t("m", "মি.")), 12f, C.muted), lp(0, WRAP, weight = 1f))
    range.addView(tv(L.num(L.t("10000 m (10 km)", "10000 মি. (10 কিমি)")), 12f, C.muted))
    box.addView(range)

    box.addView(tv(L.t("When to lock", "কখন লক হবে"), 13f, C.muted), lp(WRAP, WRAP, t = dp(14)))
    var mode = PLACE_MODE_INSIDE
    val modeChips = ArrayList<TextView>()
    val mr = LinearLayout(this)
    val modeLabels = arrayOf(L.t("While I'm here", "এখানে থাকলে"), L.t("While I'm away", "বাইরে গেলে"))
    for ((i, ml) in modeLabels.withIndex()) {
        val ch = chip(ml, i == mode)
        ch.setOnClickListener {
            mode = i
            for (j in modeChips.indices) styleChip(modeChips[j], j == mode)
        }
        modeChips.add(ch)
        mr.addView(ch, lp(0, WRAP, r = if (i < modeLabels.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
    }
    box.addView(mr)

    val d = dlg()
        .setTitle(L.t("Save this place", "এই জায়গা সেভ করুন"))
        .setView(box)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        if (name.isEmpty()) {
            nameEt.error = L.t("Enter a name", "নাম লিখুন")
        } else {
            val id = System.currentTimeMillis()
            // no time: the lock follows presence only, so the place has no duration (min = 0)
            Extra.savePlaces(this, Extra.places(this) + Place(id, name, lat, lng, radius, 0, true, mode))
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
            d.dismiss()
            done()
        }
    }
}

// =====================================================================
// UI: blocked attempts (Statistics)
// =====================================================================

fun MainActivity.attemptsCard(): View {
    val c = card()
    c.addView(section(L.t("Blocked attempts", "আটকানো চেষ্টা")))
    val list = Extra.attempts(this)
    val now = System.currentTimeMillis()
    val today = Store.dayStart(now, 0)
    val week = Store.dayStart(now, -6)
    var nToday = 0
    var nWeek = 0
    var nPower = 0
    var nSettings = 0
    var nFocus = 0
    var nAppBlock = 0
    var nReels = 0
    var nMsgSearch = 0
    var nImage = 0
    for ((t, k) in list) {
        if (t >= today) nToday++
        if (t >= week) nWeek++
        when (k) {
            "power" -> nPower++
            "settings" -> nSettings++
            "appblock" -> nAppBlock++
            "reels" -> nReels++
            "msgsearch" -> nMsgSearch++
            "image" -> nImage++
            else -> nFocus++
        }
    }
    val nums = LinearLayout(this)
    fun stat(value: Int, label: String, last: Boolean) {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.addView(tv(L.num(value.toString()), 26f, C.mint, 1))
        b.addView(tv(label, 12f, C.muted))
        nums.addView(b, lp(0, WRAP, r = if (last) 0 else dp(8), weight = 1f))
    }
    stat(nToday, L.t("Today", "আজ"), false)
    stat(nWeek, L.t("Last 7 days", "গত ৭ দিন"), false)
    stat(list.size, L.t("Total", "মোট"), true)
    c.addView(nums, lp(MATCH, WRAP, t = dp(12)))

    val chart = BarChartView(this)
    val vals = ArrayList<Long>()
    val labs = ArrayList<String>()
    for (off in -6..0) {
        val from = Store.dayStart(now, off)
        val to = Store.dayStart(now, off + 1)
        var n = 0L
        for ((t, _) in list) if (t >= from && t < to) n++
        vals.add(n)
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        labs.add(L.dayLong(dayIdx(cal)))
    }
    chart.values = vals
    chart.labels = labs
    c.addView(chart, lp(MATCH, dp(130), t = dp(14)))

    c.addView(
        tv(
            L.t(
                "Power menu: $nPower · App settings: $nSettings · Focus apps: $nFocus · App block: $nAppBlock · Reels block: $nReels · Search block: $nMsgSearch · Image guard: $nImage",
                "পাওয়ার মেনু: $nPower · অ্যাপ সেটিংস: $nSettings · ফোকাস অ্যাপ: $nFocus · অ্যাপ ব্লক: $nAppBlock · রিলস ব্লক: $nReels · সার্চ ব্লক: $nMsgSearch · ছবি সুরক্ষা: $nImage"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(10))
    )
    return c
}
