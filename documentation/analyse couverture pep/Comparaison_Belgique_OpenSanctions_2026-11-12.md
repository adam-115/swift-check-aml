# PEP Belgique : SwiftCheck AML et OpenSanctions

État au 12 novembre 2026.

**Sources des chiffres**
- SwiftCheck AML : base `swift_check_2` (lecture seule) et datasets publiés.
- OpenSanctions : page pays Belgique relue le 12/11/2026 (4 jeux de données, 2 354 PEP) et recherche publique pour le test de présence. Le détail par catégorie (absent, partiel) vient de l'analyse du 3 novembre 2026 et n'a pas été revérifié.

## En bref

| | SwiftCheck AML | OpenSanctions |
|---|---|---|
| Jeux de données PEP pour la Belgique | 24 plugins belges | 4 : Chambre (651), Sénat (126), Parlement flamand (125), Parlement de Wallonie (76) |
| Total de personnes | à ne pas comparer (périmètres différents) | 2 354 |
| Hors parlements | gouvernements, juridictions, Banque nationale, Cour des comptes, diplomates, entreprises publiques, partis, organisations internationales, élus locaux | Wikidata uniquement |

## Test de présence (petit test à la main, pas une mesure statistique)

| Échantillon | Taille | SwiftCheck AML | OpenSanctions |
|---|---|---|---|
| A. Personnalités connues, choisies par moi (gouvernement, parlement, chefs de parti, banque nationale, régions, élus locaux, UE) | 21 | 19 (90 %) ; 18 si on exige la bonne fonction | 20 (95 %) |
| C. Tirage au hasard dans nos données (3 dirigeants d'entreprises publiques, 3 ambassadeurs, 2 membres du Conseil d'État, 2 bourgmestres) | 10 | 10 (par construction) | 2 (20 %), dont 1 ressemblance douteuse |

**Sur les personnalités connues, OpenSanctions fait au moins aussi bien que nous.** Wikidata les couvre presque toutes, y compris les chefs de parti et les ministres régionaux. **Sur les personnes moins visibles, l'écart est net :** OpenSanctions ne connaît ni les diplomates, ni les présidents du Conseil d'État, ni les administrateurs d'entreprises publiques de l'échantillon, et seulement 2 des 10 personnes tirées de nos données.

## Nos manques, et ce qu'ils révèlent

**Dans l'échantillon A**
- **Adrien Dolimont** (ministre-président de la Wallonie) : absent de nos données. C'est un vrai manque de couverture (voir ci-dessous).
- **Luc Lavrysen** (ancien président de la Cour constitutionnelle, président émérite depuis avril 2026 d'après les arrêts publiés) : absent chez nous comme chez OpenSanctions. Il est parti avant la première exécution de notre parser, donc jamais publié.
- **Jean-Jacques De Gucht** : trouvé seulement via d'autres jeux de données (partis, entreprises publiques, famille), pas dans notre jeu du Sénat. À vérifier.

**Déclarations de couverture trop optimistes**
- `be_pep_regional_governments` est déclaré **complet** pour les gouvernements régionaux et communautaires, mais ne publie que **15 personnes** : le gouvernement flamand (9) et celui de la Fédération Wallonie-Bruxelles (6). Il manque les gouvernements de la Région wallonne, de la Région de Bruxelles-Capitale et de la Communauté germanophone. Les paramètres du parser n'ont pas d'adresse pour Wallonie et Bruxelles : la note prévoit de les compléter par le Moniteur belge.
- `be_pep_moniteur` ne publie que **6 personnes**, car il ne lit que les actes des **180 derniers jours**, et il est déclaré **complet** pour la Cour de cassation, les cours d'appel, le Conseil d'État, les ambassadeurs et les officiers généraux. Le détail réel est donc très inférieur : par exemple, le jeu du Conseil d'État ne contient que 4 personnes.
- `be_pep_eu_institutions` (autres fonctions UE) est déclaré complet mais n'a jamais été exécuté.

## Ce qui avantage chaque côté

**SwiftCheck AML**
- Sources officielles pour les diplomates, les entreprises publiques (234), les bourgmestres (670 en couche étendue), la Banque nationale, la Cour des comptes, la Cour constitutionnelle : des fonctions où OpenSanctions n'a presque rien.
- Traçabilité, détail par fonction, et licence qui nous appartient.

**OpenSanctions**
- Plus complet sur les personnalités connues, y compris les gouvernements régionaux, grâce à Wikidata.
- Couvre le reste du monde ; une seule intégration à maintenir.

## Limites

- Échantillons petits et choisis à la main : A est biaisé vers les personnes célèbres, C vers nos propres données. On ne sait pas combien de PEP d'OpenSanctions manquent chez nous.
- Les correspondances d'OpenSanctions se font sur le nom ; l'identité n'est pas confirmée (« Frieda Brepoels » n'est pas forcément « Frederika Brepoels »).
- Le statut de Lavrysen vient de la composition des arrêts de la Cour constitutionnelle de 2026, pas d'une annonce officielle.
