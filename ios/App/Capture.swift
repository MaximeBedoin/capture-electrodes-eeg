import ARKit
import AVFoundation
import CoreImage
import ImageIO
import SwiftUI
import simd

/// Suivi ARKit, guidage (distance, visée, vitesse, couverture) et prise de photos automatique.
/// Sortie : Documents/capture_<date>/ avec img_NNNN.jpg, poses.jsonl (une ligne par photo), session.json.
final class Capture: NSObject, ObservableObject, ARSessionDelegate {
    let session = ARSession()
    @Published var message = "Cale la tête dans la zone claire, téléphone à environ 30 cm du front, puis Démarrer"
    @Published var alerte = false
    @Published var distanceCm: Int?
    @Published var enCours = false
    @Published var couverture = Couverture()
    @Published var position: SIMD2<Float>?
    @Published var nbPhotos = 0
    @Published var reglagesCamera = ""
    @Published var tete: CercleTete?
    var tailleVue: CGSize = .zero   // taille de la vue caméra à l'écran, fixée par ContentView

    private var centre: simd_float3?
    private var dossier: URL?
    private var dossierNom = ""
    private var journal: FileHandle?
    private var derniereCapture: TimeInterval = 0
    private var capturant = false
    private var dernierePose: (t: TimeInterval, dir: simd_float3)?
    private var vitesse: Float = 0
    private let ecriture = DispatchQueue(label: "ecriture")
    private let contexte = CIContext()

    // MARK: session et exposition

    func lancerSession() {
        let conf = ARWorldTrackingConfiguration()
        if let f = ARWorldTrackingConfiguration.recommendedVideoFormatFor4KResolution {
            conf.videoFormat = f
        } else if let f = ARWorldTrackingConfiguration.recommendedVideoFormatForHighResolutionFrameCapturing {
            conf.videoFormat = f
        }
        conf.isAutoFocusEnabled = true
        session.delegate = self
        session.run(conf)
        // ARKit règle la caméra au lancement : on impose l'exposition juste après
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.appliquerExposition() }
    }

    func appliquerExposition() {
        guard let dev = ARWorldTrackingConfiguration.configurableCaptureDeviceForPrimaryCamera else {
            reglagesCamera = "exposition manuelle indisponible"
            return
        }
        do {
            try dev.lockForConfiguration()
            let fmt = dev.activeFormat
            let voulu = CMTimeMakeWithSeconds(1.0 / Reglages.vitesse, preferredTimescale: 1_000_000)
            let duree = CMTimeClampToRange(voulu, range: CMTimeRange(start: fmt.minExposureDuration, end: fmt.maxExposureDuration))
            let iso = min(max(Float(Reglages.iso), fmt.minISO), fmt.maxISO)
            dev.setExposureModeCustom(duration: duree, iso: iso, completionHandler: nil)
            dev.unlockForConfiguration()
            reglagesCamera = String(format: "1/%.0f s · ISO %.0f", 1 / duree.seconds, iso)
        } catch {
            reglagesCamera = "exposition refusée : \(error.localizedDescription)"
        }
    }

    // MARK: démarrer / terminer

    static func pose(_ T: simd_float4x4) -> (position: simd_float3, avant: simd_float3) {
        // ARKit : la caméra regarde vers −z
        (simd_make_float3(T.columns.3), simd_normalize(-simd_make_float3(T.columns.2)))
    }

    func demarrer() {
        guard let frame = session.currentFrame else { dire("Caméra pas prête", alerte: true); return }
        let p = Capture.pose(frame.camera.transform)
        let c = p.position + p.avant * (Reglages.distanceDepart + Reglages.rayonTete) / 100
        centre = c
        var cv = Couverture()
        let d = simd_normalize(p.position - c)
        cv.azimut0 = atan2(d.x, d.z) * 180 / .pi
        couverture = cv
        nbPhotos = 0
        vitesse = 0
        dernierePose = nil

        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        dossierNom = "capture_" + f.string(from: Date())
        let doc = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(dossierNom)
        try? FileManager.default.createDirectory(at: doc, withIntermediateDirectories: true)
        let fichierPoses = doc.appendingPathComponent("poses.jsonl")
        FileManager.default.createFile(atPath: fichierPoses.path, contents: nil)
        journal = try? FileHandle(forWritingTo: fichierPoses)
        dossier = doc
        let meta: [String: Any] = [
            "version_app": "0.1",
            "systeme": "iOS \(UIDevice.current.systemVersion)",
            "centre_tete_m": [c.x, c.y, c.z],
            "reglages": Reglages.tous,
            "camera": reglagesCamera,
            "convention": "Repère monde ARKit : mètres, y vers le haut (gravité). transform_colonnes = caméra vers monde, "
                + "4 colonnes de 4 ; axes caméra x droite, y haut, z arrière (la caméra regarde vers -z). "
                + "Intrinsèques en pixels de l'image enregistrée (orientation du capteur, paysage).",
        ]
        if let data = try? JSONSerialization.data(withJSONObject: meta, options: [.prettyPrinted, .sortedKeys]) {
            try? data.write(to: doc.appendingPathComponent("session.json"))
        }
        appliquerExposition()
        enCours = true
    }

    func terminer() {
        enCours = false
        tete = nil
        let j = journal
        journal = nil
        ecriture.async { try? j?.close() }
        dire("Enregistré : \(dossierNom), \(nbPhotos) photos (app Fichiers › Sur mon iPhone › Capture EEG)", alerte: false)
    }

    // MARK: guidage, à chaque image

    private func dire(_ m: String, alerte a: Bool) {
        if message != m { message = m }
        if alerte != a { alerte = a }
    }

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard enCours, let c = centre else { return }
        let p = Capture.pose(frame.camera.transform)
        let v = p.position - c
        let dCentre = simd_length(v)
        let dir = v / dCentre
        let dist = dCentre * 100 - Reglages.rayonTete
        if distanceCm != Int(dist) { distanceCm = Int(dist) }
        position = couverture.angles(dir)

        // vitesse angulaire de la caméra autour de la tête, lissée
        let t = frame.timestamp
        if let d = dernierePose, t > d.t {
            let ang = acos(max(-1, min(1, simd_dot(dir, d.dir)))) * 180 / .pi / Float(t - d.t)
            vitesse = 0.8 * vitesse + 0.2 * ang
        }
        dernierePose = (t, dir)
        let visee = acos(max(-1, min(1, simd_dot(p.avant, -dir)))) * 180 / .pi

        // contour de la tête projeté à l'écran, et taille qu'il aurait à la distance idéale
        if visee < 80 && tailleVue != .zero {
            let droite = simd_normalize(simd_make_float3(frame.camera.transform.columns.0))
            let pc = frame.camera.projectPoint(c, orientation: .portrait, viewportSize: tailleVue)
            let pb = frame.camera.projectPoint(c + droite * Reglages.rayonTete / 100, orientation: .portrait,
                                               viewportSize: tailleVue)
            let r = hypot(pb.x - pc.x, pb.y - pc.y)
            tete = CercleTete(centre: pc, rayon: r,
                              cible: r * CGFloat(dCentre * 100 / (Reglages.distanceCible + Reglages.rayonTete)))
        } else {
            tete = nil
        }

        guard case .normal = frame.camera.trackingState else {
            dire("Suivi perdu : ralentis et garde le décor dans le champ", alerte: true); return
        }
        if dist < Reglages.distMin || dist > Reglages.distMax || visee > Reglages.viseeMax {
            dire("Cale les pointillés sur la zone claire", alerte: true); return
        }
        if vitesse > Reglages.vitesseMax { dire("Trop vite", alerte: true); return }
        guard let s = couverture.secteur(dir) else { dire("Trop bas : remonte", alerte: true); return }
        let manquants = couverture.manquants
        if couverture.compte[s] >= Reglages.photosParSecteur {
            dire(manquants == 0 ? "Couverture complète ✓ tu peux terminer"
                                : "Zone couverte : va vers le rouge (\(manquants) secteurs)", alerte: false)
            return
        }
        dire("Bien : continue doucement (\(manquants) secteurs à faire)", alerte: false)
        if !capturant && t - derniereCapture >= Reglages.intervalle {
            derniereCapture = t
            capturer()
        }
    }

    // MARK: photo pleine résolution

    private func capturer() {
        capturant = true
        session.captureHighResolutionFrame { [weak self] hf, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                guard let hf, let c = self.centre, let journal = self.journal, let dossier = self.dossier,
                      let s = self.couverture.secteur(simd_normalize(Capture.pose(hf.camera.transform).position - c))
                else { self.capturant = false; return }
                self.nbPhotos += 1
                self.couverture.compte[s] += 1
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                let nom = String(format: "img_%04d.jpg", self.nbPhotos)
                let ligne = Capture.ligneJournal(hf, image: nom, secteur: s)
                let image = CIImage(cvPixelBuffer: hf.capturedImage)
                self.ecriture.async {
                    let options = [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.92]
                    if let data = self.contexte.jpegRepresentation(of: image, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                                                   options: options) {
                        try? data.write(to: dossier.appendingPathComponent(nom))
                        journal.write(ligne)
                    }
                    DispatchQueue.main.async { self.capturant = false }
                }
            }
        }
    }

    static func ligneJournal(_ f: ARFrame, image: String, secteur: Int) -> Data {
        let K = f.camera.intrinsics  // colonnes : K[colonne][ligne]
        let T = f.camera.transform
        let transform = [T.columns.0, T.columns.1, T.columns.2, T.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }
        let d: [String: Any] = [
            "image": image,
            "t": f.timestamp,
            "largeur": Int(f.camera.imageResolution.width),
            "hauteur": Int(f.camera.imageResolution.height),
            "fx": K[0][0], "fy": K[1][1], "cx": K[2][0], "cy": K[2][1],
            "transform_colonnes": transform,
            "exposition_s": f.camera.exposureDuration,
            "secteur": secteur,
        ]
        var data = (try? JSONSerialization.data(withJSONObject: d, options: [.sortedKeys])) ?? Data()
        data.append(0x0A)
        return data
    }
}
