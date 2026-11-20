import SwiftUI

/// Réglages modifiables dans l'app (UserDefaults), lus au moment où on s'en sert.
enum Reglages {
    static let defauts: [String: Double] = [
        "vitesse": 250,          // temps de pose : 1/vitesse s
        "iso": 400,
        "distMin": 20,           // distance caméra – surface de la tête, cm
        "distMax": 45,
        "rayonTete": 10,         // rayon approximatif tête + casque, cm
        "distanceDepart": 35,    // distance au front quand on touche Démarrer, cm
        "photosParSecteur": 3,
        "intervalle": 0.5,       // s minimum entre deux photos
        "vitesseMax": 30,        // °/s autour de la tête au-delà : flou probable
        "viseeMax": 25,          // ° entre l'axe de la caméra et le centre de la tête
    ]

    static func v(_ k: String) -> Double { UserDefaults.standard.object(forKey: k) as? Double ?? defauts[k]! }

    static var vitesse: Double { v("vitesse") }
    static var iso: Double { v("iso") }
    static var distMin: Float { Float(v("distMin")) }
    static var distMax: Float { Float(v("distMax")) }
    static var rayonTete: Float { Float(v("rayonTete")) }
    static var distanceDepart: Float { Float(v("distanceDepart")) }
    static var photosParSecteur: Int { Int(v("photosParSecteur")) }
    static var intervalle: Double { v("intervalle") }
    static var vitesseMax: Float { Float(v("vitesseMax")) }
    static var viseeMax: Float { Float(v("viseeMax")) }
    static var tous: [String: Double] { Dictionary(uniqueKeysWithValues: defauts.keys.map { ($0, v($0)) }) }
}

struct ReglagesView: View {
    @AppStorage("vitesse") var vitesse = 250.0
    @AppStorage("iso") var iso = 400.0
    @AppStorage("distMin") var distMin = 20.0
    @AppStorage("distMax") var distMax = 45.0
    @AppStorage("rayonTete") var rayonTete = 10.0
    @AppStorage("distanceDepart") var distanceDepart = 35.0
    @AppStorage("photosParSecteur") var photosParSecteur = 3.0
    @AppStorage("intervalle") var intervalle = 0.5
    @AppStorage("vitesseMax") var vitesseMax = 30.0
    @AppStorage("viseeMax") var viseeMax = 25.0
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Caméra") {
                    Stepper("Temps de pose : 1/\(Int(vitesse)) s", value: $vitesse, in: 30...1000, step: 10)
                    Stepper("ISO : \(Int(iso))", value: $iso, in: 25...3200, step: 25)
                }
                Section("Distance à la tête (cm)") {
                    Stepper("Minimum : \(Int(distMin))", value: $distMin, in: 5...100)
                    Stepper("Maximum : \(Int(distMax))", value: $distMax, in: 10...150)
                    Stepper("Rayon tête + casque : \(Int(rayonTete))", value: $rayonTete, in: 6...15)
                    Stepper("Distance au départ : \(Int(distanceDepart))", value: $distanceDepart, in: 10...100)
                }
                Section("Couverture") {
                    Stepper("Photos par secteur : \(Int(photosParSecteur))", value: $photosParSecteur, in: 1...10)
                    Stepper(String(format: "Intervalle : %.1f s", intervalle), value: $intervalle, in: 0.2...3, step: 0.1)
                    Stepper("Vitesse max : \(Int(vitesseMax)) °/s", value: $vitesseMax, in: 5...120, step: 5)
                    Stepper("Visée max : \(Int(viseeMax)) °", value: $viseeMax, in: 5...60, step: 5)
                }
                Button("Valeurs par défaut") {
                    for k in Reglages.defauts.keys { UserDefaults.standard.removeObject(forKey: k) }
                }
            }
            .navigationTitle("Réglages")
            .toolbar { Button("OK") { dismiss() } }
        }
    }
}
