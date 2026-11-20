# Capture EEG

App de prise de vue guidée pour reconstruire en 3D une tête portant un casque EEG,
puis localiser et nommer les électrodes par photogrammétrie.

*Guided capture app (iOS, Android planned) for photogrammetric localisation of EEG electrodes:
real-time AR tracking, coverage map around the head, distance and speed guidance,
automatic full-resolution photos with camera poses.*

## Ce que fait l'app

- Suivi de la position du téléphone en temps réel (ARKit), sans capteur LiDAR.
- Consignes à l'écran : rapproche-toi, éloigne-toi, vise le centre, trop vite.
- Cadran de couverture vu du dessus : chaque secteur passe du rouge au vert quand il a assez de photos.
- Photos automatiques en pleine résolution, exposition verrouillée (1/250 s, ISO 400 par défaut),
  chacune enregistrée avec la position et les paramètres de la caméra.
- Tout reste sur le téléphone.

Règles et format de sortie : [SPEC.md](SPEC.md).

## Installer sur iPhone (sans Mac)

1. Installer [SideStore](https://sidestore.io) (ou [AltStore](https://altstore.io) avec AltServer sur le PC),
   avec un identifiant Apple gratuit.
2. Télécharger `CaptureEEG.ipa` depuis la release
   [ios-dernier](../../releases/tag/ios-dernier), puis l'ouvrir avec SideStore.
3. La signature gratuite expire au bout de 7 jours : la rafraîchir dans SideStore.

iOS 16 minimum.

## Utiliser

1. Réglages : vérifier le temps de pose et l'ISO.
2. Téléphone à environ 35 cm du front, visant le centre de la tête : **Démarrer**.
3. Tourner lentement autour de la tête jusqu'à ce que le cadran soit vert, sans oublier
   le sommet (centre du cadran), la nuque et le dessous des oreilles (bord du cadran).
4. **Terminer**. Les photos sont dans l'app Fichiers › Sur mon iPhone › Capture EEG ;
   les transférer sur le PC (LocalSend par exemple).

## Développer

Le projet Xcode est généré par [XcodeGen](https://github.com/yonaskolb/XcodeGen) à partir de
`ios/project.yml`. GitHub Actions compile l'app à chaque envoi sur `main` (aucun Mac nécessaire).

## Licence

MIT
