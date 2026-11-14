# Diagnostic de la couverture PEP Belgique, et comparaison avec OpenSanctions

État au 12 novembre 2026. Complète la note `Comparaison_Belgique_OpenSanctions_2026-11-12.md` (petit test à la main) par une mesure sur les données.

**Sources des chiffres**
- **Ce qui est publié** : index yente local (`localhost:8000`, collection `default`), c'est-à-dire ce que le filtrage voit réellement. 24 datasets `be_pep_*`, 1 475 fiches Person.
- **Ce qui est déclaré** : base `swift_check_2` (lecture seule) : tables `parser_execution`, `pep_function`, `pep_function_parser`.
- **OpenSanctions** : fichiers publics `data.opensanctions.org/datasets/latest/<jeu>/` des 4 jeux belges (Chambre, Sénat, Parlement flamand, Parlement de Wallonie), téléchargés le 12/11/2026, avec les dates de mandat (Occupancy). La partie Wikidata d'OpenSanctions (environ 1 380 des 2 354 PEP belges) n'est pas mesurée ici (voir la fin).
- Rapprochement : nom normalisé (sans accents ni casse, ordre des mots indifférent), puis date de naissance avec un nom partiel.

## En bref

1. **Sur les 4 parlements qu'OpenSanctions couvre, nous sommes au même niveau : 409 membres en fonction sur 409**, et chacun de nos 409 membres est aussi chez OpenSanctions. La seule différence : OpenSanctions garde l'historique (anciens élus partis depuis plus de 12 mois), ce que la loi belge ne demande pas.
2. **Hors parlements, nous avons beaucoup plus** : diplomates, entreprises publiques, Banque nationale, Cour des comptes, Cour constitutionnelle, bourgmestres, proches associés. OpenSanctions n'a pas de jeu officiel pour ces fonctions.
3. **Mais notre statut déclaré est trop optimiste.** 30 des 32 fonctions sont déclarées complètes par au moins un parser ; au vu des données publiées, **11 de ces 30 ne le sont pas** (tableau ci-dessous), dont le Parlement bruxellois (0 sur 89), les gouvernements wallon, bruxellois et germanophone, la Cour de cassation et les cours d'appel.
4. **Trois exécutions enregistrées le 9 novembre ne sont pas réelles.** Elles annoncent 100 membres du Conseil d'État, 35 ministres régionaux et 18 officiers généraux ; l'index publie toujours 4, 15 et 6.

## 1. Les trois exécutions du 9 novembre

| Exécution | Annonce | Publié dans yente |
|---|---|---|
| 964, `be_pep_council_of_state` | 100 hauts magistrats | 4 |
| 965, `be_pep_regional_governments` | 35 ministres (Flandre 9, Wallonie 8, FWB 6, Bruxelles 8, germanophone 4) | 15 (Flandre 9, FWB 6) |
| 966, `be_pep_army` | 18 officiers généraux | 6 |

Indices que ces lignes n'ont pas été écrites par l'application : début et fin à la même seconde, à 01:02, 01:03 et 01:05 ; microsecondes à zéro ; pas d'empreinte de fichier (`input_sha256` vide) ; accents doublement encodés (« entitÃ©s ») alors que les vraies exécutions sont correctes ; `retained_count` égal au nombre de personnes. La version publiée de ces trois datasets date du 5 novembre.

Le code de ces trois plugins a bien été modifié ensuite (commits du 10, 11 et 12 novembre, dont « remove seed and use live fetching » pour les gouvernements régionaux) et les jars sont dans `D:/test parser 2/plugins`. **Mais aucun ne s'est exécuté depuis.** Il faut les lancer depuis l'application, puis décider quoi faire des lignes 964 à 966 (l'historique affiche aujourd'hui des chiffres faux). Je n'ai rien modifié dans la base.

## 2. Fonction par fonction (Annexe IV, loi du 18/09/2017)

Légende : ✅ conforme à la déclaration, ⚠️ déclaré complet mais incomplet, ❌ déclaré complet et vide ou presque.

| Cat. | Fonction | Déclaré | Publié | Diagnostic |
|---|---|---|---|---|
| BE-a | Roi | Complet | 1 | ✅ (via Wikidata) |
| BE-a | Gouvernement fédéral | Complet | 15 | ✅ |
| BE-a | Gouvernements régionaux et communautaires | Complet | 15 sur environ 35 | ⚠️ Wallonie, Bruxelles et Communauté germanophone absents. Code corrigé le 12/11, pas exécuté |
| BE-a | Anciens membres d'un gouvernement | Complet (Moniteur) | quasi rien | ⚠️ Le Moniteur ne lit que 180 jours. Sans enjeu immédiat : les ministres De Croo sont partis le 03/02/2025, plus de 12 mois |
| BE-b | Chambre | Complet | 150 sur 150 | ✅ Identique à OpenSanctions |
| BE-b | Sénat | Complet | 60 sur 60 | ✅ Identique à OpenSanctions |
| BE-b | Parlement flamand | Complet | 124 sur 124 | ✅ Identique à OpenSanctions |
| BE-b | Parlement de Wallonie | Complet | 75 sur 75 | ✅ Identique à OpenSanctions |
| BE-b | Parlement de la FWB | Complet | 94 sur 94 | ✅ |
| BE-b | Parlement de la Communauté germanophone | Complet | 25 sur 25 | ✅ |
| BE-b | **Parlement de la Région de Bruxelles-Capitale** | Complet | **0 sur 89** | ❌ « Parlements indisponibles : Région de Bruxelles-Capitale » à chaque exécution (parlement.brussels ne répond pas depuis notre poste). Seuls les 19 députés bruxellois francophones siégeant à la FWB sont couverts, par ce biais |
| BE-c | Organes dirigeants des partis | Complet | 20 (18 partis) | ⚠️ Wikidata seulement ; pas de source officielle, président de parti uniquement |
| BE-d | Cour constitutionnelle | Complet | 12 sur 12 | ✅ |
| BE-d | **Cour de cassation** | Complet (Moniteur) | ≈ 0 | ❌ Aucune source directe (domaine de la Cour non résolu) ; le Moniteur ne voit que les nominations des 180 derniers jours |
| BE-d | **Cours d'appel et du travail** | Complet (Moniteur) | ≈ 0 | ❌ Même cause |
| BE-d | Conseil d'État | Partiel (parser dédié), Complet (Moniteur) | 4 | ⚠️ Président, premier président, auditeur général et adjoint. Le code annonce 100, pas exécuté |
| BE-e | Banque nationale | Complet | 20 | ✅ |
| BE-e | Cour des comptes | Complet | 10 (2 présidents, 8 conseillers) | ✅ à confirmer : la Cour compte 12 membres |
| BE-f | Ambassadeurs et chefs de poste | Complet | 110 | ✅ OpenSanctions n'a pas de jeu dédié |
| BE-f | Officiers généraux | Partiel (parser dédié), Complet (Moniteur) | 6 | ⚠️ Et une fonction mal extraite : « Commandant du Cyber Command, complète : « Nous avons i… » |
| BE-g | Entreprises publiques | Complet | 234 (14 entreprises) | ✅ OpenSanctions n'a rien |
| BE-h | Organisations internationales | Complet | 7 (OTAN 4, Benelux 3) | ⚠️ SHAPE, Eurocontrol, OEACP bloqués par anti-bot ; OMD en échec |
| AMLR | Bourgmestres et gouverneurs (50 000 hab. et plus) | Complet | 44 (33 bourgmestres, 11 gouverneurs) | ✅ |
| AMLR | **Échevins, présidents de CPAS, députés provinciaux** | Complet | **0** | ❌ Le parser ne publie que bourgmestres et gouverneurs |
| AMLR | Couche étendue (toutes les communes) | Partiel | 670 annoncés, **0 dans l'index** | ⚠️ Le dataset est au catalogue yente mais vide : à vérifier (exclusion voulue ou erreur d'indexation ?) |
| EU | Députés européens | Complet | 22 sur 22 | ✅ |
| EU | Commissaire | Complet | 1 sur 1 | ✅ |
| EU | **Autres fonctions UE** | Complet | **0** | ❌ `be_pep_eu_institutions` n'a jamais été exécuté |
| RCA | Famille | Partiel | 111 | ✅ (Wikidata) |
| RCA | Proches associés | Complet | 286 | ✅ (déclarations de mandats Regimand) |

**Qualité du jeu Moniteur belge.** Les 29 personnes publiées n'ont **aucune fonction rattachée** (pas d'Occupancy, pas de propriété `position`) : une alerte n'indique donc pas pourquoi la personne est PEP. Plusieurs noms sont réduits à une initiale (« Algoet I », « Collinet T ») ou mal découpés (prénom « Colienne LEJEUNE DE », nom « SCHIERVEL »), ce qui dégrade le rapprochement.

## 3. Comparaison mesurée avec OpenSanctions (parlements)

| Jeu OpenSanctions | Personnes chez eux | En fonction selon eux | Retrouvées chez nous | Nos membres absents chez eux |
|---|---|---|---|---|
| Chambre | 650 | 176 | 168 (150 dans `be_pep_chamber`, 18 ailleurs) | 0 sur 150 |
| Sénat | 125 | 72 | 70 (60 dans `be_pep_senate`, 10 ailleurs) | 0 sur 60 |
| Parlement flamand | 124 | 124 | 124 | 0 sur 124 |
| Parlement de Wallonie | 75 | 75 | 75 | 0 sur 75 |

Pourquoi OpenSanctions a plus de « membres en fonction » que de sièges (176 pour 150 à la Chambre) : il ne clôt pas le mandat des élus devenus ministres. Les 18 retrouvés « ailleurs » chez nous sont justement ces ministres (De Wever, Prévot, Francken, Vandenbroucke…), présents dans nos jeux du gouvernement.

**Les 10 « en fonction » qu'OpenSanctions a et pas nous**
- Chambre (8) : Petra De Sutter, Paul Van Tigchelt, Tinne Van der Straeten, Jinnih Beels, Jeroen Soete, Simon Dethier, Mireille Colson, Pierre-Yves Jeholet. Les trois premiers sont des ministres du gouvernement De Croo, partis le 03/02/2025 : hors délai de 12 mois si leur siège n'a jamais été repris. Les autres sont à vérifier un par un (élus de 2024 ayant cédé leur siège, ou mandat non clos chez OpenSanctions).
- Sénat (2) : Burak Nalli, Malik Ben Achour. À vérifier sur senate.be ; Malik Ben Achour est aussi un cas du Parlement bruxellois, que nous ne publions pas.

**Ce qu'OpenSanctions garde et pas nous** : 95 élus dont le mandat a pris fin en 2024 (73 à la Chambre, 22 au Sénat) et 283 avant 2024, absents de nos données. C'est conforme à notre règle (12 mois après la fin de fonction), mais un client qui veut une approche plus prudente (« once a PEP… ») les trouvera chez eux et pas chez nous. Parmi les anciens sénateurs, plusieurs exercent aujourd'hui un autre mandat (Parlement bruxellois notamment) : ils nous manquent à cause du trou bruxellois, pas de la règle des 12 mois.

**Au-delà des parlements** : environ 870 de nos personnes (diplomates, entreprises publiques, proches associés, Banque nationale, juridictions, bourgmestres) relèvent de fonctions pour lesquelles OpenSanctions n'a pas de jeu officiel. Le test de la note précédente l'illustre : sur 10 personnes tirées au hasard de ces jeux, OpenSanctions n'en connaît que 2.

## 4. Ce qu'il faut faire, par ordre d'impact

1. **Exécuter depuis l'application** `be_pep_regional_governments`, `be_pep_council_of_state` et `be_pep_army` (code prêt, jamais lancé), vérifier les chiffres, puis traiter les lignes 964 à 966 de `parser_execution`.
2. **Parlement bruxellois (89 députés)** : trouver une source qui répond (open data régional, Wikidata en dernier recours, comme pour l'Espagne en `PARTIAL`).
3. **Corriger les déclarations** de complétude par un nouveau changeSet Liquibase : gouvernements régionaux (tant que l'exécution n'a pas confirmé), Bruxelles, Cassation, cours d'appel, Conseil d'État et officiers généraux via le Moniteur, anciens ministres, échevins et CPAS, autres fonctions UE, organisations internationales, partis → `PARTIAL` ou `NONE` avec la lacune en clair.
4. **Échevins, présidents de CPAS, députés provinciaux** des collectivités de 50 000 habitants et plus : étendre `be_pep_local_executives` (même source Wikidata/LBLOD).
5. **Moniteur belge** : rattacher une fonction à chaque personne et rejeter les noms réduits à une initiale ; envisager une lecture sur plusieurs années pour les juridictions (rétention 60 mois déjà prévue).
6. **Cour de cassation et cours d'appel** : chercher une source directe (annuaire du SPF Justice, rapports annuels) ; sans elle, ces fonctions restent quasi vides.
7. Lancer `be_pep_eu_institutions` une première fois ; vérifier pourquoi `be_pep_local_executives_all` est vide dans l'index.

## Suite donnée le 12/11/2026

- ChangeSet `137-be-coverage-completeness-review` écrit (appliqué au prochain démarrage du back) : passe en `PARTIAL` les 8 fonctions lues par le Moniteur, les partis (99), les organisations internationales (109) et les autres fonctions UE (113). Les clauses WHERE touchent bien 11 lignes.
- En attente d'une exécution réelle avant de trancher : gouvernements régionaux (89), Parlement bruxellois (96), échevins et CPAS (245).
- À lancer depuis l'application (l'API exige une connexion Keycloak) : `be_pep_regional_governments` (id 97), `be_pep_council_of_state` (101), `be_pep_army` (105), et aussi `be_pep_regional_parliaments` (98) et `be_pep_local_executives` (116), dont le code a été modifié les 11 et 12/11 sans être relancé.
- parlement.brussels ne répond toujours pas depuis notre poste (délai de 20 s dépassé le 12/11).

**Exécutions réelles du 12/11 vers 23 h 40**
- `be_pep_local_executives` : 31 bourgmestres, 12 gouverneurs et **315 échevins, présidents de CPAS et députés provinciaux**. La fonction 245 est désormais réellement couverte.
- `be_pep_regional_governments` : toujours **15** membres. Wallonie et Bruxelles en délai dépassé ; Communauté germanophone en HTTP 307 (403 avec curl : protection anti-robot, non contournée). Bloquée d'abord par le garde-fou (« baisse de 57,6 % »), parce qu'elle était comparée à la ligne fictive 965.
- `be_pep_council_of_state` : toujours **4**. Même blocage par le garde-fou, cette fois par rapport à la ligne fictive 964.
- `be_pep_regional_parliaments` : inchangé (119), Bruxelles toujours indisponible.
- `be_pep_army` : en échec. Le paramètre `armyPages` posé par le changeSet 124 est dans l'enveloppe de `ConvertTo-Json` de PowerShell (`{"value":[…],"Count":5}`). `be_pep_international_orgs` a le même défaut et échouera à sa prochaine exécution. Correction : changeSet `138-be-unwrap-powershell-json-parameters`.
- Le changeSet 137 passe aussi en `PARTIAL` les gouvernements régionaux (89) et le Parlement bruxellois (96).

## État au 13/11/2026, après redémarrage du back

ChangeSets 137 et 138 appliqués (12/11, 22:55). `be_pep_army` pas encore relancé depuis la correction. yente réindexé à la main le 13/11 (la publication de `be_pep_local_executives` de 22:42 n'avait pas été prise en compte : verrous `yente-locks` en conflit, signe de réindexations simultanées).

**Taux de couverture**
- Taux déclaré, celui que calcule le rapport de l'application : **19 fonctions complètes sur 32 = 59 %**, 13 partielles, 0 sans source (94 % avant les corrections).
- Taux mesuré en personnes, sur les 14 fonctions dont on connaît le nombre de titulaires : **623 sur 715 = 87 %**. En ajoutant le Conseil d'État (environ 100) et les officiers généraux (18, chiffre du parser) : **633 sur 833 = 76 %**.

| Fonction | Publié | Attendu |
|---|---|---|
| Roi, gouvernement fédéral, Chambre, Sénat, parlements flamand, wallon, FWB et germanophone, Cour constitutionnelle, eurodéputés, commissaire | 100 % | |
| Gouvernements régionaux | 15 | 35 |
| Parlement bruxellois | 19 (via la FWB) | 89 |
| Cour des comptes | 10 | 12 |
| Conseil d'État | 4 | ≈ 100 |
| Officiers généraux | 6 | 18 |
| Exécutifs locaux (50 000 hab. et plus) | 60 bourgmestres, 22 gouverneurs, 234 échevins, 25 présidents de CPAS, 44 députés provinciaux (sortants de moins de 60 mois compris) | ≈ 100 %, à confirmer |
| Cassation, cours d'appel et du travail | quasi rien | non chiffré |

**Nouveau constat** : 4 index yente sont construits mais absents de l'alias de recherche `yente-entities`, donc invisibles au filtrage : `be_pep_local_executives_all` (670 bourgmestres de toutes les communes), `fr_pep_local_executives_all` (107 359 entités), `lu_pep_government_former`, `nl_pep_parliament_history`. Exemple : Alain Rongvaux, bourgmestre de Saint-Léger, est dans l'index mais introuvable par `/search`.

**`be_pep_army` relancé (12/11, 23:08)** : réussi, 18 officiers généraux dans le fichier publié (CHOD, VCHOD, commandants des composantes Terre, Air, Marine, Médicale, Cyber, directeurs généraux). Défauts : une fiche « Jean » (prénom seul) et deux anciennes fiches en double gardées par la rétention (« Goetynck » à côté de « Frédéric Goetynck », « Van Goethem » sans prénom). yente sert encore les 6 du 5/11 : la version de l'index ne change pas entre publications (même défaut que ci-dessus).

**Correction du parser army (13/11)** : « Jean » venait de deux officiers aux prénoms composés, coupés au trait d'union et fusionnés (Jean-Pol Baugnée, commandant de la Composante Terre, et Jean-Louis Crucifix, commandant de l'École royale militaire). Les noms d'un seul mot (« Goetynck ») et les noms sans prénom qui doublonnent une fiche complète sont désormais écartés ; l'intitulé de fonction s'arrête à la virgule. Rejoué sur les pages archivées : 18 officiers, tous avec prénom sauf « Van Goethem » (seule mention de cet officier, gardé). Jar déployé, à relancer.

## Conseil d'État complet (13/11/2026)

Parser réécrit : page de direction + compositions officielles des chambres (FR, NL) et de l'auditorat (FR, NL), liens des PDF relus sur le site, plus de PDF embarqués. Exécution 1152 : **169 magistrats** (premier président, présidente, auditeur général et adjoint, 14 présidents de chambre, 40 conseillers d'État, 7 assesseurs, 104 membres de l'auditorat), greffiers exclus. ChangeSet `139-be-council-of-state-complete` : fonction 103 en `COMPLETE`, 169 titulaires attendus.

Taux après ce changement (et le changeSet 139 appliqué) : **20 fonctions complètes sur 32 = 63 %** ; en personnes, **808 sur 902 = 90 %** (attendus : 715 des fonctions déjà chiffrées, 169 au Conseil d'État, 18 officiers généraux).

## Limites

- Le rapprochement se fait sur le nom et la naissance ; 10 cas sont listés « à vérifier » plutôt que tranchés.
- La partie Wikidata d'OpenSanctions (environ 1 380 PEP belges, gouvernements régionaux, chefs de parti, anciens ministres) n'est pas mesurée. Pour la mesurer, il faut le fichier `peps/targets.simple.csv` (182 Mo) et un tirage au hasard parmi les fiches belges.
- Les attendus (35 ministres régionaux, 12 membres de la Cour des comptes) viennent de la composition connue des institutions, pas d'une relecture des sites officiels aujourd'hui.
