# lv_pep_parliament – Saeima (Lettonie)

Site officiel `https://www.saeima.lv` : page « Deputāti » de chaque législature (`/lv/sasaukumi/N-saeima/deputati/`) puis la fiche de chaque député.

- Paramètre `compositions` : `15,14` par défaut. `monthsAfterEnd` : 12.
- Dates de mandat d'après l'**historique du mandat** de la fiche : « Apstiprināts » / « Atjaunots » ouvrent un mandat, « Izbeigušās pilnvaras » / « Nolicis… mandātu » le ferment. Un chef de groupe n'a pas de ligne « Deputāts » dans l'activité : l'historique est la seule source fiable.
- La page présente chaque député deux fois (liste et galerie) : dédoublonnée avant le téléchargement (239 fiches, environ 4 minutes à 1 s par page).
- Le `robots.txt` interdit `/api/` et `/debug/` ; `titania.saeima.lv` interdit tout. Seules les pages publiques sont lues.
- Ni naissance ni décès publiés : pas de couche historique (une personne décédée n'y serait pas écartée). Une personne est reconnue d'une législature à l'autre par son nom.
