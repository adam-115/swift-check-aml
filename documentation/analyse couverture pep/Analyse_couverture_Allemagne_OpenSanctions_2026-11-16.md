# PEP Allemagne : couverture de SwiftCheck AML et comparaison avec OpenSanctions

État au 16 novembre 2026, après la relance complète de l'Allemagne le 15/11 au soir (exécutions 1183 à 1204).

**Sources des chiffres**
- **SwiftCheck AML, ce que voit le filtrage** : index yente local, 19 datasets `de_pep_*` publiés, **6 007 fiches Person** (un même élu peut figurer dans plusieurs datasets : Bundesrat et Landtag, Bundestag et parti…).
- **SwiftCheck AML, ce qui est déclaré et exécuté** : base `swift_check_2` (lecture seule) et fichiers publiés dans `/data` de yente.
- **OpenSanctions** : page pays Allemagne et tableaux EveryPolitician (`everypolitician.org/territories/de/`, onglets national, sous-national, autre), relus le 16/11.

## En bref

| | SwiftCheck AML | OpenSanctions |
|---|---|---|
| Personnes | 6 007 fiches, dont environ 4 100 en fonction | 10 051 PEP, 364 proches associés |
| Dont marquées en fonction | toutes, sauf les anciens députés du Bundestag (1 865) et quelques sortants | environ **2 550** : Bundestag (635), les 16 Landtage (1 866, via abgeordnetenwatch), gouvernement fédéral (≈ 17), 4 juges du BVerfG, président de la Bundesbank, 14 ambassadeurs |
| Sources | 20 parsers actifs, presque tous officiels ; Wikidata pour les partis et la famille ; abgeordnetenwatch (CC0) pour les Landtage | 3 jeux allemands (Bundestag, Bundesrat, abgeordnetenwatch) ; tout le reste vient de Wikidata, en général sans statut |
| Taux déclaré | **21 fonctions complètes sur 35 (60 %)**, dont 2 déclarées complètes à tort | non applicable |
| Taux mesuré (21 fonctions dont on connaît le nombre de titulaires) | **≈ 3 115 sur 4 188 (74 %)** | parlements fédéral et régionaux, gouvernement fédéral |

**À retenir.**
- Sur le Bundestag, les deux bases sont équivalentes. Sur **les Landtage, OpenSanctions est devant** (1 866 contre ≈ 1 518 justes) : même source qu'eux, mais nous sommes bloqués par la limite de débit d'abgeordnetenwatch (HTTP 429) sur Bade-Wurtemberg, Rhénanie-du-Nord-Westphalie et Hambourg.
- Partout ailleurs (Bundesrat, gouvernements des Länder, secrétaires d'État, cours constitutionnelles des Länder, cours des comptes, Bundesbank, BaFin, diplomates, entreprises publiques, maires, partis), OpenSanctions a peu ou rien avec le statut « en fonction ».
- **Nos trois gros manques** : les juges des 5 cours fédérales suprêmes (≈ 350, seulement les présidents), les **Landräte (290, aucun)**, et les officiers généraux (7 parsers Défense codés mais jamais exécutés).

## 1. Notre couverture, fonction par fonction

« Lu » = ce que la dernière exécution a réellement lu à la source. « Publié » = ce que voit yente (inclut ce que garde la rétention de 12 mois).

| Cat. | Fonction | Déclaré | Attendu | Lu le 15/11 | Juste et publié | Source / remarque |
|---|---|---|---|---|---|---|
| DE-a | Président fédéral | Complet | 1 | 1 | 1 | officielle |
| DE-a | Chancelier et ministres fédéraux | Complet | 17 | 18 | 18 | bundesregierung.de |
| DE-a | Secrétaires d'État (parlementaires et fonctionnaires) | Complet | 70 | 65 (+9 sortis) | 65 | Datenhandbuch du Bundestag arrêté au 12/08/2026 (96 jours) |
| DE-a | Ministres-présidents des Länder | Complet | 16 | 16 | 16 | Bundesrat |
| DE-a | Ministres et sénateurs des Länder | Complet | 53 (sous-estimé : ≈ 150 réels) | 161 | 161 | Bundesrat (membres et suppléants) |
| | dont parser `land_governments` (nouveau) | | | 146 | **0** | ⚠️ publié le 15/11 à 23 h 33 mais **pas indexé par yente** ; 22 pages en échec (Sarre, Berlin) ; 4 ministres-présidents sur 16 |
| DE-a | Anciens membres de gouvernement | Partiel | | 9 | 9 | anciens secrétaires d'État seulement ; rien pour les Länder |
| DE-b | Bundestag | Complet | 630 | 630 | 630 | fichier officiel arrêté au 29/04/2026 (200 jours) ; ⚠️ le contrôle avec l'index live ne reconnaît aucun député (« 630 absents du fichier ») : rapprochement à corriger |
| DE-b | Anciens députés du Bundestag | Complet | | 1 865 | 1 865 | sortis dans la durée retenue |
| DE-b | Bundesrat (membres et suppléants) | Complet | 149 | 180 | 180 | officielle |
| DE-b | Landtage | Complet ⚠️ | 1 866 | 1 397 (13 parlements) | ≈ 1 518 | abgeordnetenwatch ; voir section 2 : NRW absent (195), BW périmé (200 anciens au lieu de 157), Hambourg par la rétention (121) |
| DE-c | Direction fédérale des partis | Complet | | 211 en fonction (147 partis) | 211 | Wikidata |
| DE-d | Cour constitutionnelle fédérale | Complet | 16 | 16 | 16 | ⚠️ 20 personnes et 30 mandats publiés : doublons d'identifiants et anciens juges gardés « en fonction » sans date |
| DE-d | BGH, BVerwG, BFH, BAG, BSG | Partiel | ≈ 350 | 16 (présidents et vice-présidents) | 16 | **5 %** ; plan en cours : listes Wikipédia DE + recoupement avec les plans de répartition officiels |
| DE-d | Cours constitutionnelles des Länder | Complet | 150 | 161 | 161 | officielles, sans les suppléants |
| DE-e | Bundesrechnungshof | Partiel | 16 | 3 | 4 | président, vice-président, 2 membres |
| DE-e | Cours des comptes des Länder | Partiel | 24 | 0 | 16 | présidents lus le 05/11, gardés par la rétention ; ils disparaîtront dans 12 mois si la lecture n'est pas réparée |
| DE-f | Bundesbank (directoire) | Complet | 6 | 6 | 6 | officielle |
| DE-f | BaFin (Direktorium) | Complet | 7 | 7 | 7 | officielle ; Verwaltungsrat non couvert |
| DE-g | Ambassadeurs et chargés d'affaires | Complet | 150 (sous-estimé) | 216 | 216 | Auswärtiges Amt (+47 sortants gardés) |
| DE-g | Attachés de défense | aucun parser | | | 0 | |
| DE-h | Entreprises publiques | Partiel | non chiffré | 48 (6 entreprises) | 48 | DB, Telekom, KfW, DFS, Bundesdruckerei, Uniper ; DHL illisible, Uniper en 403 ; conseils de surveillance absents sauf Telekom |
| DE-i | Organisations internationales en Allemagne | Partiel | 80 | 24 (3 organisations) | 24 | ITLOS, ESO, EMBL ; EPO, EUMETSAT, OCCAR, CCNUCC en 404 ou sans nom |
| AMLR | Maires des communes de 50 000 hab. et plus | Complet | 200 | ≈ 180 | ≈ 180 | 206 mandats sur 182 villes : doublons (10 maires pour Göttingen) |
| AMLR | **Landräte** (arrondissements) | Complet ⚠️ | 290 | **0** | **0** | déclaré complet à tort : le parser ne produit que des Oberbürgermeister |
| EU | Eurodéputés élus en Allemagne | Complet | 96 | 96 | 96 | officielle ; photos non publiées (colonne `licence` trop courte) |
| EU | Commissaire européenne | Complet | 1 | 1 | 1 | |
| EU | Autres fonctions UE | aucun parser | | | 0 | |
| Défense | Officiers généraux et amiraux | **pas de fonction** | ≈ 200 | jamais exécuté | 0 | 7 parsers `de_pep_defence_*` en base depuis les derniers commits, jamais lancés ni reliés à une fonction |
| | Lobbyregister | pas de fonction | | jamais exécuté | 0 | parser en base, jamais lancé |
| RCA | Famille | Partiel | | 131 proches (77 PEP) | 131 | Wikidata |
| RCA | Proches associés | aucun parser | | | 0 | |

**Taux mesuré** sur les 21 fonctions chiffrées : **≈ 3 115 titulaires justes sur 4 188 attendus (74 %)**. Sans les cours fédérales et les Landräte (les deux manques structurels), on est à **87 %**. Avec les Landtage réparés en plus, à **97 %**.

## 2. Problèmes qui faussent nos chiffres aujourd'hui

1. **Landtage, limite de débit d'abgeordnetenwatch.** Trois parlements répondent HTTP 429 à chaque exécution :
   - Rhénanie-du-Nord-Westphalie : **aucun député publié** (195 manquent) ;
   - Bade-Wurtemberg : les 200 fiches publiées viennent de la toute première exécution (04/11) et sont gardées par la rétention ; le Landtag élu en mars 2026 compte 157 députés ; nos fiches sont donc périmées ;
   - Hambourg : 121 députés gardés par la rétention, probablement encore justes.
   Il faut espacer les appels (pause et reprise sur 429), puis relancer.
2. **Gouvernements des Länder pas indexés.** Le fichier `de_pep_land_governments` est dans `/data` et dans le manifeste de yente, mais aucun index Elasticsearch n'existe : yente n'a pas été réindexé après la publication du 15/11 (même problème qu'en Belgique, `POST /updatez`). Le parser ne trouve par ailleurs que 4 ministres-présidents sur 16 et 22 pages sont en échec.
3. **Bundestag.** Le rapprochement avec l'index live échoue entièrement (630 sur 630 non reconnus) : on ne détecte donc pas les départs et arrivées depuis le 29/04/2026.
4. **Doublons et anciens titulaires « en fonction »** : BVerfG (20 personnes pour 16 sièges, sans dates), maires (Göttingen ×10), ambassadeurs (jusqu'à 3 par poste). Pas de faux négatif pour le filtrage, mais un statut faux et des doublons pour l'analyste.
5. **Deux fonctions déclarées complètes à tort** : Landräte (aucun) et Landtage (NRW absent). À passer en PARTIAL par un nouveau changeSet en attendant la correction.

## 3. Comparaison avec OpenSanctions

| Fonction | OpenSanctions en fonction | (terminés / statut inconnu) | SwiftCheck AML juste |
|---|---|---|---|
| Président fédéral | 1 | | 1 |
| Gouvernement fédéral | ≈ 17 (source CIA World Leaders) | nombreux anciens | 18 |
| Secrétaires d'État | 0 | 11 / 104 | 65 |
| Bundestag | 635 | 1 264 / 35 | 630 (+1 865 anciens) |
| Bundesrat | 0 | 29 / 201 (+ 51 / 81 suppléants) | 180 |
| Ministres-présidents des Länder | 0 | 8 / 13 | 16 |
| Ministres des Länder | 0 | quelques / ≈ 40 | 161 |
| **Landtage (16)** | **1 866** | Wikidata, des milliers sans statut | **≈ 1 518** |
| Partis (direction) | 0 | quelques | 211 |
| BVerfG | 4 | 27 / 1 | 16 |
| Autres cours fédérales | 0 | 3 / 12 | 16 |
| Cours constitutionnelles des Länder | 0 | | 161 |
| Cours des comptes | 0 | | 20 |
| Bundesbank, BaFin | 1 | 2 | 13 |
| Ambassadeurs allemands | ≈ 14 | ≈ 600 terminés | 216 |
| Officiers généraux | 0 | ≈ 15 terminés | 0 |
| Entreprises publiques | 0 | 1 | 48 |
| Maires et Landräte | ≈ 3 | ≈ 100 sans statut | ≈ 180 maires, 0 Landrat |
| Eurodéputés | dans le jeu UE | | 96 |
| Proches | 364 proches associés | | 131 membres de famille |

**Lecture.**
- OpenSanctions a 10 051 PEP « liés à l'Allemagne », mais environ **2 550 seulement sont marqués en fonction**, et 1 866 d'entre eux sont des députés de Landtag. Le reste est de l'historique Wikidata (anciens ambassadeurs, anciens députés, évêques, recteurs…) dont le statut est le plus souvent inconnu.
- **Notre seul retard réel sur des titulaires en fonction est le Landtag** (≈ 350 députés), et il vient d'un défaut technique de notre côté, pas de la source.
- Nous sommes nettement devant sur tout ce que la loi allemande (GwG § 1 Abs. 12, liste nationale des fonctions PEP) et l'AMLR exigent hors parlements : Bundesrat, gouvernements des Länder, secrétaires d'État, juges constitutionnels, cours des comptes, banque centrale et superviseur, diplomates, entreprises publiques, maires.
- Sur l'historique (anciens PEP au-delà de 12 mois), OpenSanctions garde davantage. Ce n'est pas exigé par le GwG, qui demande la vigilance pendant au moins 12 mois après la fin de la fonction.

## 4. Ce qu'il faut faire, par ordre de rendement

| Priorité | Action | Gain |
|---|---|---|
| 1 | Landtage : pause et reprise sur HTTP 429, relance ; corriger BW | ≈ +350 députés justes, −200 fiches périmées |
| 2 | Réindexer yente (`/updatez`) pour publier `land_governments` ; réparer les pages Sarre et Berlin et la lecture des ministres-présidents | ministres et secrétaires d'État des Länder |
| 3 | Lancer les 7 parsers Défense et créer la fonction « officiers généraux et amiraux » (lot 1) | ≈ 200 |
| 4 | Juges des cours fédérales (lot 1, en cours) : Wikipédia DE + recoupement des plans officiels | ≈ +330 |
| 5 | Landräte : ajouter la lecture des arrondissements au parser `local_executives` | +290 |
| 6 | Bundesrechnungshof complet (3 → 16) et lecture des cours des comptes des Länder | +12, et éviter la perte des 16 présidents dans 12 mois |
| 7 | ChangeSet de correction des déclarations (Landräte et Landtage en PARTIAL, attendus des ministres des Länder et des ambassadeurs) | taux déclaré honnête |
| 8 | Doublons BVerfG, maires, ambassadeurs ; rapprochement Bundestag live ; colonne `licence` de `pep_media` | qualité |

Avec les priorités 1 à 5, le taux mesuré passerait d'environ 74 % à environ 97 %.
