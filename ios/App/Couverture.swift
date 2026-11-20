import SwiftUI
import simd

/// Sphère autour de la tête découpée en secteurs (bandes d'élévation × azimut).
/// Élévation : angle de la caméra au-dessus de l'horizontale passant par le centre de la tête
/// (−30° : sous le menton / la nuque ; 90° : au-dessus du sommet). Azimut 0 = position au départ (face).
struct Couverture {
    static let bandes: [(min: Float, max: Float, n: Int)] = [(-30, 0, 12), (0, 30, 12), (30, 60, 8), (60, 90.1, 4)]
    static let nSecteurs = bandes.reduce(0) { $0 + $1.n }

    var azimut0: Float = 0
    var compte = [Int](repeating: 0, count: Couverture.nSecteurs)

    /// (élévation, azimut relatif au départ), en degrés, de la direction centre → caméra.
    func angles(_ dir: simd_float3) -> SIMD2<Float> {
        let elev = asin(max(-1, min(1, dir.y))) * 180 / .pi
        var az = (atan2(dir.x, dir.z) * 180 / .pi - azimut0).truncatingRemainder(dividingBy: 360)
        if az < 0 { az += 360 }
        return SIMD2(elev, az)
    }

    func secteur(_ dir: simd_float3) -> Int? {
        let a = angles(dir)
        var debut = 0
        for b in Couverture.bandes {
            if a.x >= b.min && a.x < b.max { return debut + min(b.n - 1, Int(a.y / 360 * Float(b.n))) }
            debut += b.n
        }
        return nil
    }

    var manquants: Int { compte.filter { $0 < Reglages.photosParSecteur }.count }
}

/// Cadran vu du dessus : centre = sommet de la tête, bord = 30° sous l'horizontale ; haut = face.
/// Rouge : secteur vide ; vert : assez de photos. Point blanc : position actuelle du téléphone.
struct Cadran: View {
    let couverture: Couverture
    let position: SIMD2<Float>?

    var body: some View {
        Canvas { ctx, taille in
            let R = min(taille.width, taille.height) / 2 - 8
            let c = CGPoint(x: taille.width / 2, y: taille.height / 2)
            func rayon(_ elev: Float) -> CGFloat { CGFloat((90 - min(elev, 90)) / 120) * R }
            func point(_ r: CGFloat, _ deg: Double) -> CGPoint {
                CGPoint(x: c.x + r * CGFloat(cos(deg * .pi / 180)), y: c.y + r * CGFloat(sin(deg * .pi / 180)))
            }
            let cible = Double(max(1, Reglages.photosParSecteur))
            var debut = 0
            for b in Couverture.bandes {
                for k in 0..<b.n {
                    let a0 = Double(k) * 360 / Double(b.n) - 90
                    let a1 = Double(k + 1) * 360 / Double(b.n) - 90
                    var p = Path()
                    for i in 0...8 {
                        let q = point(rayon(b.max), a0 + (a1 - a0) * Double(i) / 8)
                        if i == 0 { p.move(to: q) } else { p.addLine(to: q) }
                    }
                    for i in stride(from: 8, through: 0, by: -1) {
                        p.addLine(to: point(rayon(b.min), a0 + (a1 - a0) * Double(i) / 8))
                    }
                    p.closeSubpath()
                    let f = min(1, Double(couverture.compte[debut + k]) / cible)
                    ctx.fill(p, with: .color(Color(hue: f * 0.33, saturation: 0.85, brightness: 0.85).opacity(0.75)))
                    ctx.stroke(p, with: .color(.white.opacity(0.6)), lineWidth: 1)
                }
                debut += b.n
            }
            ctx.draw(Text("face").font(.caption2).foregroundColor(.white), at: CGPoint(x: c.x, y: 4))
            if let pos = position {
                let q = point(min(rayon(pos.x), R + 6), Double(pos.y) - 90)
                ctx.fill(Path(ellipseIn: CGRect(x: q.x - 6, y: q.y - 6, width: 12, height: 12)), with: .color(.white))
            }
        }
    }
}
