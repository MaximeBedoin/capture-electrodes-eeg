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
     [`distMin`, `distMax`], ou angle entre l'axe optique et la direction du centre > `viseeMax`
     → « Cale les pointillés sur la zone claire » ;
   - vitesse angulaire de la caméra autour du centre (lissée, facteur 0,8) > `vitesseMax` °/s → « Trop vite » ;
   - élévation < −45° → « Trop bas » ;
   - secteur déjà plein → « Zone couverte : va vers le rouge » ;
   - sinon, une photo pleine résolution est prise si la précédente date de plus de `intervalle` s.
3. Secteurs (élévation de la caméra vue du centre de la tête, gravité vers le bas ; azimut 0 = départ) :

   | bande d'élévation | secteurs d'azimut |
   |---|---|
   | −45° à 0° (nuque, sous les oreilles, inion) | 24 |
   | 0° à 30° | 24 |
   | 30° à 60° | 16 |
   | 60° à 90° (sommet, vu sous plusieurs angles) | 8 |

   Un secteur est plein à `photosParSecteur` photos (`photosParSecteurBas` dans la bande basse, la plus mal
   reconstruite à l'essai du 20/11). Dans un secteur plein, une photo nettement plus nette remplace la moins nette. L'app iOS v0.1 suit encore
   l'ancien découpage (12/12/8/4 secteurs, −30°, 3 photos).
4. Cadrage visuel : l'écran est grisé sauf une zone claire centrale, de la taille qu'aurait la tête
   (sphère de rayon `rayonTete`) à `distanceCible` cm. Le contour de la tête, projeté depuis le suivi AR,
   est dessiné en pointillés : vert si son rayon est à moins de 20 % de celui de la zone et son centre
   à moins de 25 % du rayon du centre de l'écran, orange sinon.
5. Exposition manuelle imposée : temps de pose 1/`vitesse` s, ISO `iso` (bornés par la caméra).

Valeurs par défaut : vitesse 250, iso 400, distMin 20, distMax 35, distanceCible 27, rayonTete 10, distanceDepart 30,
photosParSecteur 2, photosParSecteurBas 2, intervalle 0,5, vitesseMax 30, viseeMax 25.

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
