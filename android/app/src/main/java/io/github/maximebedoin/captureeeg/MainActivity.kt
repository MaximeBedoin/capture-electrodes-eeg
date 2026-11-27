package io.github.maximebedoin.captureeeg

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Size
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val VERSION = "android 0.1"

/** une photo gardée : fichier sur le disque (pas en mémoire : 144 photos de 12 Mpx) */
class Photo(val nom: String, val fichier: File, val net: Double, val j: JSONObject)

class MainActivity : ComponentActivity(), SensorEventListener {
    private var R = Reglages()
    private enum class Etat { PRET, CAPTURE, EXPORT }
    private var etat = Etat.PRET

    // capteurs
    private lateinit var sm: SensorManager
    @Volatile private var orient: Orientation? = null
    private var vitesse = 0.0  // °/s, lissée
    private var accel = 0.0    // m/s² sans la gravité, lissée
    private var lentDepuis = 0L

    // caméra
    private var imageCapture: ImageCapture? = null
    private val travail = Executors.newSingleThreadExecutor()
    @Volatile private var expoNs = 0L
    @Volatile private var iso = 0
    private var taille = ""

    // prise
    private var az0 = 0.0
    private var secteurs = Array(NSECT) { mutableListOf<Photo>() }
    private var nPris = 0
    private var remplacees = 0
    private var derniere = 0L
    @Volatile private var capturant = false
    private var debut = Date()
    private lateinit var dossier: File

    // vues
    private lateinit var apercu: PreviewView
    private lateinit var masque: MasqueView
    private lateinit var cadran: CadranView
    private lateinit var infos: TextView
    private lateinit var message: TextView
    private lateinit var compteur: TextView
    private lateinit var bouton: Button
    private lateinit var accueil: ScrollView
    private lateinit var erreur: TextView
    private val champs = mutableMapOf<String, EditText>()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ouvrirCamera() else erreur.text = "Accès à la caméra refusé"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lireReglages()
        construireVues()
        // retour : de la caméra aux paramètres ; pendant une prise, on ne quitte pas (photos perdues)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    accueil.visibility == View.VISIBLE -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    etat == Etat.CAPTURE -> dire("Prise en cours : touche Terminer d'abord", true)
                    etat == Etat.EXPORT -> {}
                    else -> {
                        ProcessCameraProvider.getInstance(this@MainActivity).get().unbindAll()
                        imageCapture = null
                        erreur.text = ""
                        accueil.visibility = View.VISIBLE
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // rotation « jeu » : sans magnétomètre (le casque et ses câbles le perturberaient), dérive lente en azimut
        val rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        for (s in listOf(rot, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)))
            s?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        Choreographer.getInstance().postFrameCallback(boucle)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        Choreographer.getInstance().removeFrameCallback(boucle)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                val m = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(m, e.values)
                orient = Orientation(m)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val w = Math.toDegrees(sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()))
                vitesse = 0.7 * vitesse + 0.3 * w
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val a = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                accel = 0.7 * accel + 0.3 * a
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------------------------------------------------------------- interface

    private fun dp(x: Int) = (x * resources.displayMetrics.density).roundToInt()

    private fun construireVues() {
        val racine = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        apercu = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        masque = MasqueView(this) { R.zone }
        racine.addView(apercu, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        racine.addView(masque, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val haut = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0)); setPadding(dp(12), dp(30), dp(12), dp(6))
        }
        infos = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = "…" }
        haut.addView(infos, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        haut.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = VERSION })
        racine.addView(haut, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        message = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        racine.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(16), dp(64), dp(16), 0)
        })

        val bas = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        cadran = CadranView(this) { i -> min(1f, secteurs[i].size.toFloat() / R.cible(i)) }
        bas.addView(cadran, LinearLayout.LayoutParams(dp(180), dp(180)))
        val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        compteur = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = "0 photo"; gravity = Gravity.CENTER }
        bouton = Button(this).apply { text = "Démarrer"; textSize = 18f; setOnClickListener { appui() } }
        droite.addView(compteur); droite.addView(bouton)
        bas.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bas.addView(droite)
        racine.addView(bas, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(24))
        })

        accueil = ScrollView(this).apply { setBackgroundColor(Color.rgb(17, 17, 17)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(40), dp(24), dp(24)) }
        fun para(t: String, taille: Float = 15f) = col.addView(TextView(this).apply {
            text = t; textSize = taille; setTextColor(Color.rgb(220, 220, 220)); setPadding(0, dp(8), 0, dp(8))
        })
        para("Capture EEG", 24f)
        para("Prise de vue guidée pour reconstruire le casque EEG en 3D. Tout reste sur le téléphone : " +
            "aucune image n'est envoyée sur internet.")
        para("Tiens le téléphone en portrait. Approche-toi jusqu'à ce que la tête remplisse la zone claire, " +
            "tourne lentement autour en t'arrêtant pour chaque photo, et remplis le cadran (centre = sommet, " +
            "bord = par en dessous : nuque, derrière les oreilles ; haut = face). Prends ton temps sur le bas du casque.")
        fun champ(cle: String, libelle: String, valeur: String) {
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            ligne.addView(TextView(this).apply { text = libelle; textSize = 15f; setTextColor(Color.WHITE) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val e = EditText(this).apply {
                setText(valeur); setTextColor(Color.WHITE); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            ligne.addView(e, LinearLayout.LayoutParams(dp(90), WRAP_CONTENT))
            champs[cle] = e
            col.addView(ligne)
        }
        champ("pps", "Photos par secteur", R.pps.toString())
        champ("ppsBas", "Photos par secteur, bande basse", R.ppsBas.toString())
        champ("vmax", "Rotation max à l'arrêt (°/s)", fmt(R.vmax))
        champ("zone", "Zone claire (% de la largeur)", fmt(R.zone * 100))
        champ("interv", "Intervalle entre photos (s)", fmt(R.interv))
        col.addView(Button(this).apply {
            text = "Ouvrir la caméra"; textSize = 18f
            setOnClickListener {
                lireChamps()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    ouvrirCamera() else permission.launch(Manifest.permission.CAMERA)
            }
        })
        erreur = TextView(this).apply { setTextColor(Color.rgb(255, 136, 136)); textSize = 15f }
        col.addView(erreur)
        accueil.addView(col)
        racine.addView(accueil, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Android 15 : l'app passe sous les barres système ; on décale le bandeau et le bas
        // de la hauteur de la barre d'état et des boutons de navigation
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(racine) { _, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            haut.setPadding(dp(12), b.top + dp(6), dp(12), dp(6))
            (message.layoutParams as FrameLayout.LayoutParams).topMargin = b.top + dp(40)
            (bas.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + dp(16)
            col.setPadding(dp(24), b.top + dp(16), dp(24), b.bottom + dp(24))
            racine.requestLayout()
            ins
        }
        setContentView(racine)
    }

    // 2 décimales au plus : les réglages passent par des Float (80 % relu « 80.000001 »)
    private fun fmt(x: Double) = "%.2f".format(Locale.US, x).trimEnd('0').trimEnd('.')

    private fun lireReglages() {
        val p = getPreferences(MODE_PRIVATE)
        R = Reglages(p.getInt("pps", 2), p.getInt("ppsBas", 2), p.getFloat("vmax", 12f).toDouble(), 0.6,
            p.getFloat("zone", 0.8f).toDouble(), p.getFloat("interv", 0.5f).toDouble())
    }

    private fun lireChamps() {
        fun v(k: String, d: Double) = champs[k]!!.text.toString().replace(',', '.').toDoubleOrNull() ?: d
        R = R.copy(pps = max(1, v("pps", 2.0).toInt()), ppsBas = max(1, v("ppsBas", 2.0).toInt()),
            vmax = v("vmax", 12.0), zone = (v("zone", 80.0) / 100).coerceIn(0.3, 1.0), interv = max(0.2, v("interv", 0.5)))
        getPreferences(MODE_PRIVATE).edit().putInt("pps", R.pps).putInt("ppsBas", R.ppsBas)
            .putFloat("vmax", R.vmax.toFloat()).putFloat("zone", R.zone.toFloat()).putFloat("interv", R.interv.toFloat()).apply()
        masque.invalidate()
    }

    private fun dire(m: String, alerte: Boolean) {
        if (message.text != m) message.text = m
        message.setBackgroundColor(if (alerte) Color.argb(217, 200, 40, 40) else Color.argb(204, 40, 160, 70))
    }

    // ---------------------------------------------------------------- caméra

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun ouvrirCamera() {
        val futur = ProcessCameraProvider.getInstance(this)
        futur.addListener({
            try {
                val fournisseur = futur.get()
                // photo 4:3 vers 12 Mpx : tout le champ du capteur, sans les 50 Mpx (trop lourds pour 144 photos)
                val choix = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val pb = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                // temps de pose et ISO réels, lus sur l'aperçu (exposition automatique)
                Camera2Interop.Extender(pb).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { expoNs = it }
                        res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                    }
                })
                val preview = pb.build().also { it.surfaceProvider = apercu.surfaceProvider }
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(choix)
                    .setJpegQuality(92)
                    .build()
                fournisseur.unbindAll()
                fournisseur.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                imageCapture = ic
                ic.resolutionInfo?.resolution?.let { taille = "${min(it.width, it.height)}×${max(it.width, it.height)}" }
                accueil.visibility = View.GONE
                dire("En portrait, face au visage, la tête remplit la zone claire, puis Démarrer", false)
            } catch (e: Exception) {
                erreur.text = "Erreur caméra : ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun texteExpo() = if (expoNs > 0) "1/${(1e9 / expoNs).roundToInt()} s ISO $iso" else "exposition auto"

    // ---------------------------------------------------------------- prise

    private fun appui() {
        when (etat) {
            Etat.PRET -> {
                val o = orient ?: run { dire("Capteurs muets : bouge un peu le téléphone", true); return }
                az0 = o.anglesAbsolus()[1]  // azimut 0 = face, au départ
                secteurs = Array(NSECT) { mutableListOf() }; nPris = 0; remplacees = 0; debut = Date()
                dossier = File(cacheDir, "capture_${horodatage(debut)}").apply { deleteRecursively(); mkdirs() }
                compteur.text = "0 photo"
                etat = Etat.CAPTURE; bouton.text = "Terminer"
            }
            Etat.CAPTURE -> {
                etat = Etat.EXPORT; bouton.text = "…"; bouton.isEnabled = false
                dire("Enregistrement du fichier…", false)
                travail.execute { exporter() }
            }
            Etat.EXPORT -> {}
        }
    }

    private val boucle = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            Choreographer.getInstance().postFrameCallback(this)
            val t = SystemClock.uptimeMillis()
            val o = orient
            val pos = if (o != null && etat == Etat.CAPTURE) anglesRelatifs(o, az0) else null
            cadran.position = pos; cadran.invalidate()
            if (imageCapture != null) infos.text = "$taille · ${texteExpo()}" + if (o == null) " · capteurs muets" else ""
            if (etat != Etat.CAPTURE || pos == null) return
            // photo seulement téléphone immobile (rotation ET accélération faibles) depuis au moins 0,4 s
            if (vitesse > R.vmax || accel > R.amax) lentDepuis = t
            if (vitesse > 3 * R.vmax) { dire("Trop vite", true); return }
            val s = secteur(pos[0], pos[1])
            if (s < 0) { dire("Trop bas : remonte", true); return }
            val manquants = secteurs.indices.count { secteurs[it].size < R.cible(it) }
            val plein = secteurs[s].size >= R.cible(s)
            when {
                plein -> dire(if (manquants > 0) "Zone couverte : va vers le rouge ($manquants secteurs)" else "Couverture complète ✓ tu peux terminer", false)
                t - lentDepuis < 400 -> dire("Arrête-toi : immobile une demi-seconde", false)
                else -> dire("Tête qui remplit la zone claire ($manquants secteurs)", false)
            }
            if (!capturant && t - lentDepuis > 400 && (t - derniere) / 1000.0 >= R.interv) {
                derniere = t
                capturer(s, pos, o!!, plein)
            }
        }
    }

    private fun capturer(s: Int, pos: DoubleArray, o: Orientation, plein: Boolean) {
        val ic = imageCapture ?: return
        capturant = true
        // pose et mouvement relevés au déclenchement, pas à l'arrivée de l'image
        val j = JSONObject().apply {
            put("t_ms", SystemClock.uptimeMillis())
            put("alpha", o.alpha); put("beta", o.beta); put("gamma", o.gamma)
            put("R_tel_vers_monde", org.json.JSONArray(o.m.map { it.toDouble() }))
            put("elevation", (pos[0] * 10).roundToInt() / 10.0); put("azimut", (pos[1] * 10).roundToInt() / 10.0)
            put("secteur", s)
            put("vitesse_deg_s", (vitesse * 10).roundToInt() / 10.0); put("accel_m_s2", (accel * 100).roundToInt() / 100.0)
            put("exposition_s", expoNs / 1e9); put("iso", iso)
        }
        ic.takePicture(travail, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try { traiter(image, s, plein, j) } catch (e: Exception) {
                    runOnUiThread { dire("Erreur photo : ${e.message}", true) }
                } finally { image.close(); capturant = false }
            }
            override fun onError(e: ImageCaptureException) {
                capturant = false
                runOnUiThread { dire("Erreur photo : ${e.message}", true) }
            }
        })
    }

    /** sur le fil de travail : netteté, décision, rotation droite et écriture */
    private fun traiter(image: ImageProxy, s: Int, plein: Boolean, j: JSONObject) {
        val buf = image.planes[0].buffer
        val jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
        // netteté sur un carré de 512 px au centre, en pleine résolution (le laplacien ne dépend pas de la rotation)
        val dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        val n = 512
        val w = dec.width; val h = dec.height
        val carre = dec.decodeRegion(Rect((w - n) / 2, (h - n) / 2, (w + n) / 2, (h + n) / 2), null)
        dec.recycle()
        val px = IntArray(n * n); carre.getPixels(px, 0, n, 0, 0, n, n); carre.recycle()
        val g = FloatArray(n * n) { val c = px[it]; 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c) }
        val net = nettete(g, n)
        // comparaison dans le même secteur (même point de vue, même contenu) ; on n'écrit que si on garde
        val L = secteurs[s].toList()
        val pire = L.minByOrNull { it.net }
        if (plein) { if (pire == null || net < 1.15 * pire.net) return }
        else if (L.isNotEmpty() && net < 0.6 * L.maxOf { it.net }) return

        nPris++
        val nom = "img_%04d.jpg".format(nPris)
        val f = File(dossier, nom)
        // pixels remis droits (portrait) : COLMAP ignore l'orientation EXIF
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) f.writeBytes(jpeg) else {
            val bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            val droit = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            bm.recycle()
            f.outputStream().use { droit.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            droit.recycle()
        }
        val (lw, lh) = if (rot % 180 == 0) w to h else h to w
        j.put("image", nom); j.put("largeur", lw); j.put("hauteur", lh); j.put("nettete", (net * 10).roundToInt() / 10.0)
        val p = Photo(nom, f, net, j)
        runOnUiThread {
            val M = secteurs[s]
            if (plein && pire != null && M.contains(pire)) { M[M.indexOf(pire)] = p; pire.fichier.delete(); remplacees++ } else M.add(p)
            bouton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val total = secteurs.sumOf { it.size }
            compteur.text = "$total photos" + if (remplacees > 0) "\n($remplacees remplacées)" else ""
        }
    }

    private fun horodatage(d: Date) = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(d)

    /** zip sans compression (JPEG déjà compressés) dans Téléchargements/CaptureEEG */
    private fun exporter() {
        try {
            while (capturant) Thread.sleep(50)
            val nomDossier = dossier.name
            val photos = secteurs.flatMap { it }.sortedBy { it.nom }
            val session = JSONObject().apply {
                put("version_app", VERSION)
                put("appareil", "${Build.MANUFACTURER} ${Build.MODEL}"); put("android", Build.VERSION.RELEASE)
                put("camera", "$taille · ${texteExpo()}")
                put("reglages", JSONObject().apply {
                    put("pps", R.pps); put("ppsBas", R.ppsBas); put("vmax", R.vmax); put("amax", R.amax)
                    put("zone", R.zone); put("interv", R.interv)
                })
                put("convention", "Mode capteurs, sans suivi AR. R_tel_vers_monde : matrice de rotation (ligne par ligne) " +
                    "de TYPE_GAME_ROTATION_VECTOR, repère téléphone vers monde (x est, y nord, z haut ; azimut sans boussole, " +
                    "relatif). alpha, beta, gamma : angles W3C équivalents (R = Rz(alpha) Rx(beta) Ry(gamma)), comme la web app. " +
                    "elevation, azimut : position de la caméra vue du centre de la tête, déduite de l'axe optique en supposant " +
                    "que la caméra vise ce centre ; azimut 0 = face au départ. Images remises droites (portrait), sans EXIF d'orientation.")
            }
            val valeurs = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$nomDossier.zip")
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/CaptureEEG")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valeurs) ?: error("Téléchargements inaccessible")
            contentResolver.openOutputStream(uri)!!.use { sortie ->
                ZipOutputStream(sortie.buffered()).use { z ->
                    z.setLevel(Deflater.NO_COMPRESSION)
                    for (p in photos) { z.putNextEntry(ZipEntry("$nomDossier/${p.nom}")); p.fichier.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                    z.putNextEntry(ZipEntry("$nomDossier/poses.jsonl"))
                    z.write(photos.joinToString("\n", postfix = "\n") { it.j.toString() }.toByteArray())
                    z.closeEntry()
                    z.putNextEntry(ZipEntry("$nomDossier/session.json"))
                    z.write(session.toString(2).toByteArray()); z.closeEntry()
                }
            }
            dossier.deleteRecursively()
            runOnUiThread { dire("Enregistré : Téléchargements/CaptureEEG/$nomDossier.zip (${photos.size} photos)", false); finir() }
        } catch (e: Exception) {
            runOnUiThread { dire("Erreur d'enregistrement : ${e.message} (photos gardées)", true); etat = Etat.CAPTURE; bouton.text = "Terminer"; bouton.isEnabled = true }
        }
    }

    private fun finir() {
        etat = Etat.PRET; bouton.text = "Démarrer"; bouton.isEnabled = true
    }
}
