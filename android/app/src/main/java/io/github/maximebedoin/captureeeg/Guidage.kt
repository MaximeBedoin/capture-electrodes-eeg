package io.github.maximebedoin.captureeeg

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min

// Même découpage et mêmes règles que la web app 0.6 et SPEC.md.
// Mode « capteurs » (sans suivi AR) : la position de la caméra autour de la tête est déduite de
// l'orientation du téléphone, en supposant qu'il vise le centre de la tête.

/** élévation min, max (degrés), nombre de secteurs en azimut */
data class Bande(val min: Double, val max: Double, val n: Int)

val BANDES = listOf(Bande(-45.0, 0.0, 24), Bande(0.0, 30.0, 24), Bande(30.0, 60.0, 16), Bande(60.0, 90.1, 8))
val NSECT = BANDES.sumOf { it.n }

data class Reglages(
    var pps: Int = 2,          // photos par secteur
    var ppsBas: Int = 2,       // photos par secteur, bande basse
    var vmax: Double = 12.0,   // rotation max à l'arrêt, °/s
    var amax: Double = 0.6,    // accélération max à l'arrêt, m/s²
    var zone: Double = 0.8,    // diamètre de la zone claire, fraction de la largeur
    var interv: Double = 0.5,  // intervalle min entre photos, s
) {
    fun cible(s: Int) = if (s < BANDES[0].n) ppsBas else pps
}

/**
 * Matrice de rotation Android (SensorManager.getRotationMatrixFromVector, ligne par ligne,
 * téléphone → monde x est, y nord, z haut) : c'est la même convention que la matrice W3C
 * R = Rz(alpha) Rx(beta) Ry(gamma) de DeviceOrientationEvent.
 */
class Orientation(val m: FloatArray) {
    /** angles W3C (degrés), pour que le pipeline lise les mêmes champs que la web app */
    val beta get() = Math.toDegrees(asin(m[7].toDouble().coerceIn(-1.0, 1.0)))
    val gamma get() = Math.toDegrees(atan2(-m[6].toDouble(), m[8].toDouble()))
    val alpha get() = (Math.toDegrees(atan2(-m[1].toDouble(), m[4].toDouble())) + 360) % 360

    /** axe optique de la caméra arrière dans le repère monde : R·(0,0,−1) = −(3e colonne) */
    fun axe() = doubleArrayOf(-m[2].toDouble(), -m[5].toDouble(), -m[8].toDouble())

    /** [élévation, azimut absolu] de la caméra vue du centre de la tête (direction centre → caméra = −axe) */
    fun anglesAbsolus(): DoubleArray {
        val v = axe()
        val elev = Math.toDegrees(asin((-v[2]).coerceIn(-1.0, 1.0)))
        val az = Math.toDegrees(atan2(-v[0], -v[1]))
        return doubleArrayOf(elev, az)
    }
}

fun anglesRelatifs(o: Orientation, az0: Double): DoubleArray {
    val (e, a) = o.anglesAbsolus()
    var az = (a - az0) % 360
    if (az < 0) az += 360
    return doubleArrayOf(e, az)
}

fun secteur(elev: Double, az: Double): Int {
    var d = 0
    for (b in BANDES) {
        if (elev >= b.min && elev < b.max) return d + min(b.n - 1, floor(az / 360 * b.n).toInt())
        d += b.n
    }
    return -1
}

/** netteté = variance du laplacien sur une image en niveaux de gris (n × n), comme la web app */
fun nettete(g: FloatArray, n: Int): Double {
    var s = 0.0; var s2 = 0.0; var k = 0
    for (y in 1 until n - 1) for (x in 1 until n - 1) {
        val i = y * n + x
        val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - n] - g[i + n]).toDouble()
        s += l; s2 += l * l; k++
    }
    return s2 / k - (s / k) * (s / k)
}
