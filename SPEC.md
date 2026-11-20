# Spécification commune (iOS et Android)

Les deux apps appliquent les mêmes règles et écrivent le même format, pour que le pipeline
de reconstruction (COLMAP, détection et nommage des électrodes) lise l'une ou l'autre sans distinction.

## Guidage

1. Au départ, l'expérimentateur tient le téléphone à `distanceDepart` cm du front, en visant le
   centre de la tête, et touche **Démarrer**. Le centre de la tête est placé à
   `distanceDepart + rayonTete` cm devant la caméra. Pas de capteur de profondeur requis.
2. À chaque image du suivi AR, dans cet ordre (le premier problème trouvé est affiché) :
   - suivi AR perdu → « Suivi perdu » ;
   - distance caméra – surface de la tête (`|caméra − centre| − rayonTete`) hors de
     [`distMin`, `distMax`] → « Éloigne-toi » / « Rapproche-toi » ;
   - angle entre l'axe optique et la direction du centre > `viseeMax` → « Vise le centre » ;
   - vitesse angulaire de la caméra autour du centre (lissée, facteur 0,8) > `vitesseMax` °/s → « Trop vite » ;
   - élévation < −30° → « Trop bas » ;
   - secteur déjà plein → « Zone couverte : va vers le rouge » ;
   - sinon, une photo pleine résolution est prise si la précédente date de plus de `intervalle` s.
3. Secteurs (élévation de la caméra vue du centre de la tête, gravité vers le bas ; azimut 0 = départ) :

   | bande d'élévation | secteurs d'azimut |
   |---|---|
   | −30° à 0° (menton, nuque, sous les oreilles) | 12 |
   | 0° à 30° | 12 |
   | 30° à 60° | 8 |
   | 60° à 90° (sommet, vu sous plusieurs angles) | 4 |

   Un secteur est plein à `photosParSecteur` photos.
4. Exposition manuelle imposée : temps de pose 1/`vitesse` s, ISO `iso` (bornés par la caméra).

Valeurs par défaut : vitesse 250, iso 400, distMin 20, distMax 45, rayonTete 10, distanceDepart 35,
photosParSecteur 3, intervalle 0,5, vitesseMax 30, viseeMax 25.

## Sortie : un dossier `capture_AAAAMMJJ_HHMMSS/`

- `img_0001.jpg`, … : photos pleine résolution, orientation du capteur (paysage), JPEG qualité 0,92.
- `poses.jsonl` : une ligne JSON par photo, avec :
  - `image` ; `t` (s, horloge du suivi) ; `largeur`, `hauteur` (pixels) ;
  - `fx`, `fy`, `cx`, `cy` (pixels de l'image enregistrée) ;
  - `transform_colonnes` : matrice 4×4 caméra → monde, 16 nombres, colonne par colonne,
    en mètres ; repère monde y vers le haut ; axes caméra x droite, y haut, z arrière
    (convention ARKit ; l'app Android convertit vers cette convention) ;
  - `exposition_s` ; `secteur`.
- `session.json` : version de l'app, système, centre de la tête (m), réglages utilisés.

Aucune donnée ne quitte le téléphone : pas de réseau, pas de cloud.
