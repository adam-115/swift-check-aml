# PEP Belgique : couverture de SwiftCheck AML et comparaison avec OpenSanctions

État au 15 novembre 2026, après la relance complète de la Belgique le matin même (exécutions 1153 à 1179). Remplace les chiffres du diagnostic du 12/11.

**Sources des chiffres**
- **SwiftCheck AML, ce que voit le filtrage** : index yente local, 24 datasets `be_pep_*`, **5 103 fiches**, soit environ **4 235 personnes distinctes** (nom + date de naissance).
- **SwiftCheck AML, ce qui est déclaré et exécuté** : base `swift_check_2` (lecture seule).
- **OpenSanctions** : page pays Belgique et tableau EveryPolitician par fonction (`everypolitician.org/territories/be/`, onglets national, sous-national, autre), relus le 15/11 ; les 4 jeux belges officiels (`data.opensanctions.org`, avec les dates de mandat) téléchargés le 15/11 ; leur recherche publique pour le test de présence.

## En bref

| | SwiftCheck AML | OpenSanctions |
|---|---|---|
| Personnes | ≈ 4 235 (5 103 fiches) | 2 354 PEP, 134 proches associés |
| Dont marquées en fonction | toutes, sauf les sortants gardés par la rétention | **449** : roi et gouvernement fédéral (21), Chambre (150), Sénat (72), parlements flamand (124) et wallon (75), gouverneur BNB, quelques autres |
| Sources officielles | 24 parsers ; Wikipédia ou Wikidata pour le roi, les partis (en partie), 3 gouvernements régionaux, le Parlement bruxellois, SHAPE, l'OEACP, la famille | 4 jeux (Chambre, Sénat, Parlement flamand, Parlement de Wallonie) ; tout le reste vient de Wikidata |
| Taux déclaré | **26 fonctions complètes sur 31 (84 %)** | non applicable |
| Taux mesuré (16 fonctions dont on connaît le nombre de titulaires) | **892 sur 902 publiés (99 %)**, mais seulement **689 sur 902 (76 %) confirmés par la dernière exécution** | parlements fédéraux et régionaux FL/WA seulement |

**À retenir.** Sur les parlements, les deux bases sont identiques. Partout ailleurs (juridictions, Conseil d'État, entreprises publiques, diplomates, organisations internationales, élus locaux, armée, Banque nationale), OpenSanctions a peu ou rien, et presque jamais avec le statut « en fonction ». Notre retard ne porte que sur l'historique : OpenSanctions garde les anciens élus partis depuis plus de 12 mois, ce que la loi belge ne demande pas.

**Mais deux problèmes faussent nos chiffres aujourd'hui** (section 2) : le Conseil d'État n'est publié que par la rétention, à cause d'un vieux jar chargé par le back, et le gouvernement bruxellois publié est le gouvernement sortant.

## 1. Notre couverture, fonction par fonction

« Publié » = ce que voit yente aujourd'hui. « Exécution » = ce que la dernière exécution a réellement lu à la source (le reste est gardé par la rétention).

| Cat. | Fonction | Déclaré | Publié | Exécution du 15/11 | Attendu | Source |
|---|---|---|---|---|---|---|
| BE-a | Roi | Complet | 1 | 1 | 1 | Wikidata, contrôlé par monarchie.be |
| BE-a | Gouvernement fédéral | Complet | 15 | 15 | 15 | officielle |
| BE-a | Gouvernements régionaux et communautaires | Complet | 35 | 35 | 35 | Flandre, FWB : officielles ; Wallonie, Bruxelles, germanophone : **Wikipédia** |
| | dont Bruxelles | | 8 | 8 | 8 | ⚠️ **gouvernement sortant** (Vervoort III, fini le 14/02/2026) ; le gouvernement Dilliès manque |
| BE-b | Chambre | Complet | 150 | 150 | 150 | officielle |
| BE-b | Sénat | Complet | 60 | 60 | 60 | officielle |
| BE-b | Parlement flamand | Complet | 126 | 124 | 124 | officielle (+ 2 sortants gardés) |
| BE-b | Parlement de Wallonie | Complet | 75 | 75 | 75 | officielle |
| BE-b | Parlement de la FWB | Complet | 94 | 94 | 94 | officielle |
| BE-b | Parlement bruxellois | Complet | 89 | 89 | 89 | **Wikipédia** (parlement.brussels injoignable) |
| BE-b | Parlement germanophone | Complet | 25 | **0** | 25 | officielle, mais « indisponible » le 15/11 : publié par la rétention |
| BE-c | Partis | Complet | 171 (30 partis) | 171 | non chiffré | Wikidata + déclarations Regimand (Cour des comptes) |
| BE-d | Cour constitutionnelle | Complet | 12 | 12 | 12 | officielle |
| BE-d | Cour de cassation | Complet | 59 | 59 | non chiffré | cass.be (historiques du siège et du parquet) + Moniteur ; sortis depuis moins de 5 ans inclus |
| BE-d | Cours d'appel et du travail | Partiel | quelques-uns | | plusieurs centaines | Moniteur, 180 jours |
| BE-d | Conseil d'État | Complet | 169 | **4** | 169 | ⚠️ vieux jar chargé (section 2) : publié par la rétention |
| BE-e | Banque nationale | Complet | 20 | 20 | non chiffré | officielle |
| BE-e | Cour des comptes | Complet | 10 | 10 | 12 | officielle |
| BE-f | Diplomates | Complet | 110 | 110 | non chiffré | officielle |
| BE-f | Officiers généraux | Partiel | 18 | **5** | 18 | mil.be : la page « structure de la Défense » répond 404 ; les 13 autres viennent de l'ancienne page embarquée, gardés par la rétention |
| BE-g | Entreprises publiques | Complet | 583 (25 entreprises) | 583 | non chiffré | Regimand + SFPI (234 le 12/11) |
| BE-h | Organisations internationales | Complet | 22 | 22 | non chiffré | officielles ; SHAPE et OEACP via **Wikipédia** |
| AMLR | Exécutifs locaux de 50 000 hab. et plus | Complet | 385 | 345 | ≈ 360 | LBLOD + Regimand (31 bourgmestres, 12 gouverneurs, 315 échevins, présidents de CPAS, députés provinciaux) |
| AMLR | Toutes les communes (couche étendue) | Partiel | 2 413 | 2 413 | | 668 bourgmestres, 13 gouverneurs, 2 012 échevins et autres |
| EU | Eurodéputés, commissaire | Complet | 22 + 1 | 23 | 23 | officielles |
| EU | Autres fonctions UE | Complet | 5 | 5 | non chiffré | CJUE, Cour des comptes européenne, BCE |
| RCA | Famille | Partiel | 113 | 113 | | Wikidata |
| RCA | Proches associés | Complet | 286 | 286 | | Regimand |
| | Moniteur belge (nominations récentes) | Partiel | 93 | 64 | | 29 fiches anciennes sans fonction, 11 noms réduits à une initiale |

**Taux mesuré** sur les 16 fonctions dont on connaît le nombre de titulaires (902 attendus) :
- **publié et juste : 892 (99 %)**. Il manque le gouvernement bruxellois en fonction (8, remplacé par le sortant) et 2 membres de la Cour des comptes.
- **confirmé par la dernière exécution : 689 (76 %)**, la rétention couvrant le reste (Conseil d'État 165, armée 13, Parlement germanophone 25). Avec le bon jar du Conseil d'État : 854 (95 %).

**Taux déclaré : 26 sur 31 (84 %).** Le changeSet 140 a repassé en « complet » les gouvernements régionaux, le Parlement bruxellois, les partis, la Cassation, les organisations internationales et les autres fonctions UE. C'est justifié par les données, sauf pour les gouvernements régionaux tant que Bruxelles pointe vers le gouvernement sortant (25 sur 31, soit 81 %, en le corrigeant). Plusieurs de ces fonctions reposent sur Wikipédia, que la fiche de couverture devrait mentionner.

## 2. Problèmes à corriger d'abord

1. **Conseil d'État : vieux jar chargé.** Le back redémarré le 15/11 charge ses plugins depuis `D:/test-parser/jar parser plugins` (`pf4j.plugins.dir`), qui contient un jar du 06/11. Le jar du 13/11 n'est que dans `D:/test parser 2/plugins`. L'exécution 1158 n'a lu que la page de direction (4 personnes) ; les 165 autres sont gardées par la rétention, jusqu'au 15/11/2027 au plus tard. Correction : copier le jar dans `jar parser plugins`, recharger le plugin, relancer. Les autres jars belges modifiés le 15/11 sont identiques dans les deux dossiers ; 12 jars du 06/11 diffèrent de quelques heures (chamber, senate, diplomats…).
2. **Gouvernement bruxellois sortant.** `be_pep_regional_governments` lit la page Wikipédia « Gouvernement Vervoort III », fini le 14/02/2026 (successeur : gouvernement Dilliès). Rudi Vervoort est publié comme ministre-président en fonction. Ces 8 personnes restent des PEP (sorties depuis moins de 12 mois), mais avec une fonction fausse, et le gouvernement actuel manque. Correction : pointer vers « Gouvernement Dilliès » et, à terme, détecter la page du gouvernement en cours plutôt que de la fixer dans les paramètres. À vérifier aussi : la page FWB publie Boris Dilliès comme ministre de la Communauté française.
3. **Armée : source officielle cassée.** `structure-de-la-defense` répond 404 ; seules les 4 pages d'actualité restent lisibles (5 officiers). Les 13 autres disparaîtront en novembre 2027.
4. **Parlement germanophone indisponible** le 15/11 (pdg.be) : à relancer.
5. **Moniteur** : 29 fiches sans fonction et 11 noms réduits à une initiale, gardés 60 mois. Les nouvelles fiches ont bien une fonction.

## 3. Comparaison mesurée avec OpenSanctions

### Parlements (les 4 jeux officiels d'OpenSanctions)

| Jeu | Chez eux | En fonction selon eux | Retrouvés chez nous | Nos membres absents chez eux |
|---|---|---|---|---|
| Chambre | 650 | 176 | 172 (150 dans la Chambre, 22 ailleurs) | 0 sur 150 |
| Sénat | 125 | 72 | 72 (60 au Sénat, 12 ailleurs) | 0 sur 60 |
| Parlement flamand | 124 | 124 | 124 | 0 sur 124 (+ 2 sortants gardés chez nous seulement) |
| Parlement de Wallonie | 75 | 75 | 75 | 0 sur 75 |

- Les « en fonction » en trop chez OpenSanctions sont les élus devenus ministres, dont le mandat parlementaire n'est pas clos chez eux. Nous les avons dans nos jeux du gouvernement.
- Les 4 restants à la Chambre (Petra De Sutter, Paul Van Tigchelt, Tinne Van der Straeten, Jeroen Soete) sont d'anciens ministres De Croo, partis en février 2025, donc hors délai de 12 mois.
- Les sénateurs Burak Nalli et Malik Ben Achour, absents le 12/11, sont maintenant retrouvés grâce au Parlement bruxellois.
- **Historique** : sur 474 anciens députés de la Chambre chez OpenSanctions, nous en retrouvons 184 dans d'autres jeux (partis, entreprises publiques, communes…) ; 290 sont absents chez nous, dont 51 sortis en 2024. Au Sénat, 13 anciens sont absents. C'est conforme à la règle des 12 mois.

### Toutes les fonctions (tableau EveryPolitician d'OpenSanctions)

| Fonction | OpenSanctions : en cours / terminés / inconnus | SwiftCheck AML |
|---|---|---|
| Roi, Premier ministre, vice-Premiers ministres, ministres fédéraux | 21 / 94 / 30 | 16 en fonction (roi + 15) |
| Ministres-présidents et ministres régionaux | **0** / 16 / 18 | 35 (dont 8 bruxellois sortants) |
| Parlement bruxellois | **0** / 4 / 92 | 89 |
| Parlement de la Communauté française | **0** / 8 / 73 | 94 |
| Parlement germanophone | absent | 25 |
| Bourgmestres, échevins, conseillers communaux | **0** / 88 / 581 | 2 413 (toutes les communes) |
| Gouverneurs de province | 0 / 0 / 10 | 13 |
| Cour de cassation | 0 / 2 / 1 (premier président) | 59 |
| Conseil d'État, Cour constitutionnelle, Cour des comptes | absents | 169 + 12 + 10 |
| Chef de la Défense | 0 / 1 / 1 | 18 officiers généraux |
| Banque nationale | 1 / 2 / 0 (gouverneur) | 20 |
| Ambassadeurs de Belgique | **0** / 17 / 7 | 110 |
| Entreprises publiques | absentes | 583 |
| Organisations internationales en Belgique | absentes (84 fiches liées à l'OTAN, aucune en cours) | 22 |
| Proches et famille | 134 proches associés | 113 famille + 286 associés |

Une partie des PEP « belges » d'OpenSanctions ne le sont pas : 122 fiches sont des **ambassadeurs étrangers accrédités en Belgique** (France, Japon, Espagne…), rattachés à la Belgique par Wikidata. Ce sont des PEP de leur propre pays.

### Test de présence : 56 personnes tirées au hasard dans nos 20 jeux hors parlements

3 personnes par jeu (graine fixe), noms complets seulement, cherchées dans la recherche publique d'OpenSanctions, correspondance exacte du nom.

| Groupe | Trouvés chez OpenSanctions |
|---|---|
| Fonctions électives et gouvernementales (gouvernements, eurodéputés, commissaire, parlements régionaux, partis, famille, élus déclarant des mandats, roi) | **20 sur 23 (87 %)** |
| Fonctions institutionnelles (Conseil d'État, Cour des comptes, Cour constitutionnelle, diplomates, armée, BNB, entreprises publiques, organisations internationales, autres fonctions UE, échevins, Moniteur) | **3 sur 33 (9 %)** |
| Total | 23 sur 56 (41 %) |

Les 3 trouvés du second groupe sont des têtes connues : Pierre Wunsch (gouverneur BNB), Thierry Giet (Cour constitutionnelle) et Denis Mathen (gouverneur de Namur). Le roi figure chez OpenSanctions sous « Philippe of Belgium » (compté trouvé). Non trouvés, par exemple : les 3 auditeurs du Conseil d'État, la première présidente de la Cour des comptes (Hilde François), 3 ambassadeurs, Tom Dechaene (BCE), 3 administrateurs d'entreprises publiques (skeyes, SNCB, Vivaqua) et 2 échevins.

## 4. Ce qui avantage chaque côté

**SwiftCheck AML**
- Couvre toutes les fonctions de l'annexe IV et de l'AMLR, avec le statut en fonction et une source par personne. OpenSanctions n'a de statut fiable que pour 5 fonctions (roi, gouvernement fédéral, Chambre, Sénat, deux parlements régionaux).
- Volume utile : environ 4 235 personnes contre environ 450 « en cours » chez OpenSanctions.
- Licence qui nous appartient ; OpenSanctions est en CC BY-NC 4.0 (licence payante pour un usage commercial).

**OpenSanctions**
- Historique long des parlementaires (approche « une fois PEP »).
- Couverture du reste du monde, une seule intégration.
- Les têtes connues de presque toutes les fonctions, via Wikidata.

## 5. Ce qu'il faut faire, par ordre d'impact

1. Copier le jar du Conseil d'État dans `D:/test-parser/jar parser plugins`, recharger, relancer : de 4 à 169 confirmés.
2. Gouvernement bruxellois : passer au gouvernement Dilliès (et vérifier la FWB).
3. Relancer `be_pep_regional_parliaments` pour le Parlement germanophone.
4. Armée : trouver une source de remplacement pour la page 404 (arrêtés royaux au Moniteur, organigramme mil.be).
5. Mentionner Wikipédia dans les fiches de couverture des fonctions qui en dépendent (gouvernements régionaux, Parlement bruxellois, SHAPE, OEACP).
6. Moniteur : purger les 29 anciennes fiches sans fonction.

## Corrections faites le 15/11 (à recharger et relancer)

- **Conseil d'État** : jar du 13/11 copié dans `D:/test-parser/jar parser plugins` (l'ancien, du 06/11, est sauvegardé dans le dossier de travail de la session).
- **Gouvernement bruxellois** : changeSet `141-be-brussels-government-dillies` (page « Gouvernement Dilliès », appliqué au prochain redémarrage). Le parser suit aussi le lien « suivant » de l'infobox Wikipédia quand le gouvernement lu a une date de fin (3 sauts au plus) et le signale dans le message d'exécution : le nouveau jar donne donc le bon gouvernement avant même le redémarrage. Tests : 4, verts. Jar copié dans les deux dossiers de plugins. Le gouvernement de la FWB est juste : Boris Dilliès y est ministre de la Recherche depuis le 04/03/2026.

**Vérifié après relance (15/11, 12:25)** : exécution 1180, Conseil d'État **169 magistrats lus en direct** ; exécution 1181, gouvernements régionaux 35, dont les 8 du gouvernement Dilliès. yente (après réindexation) publie 40 membres : les 35 en fonction et 5 sortants bruxellois gardés par la rétention (Vervoort, Maron, Clerfayt, Trachte, Ben Hamou). Ces sortants gardent leur ancien intitulé (« Ministre-président » pour Rudi Vervoort), sans mention de fin de mandat. Le taux confirmé par la dernière exécution passe de 76 % à **95 %** (854 sur 902).

## Limites

- Le tableau EveryPolitician donne des nombres de fiches par fonction, pas des personnes : une même personne peut y figurer plusieurs fois.
- Test de présence : 56 personnes, correspondance sur le nom seulement. Les noms réduits à des initiales (siège du Conseil d'État, auditorat francophone) sont exclus du tirage, ce qui avantage un peu OpenSanctions.
- Les attendus (35 ministres régionaux, 12 membres de la Cour des comptes, 169 au Conseil d'État, 18 officiers) viennent des compositions connues ou des documents officiels lus, pas d'un recensement indépendant.
- Le gouvernement Dilliès et sa date (14/02/2026) viennent de Wikipédia, non vérifiés sur un site officiel (be.brussels injoignable).
