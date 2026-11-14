# PEP France : SwiftCheck AML et OpenSanctions

État au 12 novembre 2026.

**Sources des chiffres**
- SwiftCheck AML : base `swift_check_2` (lecture seule) et datasets publiés, dernière exécution réussie de chaque parser.
- OpenSanctions : page pays France relue le 12/11/2026 (jeux de données et total). Le détail par catégorie (fonctions absentes ou partielles chez OpenSanctions) vient de l'analyse du 3 novembre 2026 et n'a pas été revérifié.

## En bref

| | SwiftCheck AML | OpenSanctions |
|---|---|---|
| Fonctions de la loi couvertes (CMF R.561-18, UE, AMLR) | 21 sur 22 déclarées complètes, 1 partielle (organisations internationales) | sur les 9 catégories FR-a à FR-h et FR-EU : 3 complètes (gouvernement, Parlement, UE), 3 partielles, 3 absentes |
| Personnes | voir tableau (volumes non comparables) | 109 423 « political office-holders » |
| Origine du volume | fonctions de la loi, sorties de moins de 12 mois | 68 720 maires de toutes les communes, 3 155 de l'Assemblée (historique), 3 883 déclarants HATVP |
| Sources | un parser par source officielle ; Wikidata pour la famille, les partis et 2 fonctions UE | quelques sources officielles (maires, HATVP, parlements) ; le reste vient de Wikidata, de la CIA et d'EveryPolitician |
| Licence | à nous, licences des sources respectées | CC BY-NC 4.0 : usage commercial soumis à licence payante |

**Les 21 sur 22 sont un statut déclaré, pas un taux mesuré.** Dans l'analyse du 10 novembre, 19 fonctions étaient mesurées et fiables, 4 non mesurables, 4 fragiles. Les fonctions des juridictions reposent sur le JORF, qui donne des nominations sans dates de fin : seuls 3 membres du Conseil d'État, 18 de la Cour de cassation et 6 de la Cour des comptes sont confirmés en poste.

## Catégorie par catégorie

| Cat. | Fonctions | SwiftCheck AML | OpenSanctions |
|---|---|---|---|
| FR-a | Président, gouvernement, anciens ministres | Complet : président 1, gouvernement 36 (35 attendus), JORF pour les anciens | Complet (CIA, Wikidata, HATVP) |
| FR-b | Députés, sénateurs | Complet : 627 députés (577 attendus) et 421 sénateurs (348), sortants de moins de 12 mois compris | Complet : 3 155 (Assemblée) et 522 (Sénat), historique compris |
| FR-c | Organes exécutifs des partis | Complet : 137, mais via Wikidata | Absent |
| FR-d | Conseil constitutionnel, Conseil d'État, Cour de cassation | Conseil constitutionnel **10 sur 10** ; Conseil d'État et Cassation par le JORF (nominations depuis 1990, statut « inconnu » pour la plupart) | Pas de jeu de données officiel dédié (seulement l'Inspection générale de la justice) ; au niveau des noms, les membres du Conseil constitutionnel sont connus via Wikidata (voir le test de présence) |
| FR-e | Cour des comptes, Banque de France | Banque de France 11 sur 11 ; Cour des comptes par le JORF | Partiel : gouverneur et sous-gouverneurs |
| FR-f | Ambassadeurs, chefs d'état-major | Ambassadeurs 194 (160 attendus) ; chefs d'état-major **4 sur 4** | Partiel : ambassadeurs surtout anciens (Wikidata) ; état-major bien couvert |
| FR-g | Entreprises publiques et sociétés locales | 1 578 (État) et 2 451 (sociétés locales de 50 M€ et plus) | Absent (quelques dirigeants via la HATVP) |
| FR-h | Organisations internationales en France | Partiel : **52 sur 70 (74 %)** | Partiel : onglet « intergouvernemental » d'EveryPolitician |
| FR-EU | Fonctions européennes | Complet : 82 députés européens, 1 commissaire, 9 autres fonctions | Complet : députés européens, commissaires |
| AMLR | Exécutifs locaux de 50 000 habitants et plus | 455 sur 500 (91 %) ; en plus 35 097 maires de toutes les communes en couche étendue | 68 720 maires de toutes les communes, hors champ légal actuel |
| RCA | Famille et proches associés | Partiel : 241 membres de la famille, 387 organisations liées | Partiel : 1 070 proches, via Wikidata |

## Ce qui avantage chaque côté

**SwiftCheck AML**
- Couvre des catégories qu'OpenSanctions laisse vides : hautes juridictions, entreprises publiques, partis, Cour des comptes, organisations internationales.
- Suit la loi : règle des 12 mois, fonctions listées par le CMF, détail de couverture et traçabilité des fichiers bruts.

**OpenSanctions**
- Couvre le reste du monde (environ 200 pays), indispensable pour les clients étrangers.
- Historique plus profond, et beaucoup plus de noms (maires de toutes les communes, déclarants HATVP). Mais beaucoup de titulaires au statut incertain.

## Limites de cette comparaison

- Les volumes ne se comparent pas : OpenSanctions compte les titulaires de toutes les époques, nous seulement les actuels et les sortis depuis moins de 12 mois. Nos totaux pour les juridictions (335, 460, 444 personnes) sont aussi cumulatifs depuis 1990.
- Les effectifs attendus de la Cour de cassation (400) et de la Cour des comptes (300) ne sont pas sourcés ; je n'ai pas trouvé de chiffre officiel.
- Les partis (137) et la famille (241) dépendent de Wikidata, qui n'est pas une source officielle.
- Le détail par catégorie côté OpenSanctions date du 3 novembre : seuls le total et la liste des jeux de données ont été relus aujourd'hui.
