import ARKit
import SwiftUI

@main
struct CaptureEEGApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct VueCamera: UIViewRepresentable {
    let session: ARSession
    func makeUIView(context: Context) -> ARSCNView {
        let v = ARSCNView()
        v.session = session
        return v
    }
    func updateUIView(_ uiView: ARSCNView, context: Context) {}
}

struct ContentView: View {
    @StateObject private var cap = Capture()
    @State private var reglages = false

    var body: some View {
        ZStack {
            VueCamera(session: cap.session).ignoresSafeArea()
            VStack(spacing: 12) {
                HStack {
                    Text(cap.reglagesCamera).font(.caption)
                    Spacer()
                    Button("Réglages") { reglages = true }.disabled(cap.enCours)
                }
                .padding(10)
                .background(.black.opacity(0.5))

                Text(cap.message)
                    .font(.title3.bold())
                    .multilineTextAlignment(.center)
                    .padding(12)
                    .background((cap.alerte ? Color.red : Color.green).opacity(0.75))
                    .cornerRadius(12)
                    .padding(.horizontal)
                if cap.enCours, let d = cap.distanceCm {
                    Text("\(d) cm").font(.title2.monospacedDigit())
                }
                Spacer()
                HStack(alignment: .bottom) {
                    Cadran(couverture: cap.couverture, position: cap.enCours ? cap.position : nil)
                        .frame(width: 190, height: 190)
                        .background(.black.opacity(0.35))
                        .clipShape(Circle())
                    Spacer()
                    VStack(spacing: 10) {
                        Text("\(cap.nbPhotos) photos").font(.headline)
                        Button(cap.enCours ? "Terminer" : "Démarrer") {
                            if cap.enCours { cap.terminer() } else { cap.demarrer() }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(cap.enCours ? .red : .blue)
                        .controlSize(.large)
                    }
                }
                .padding()
            }
            .foregroundColor(.white)
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            cap.lancerSession()
        }
        .sheet(isPresented: $reglages, onDismiss: { cap.appliquerExposition() }) { ReglagesView() }
    }
}
