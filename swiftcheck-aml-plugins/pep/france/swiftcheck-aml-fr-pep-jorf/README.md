# fr_pep_jorf – Hautes fonctions nommées au Journal officiel (France)

## Ce que fait ce parser

Il lit l'**open data du Journal officiel** publié par la DILA. Il y repère les décrets et arrêtés qui nomment, maintiennent ou font partir les membres de trois hautes juridictions et les ambassadeurs :

- le Conseil d'État ;
- la Cour de cassation ;
- la Cour des comptes ;
- les ambassadeurs et représentants permanents.

À partir de ces textes, il reconstitue les mandats depuis 1990 et les publie en entités FtM. C'est l'équivalent français de [`lu_pep_legilux`](../../luxembourg/swiftcheck-aml-lu-pep-legilux).

**Exécution réelle du 30/10/2026** : 876 fichiers de la DILA (archive complète et fichiers quotidiens jusqu'au 30/10/2026), 8 055 articles gardés, 1 498 personnes publiées, 0 valeur rejetée.

- **Première exécution** : 25 min, surtout le téléchargement de 705 fichiers. Le cache occupe ensuite 9,9 Mo.
- **Exécutions suivantes** : 6 à 14 s.

Répartition mesurée sur l'archive complète et 3 mois de fichiers quotidiens :

| | Personnes | Attendu |
|---|---|---|
| Conseil d'État | 314 | ≈ 300 |
| Cour de cassation | 416 | ≈ 400 |
| Cour des comptes | 425 | ≈ 300 (le corps compte aussi les magistrats détachés) |
| Ambassadeurs et représentants permanents | 222, dont 31 sortis depuis moins d'un an | ≈ 160 postes, plus les ambassadeurs thématiques |

### Membres du Gouvernement (sélection version 2)

Le parser lit aussi les décrets « relatif à la composition du Gouvernement », « portant nomination du Premier ministre » et ceux qui mettent fin aux fonctions du Gouvernement. Il en tire les **membres en fonction** et les **anciens membres sortis depuis moins de 12 mois** (FR-a). Il complète ainsi `fr_pep_government` : la HATVP ne fait connaître un ancien membre que s'il dépose une déclaration après son départ. Wikidata a été essayé, mais il ne rattache que 2 à 6 membres à chacun des gouvernements récents.

| Tournure | Exemple réel |
|---|---|
| Liste | « Sont nommés ministres : » puis « M. Laurent NUNEZ, ministre de l'intérieur ; » (la fonction suit le nom) |
| Ministres délégués | « Sont nommés ministres délégués … : », « - Auprès du ministre de l'intérieur : », « M. Jean-Didier BERGER ; » |
| Nomination seule | « Mme Catherine PÉGARD est nommée ministre de la culture. » ; « M. Sébastien LECORNU est nommé Premier ministre. » |
| Départ | « Il est mis fin aux fonctions de Mme Rachida DATI, ministre de la culture. », ou une liste après « Il est mis fin aux fonctions de : » |
| Fin du Gouvernement | « Il est mis fin, sur la présentation de la démission du Gouvernement, aux fonctions de M. Jean-Pierre Raffarin, Premier ministre, et des autres membres du Gouvernement. » (tournure de 1991 à 2025) : tous les mandats ouverts se ferment ; le Premier ministre nommé le même jour ne l'est pas |

**Mesure du 30/10/2026**, sur tout l'historique : 39 mandats publiés.

- **35 membres du gouvernement Lecornu II**, avec les nominations du 26/02/2026.
- **4 anciens sortis depuis moins d'un an** : Mmes de Montchalin, Dati et Parmentier-Lecocq, M. Fournier.
- Aucun membre d'un gouvernement antérieur n'est resté ouvert par erreur.

Un mandat ouvert au Gouvernement a le statut `current`, et non `unknown` : toute fin de fonctions passe par un décret.

Trois règles complètent la lecture :

- Un membre qui quitte une fonction et en reçoit une autre dans le même décret reste au Gouvernement.
- Un mandat ouvert depuis plus de `governmentMaxYears` ans (6) est écarté : c'est une sécurité si un décret de fin de Gouvernement a été manqué.
- Une coupure réseau pendant un téléchargement est retentée 3 fois. Une exécution qui échoue reprend ensuite là où elle s'est arrêtée, grâce au cache.

## Contexte

| | |
|---|---|
| Catégories PEP | **FR-a** Premier ministre, ministre ou secrétaire d'État ; ancien membre du Gouvernement · **FR-d** Membre du Conseil d'État, membre de la Cour de cassation · **FR-e** Magistrat de la Cour des comptes · **FR-f** Ambassadeur ou chargé d'affaires · **FR-g** Dirigeant d'un grand port maritime (partiel) |
| Base légale | CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 d), e) et f) |
| Couverture | **Partielle** : voir « Limites connues » |
| Rétention | Mandats terminés depuis moins de 12 mois (`monthsAfterEnd`), puis rétention du core (12 mois) |

## Source

| | |
|---|---|
| Éditeur | Direction de l'information légale et administrative (DILA) |
| Adresse | `https://echanges.dila.gouv.fr/OPENDATA/JORF/` |
| Fichiers | `Freemium_jorf_global_<date>.tar.gz` : archive complète, environ 1,7 Go. Puis `JORF_<date>-<heure>.tar.gz` : un fichier par publication depuis cette date, 2 à 10 Mo, deux par jour. |
| Format | Un XML par article (`ARTICLE`). Son bloc `CONTEXTE` donne le titre, la nature et les dates du texte : une seule lecture suffit, sans assembler les textes. |
| Licence | Licence Ouverte / Open Licence (Etalab 2.0) |

### Volume et cache

Tout relire à chaque exécution représenterait plusieurs gigaoctets. Le plugin garde donc un **cache** dans `cacheDir` :

1. chaque fichier de la DILA est téléchargé **une seule fois** ;
2. il est lu en flux (lecteur tar intégré, `TarReader`) et réduit aux articles utiles. L'archive complète prend environ 70 s et donne environ 7 500 articles ; un fichier quotidien en donne de 0 à 5 ;
3. l'extrait est enregistré dans `<fichier>.s<version>.zip`, puis le fichier d'origine est supprimé.

### Dirigeants des grands ports maritimes (sélection version 3, FR-g)

Les grands ports maritimes (Marseille, Dunkerque, Bordeaux, Haropa…) n'ont **aucun dirigeant inscrit au registre des entreprises** : `fr_pep_state_enterprises` ne peut donc pas les produire. Leurs dirigeants sont nommés au Journal officiel, et le parser lit ces nominations (institution « Entreprises publiques »).

Exemple réel (décret du 9 avril 2026, JO du 10 avril) : « M. Ivan MARTIN est nommé président du directoire du grand port maritime de Bordeaux, en remplacement de M. Jean-Frédéric LAURENT, à compter du 20 avril 2026. »

- **Mandat** : un par personne et par poste écrit (président du directoire, membre du directoire, membre du conseil de surveillance… de tel port).
- **Poste à titulaire unique** (président, directeur général) : la nomination d'un successeur ferme le mandat du prédécesseur. Un membre du conseil de surveillance n'a pas de successeur désigné, son mandat reste ouvert.
- **Sans fin connue** : le mandat est écarté après `enterpriseMaxYears` ans (6 par défaut ; les mandats sont de cinq ans).
- Le passage à la version 3 force la **relecture de l'archive complète** à la prochaine exécution, soit environ 25 min et 1,7 Go téléchargés une fois.

Après la première exécution, seuls les nouveaux fichiers quotidiens sont téléchargés.

Le cache ne contient que des copies réduites de fichiers publics, jamais d'état de conversion : le supprimer fait seulement tout retélécharger. Changer la sélection (`JorfArticles.SELECTION_VERSION`) force la relecture des fichiers.

### Articles gardés (`JorfArticles.keep`)

- Le titre du texte parle d'une nomination, retraite, fin de fonctions, cessation, désignation, radiation, reconduction, renouvellement ou maintien.
- Ce titre cite le Conseil d'État, la Cour des comptes, la Cour de cassation, la magistrature, un ambassadeur, un représentant permanent ou un grand port maritime.
- Sont exclus les textes sur la Cour nationale du droit d'asile, les attachés, les jurys, les chambres régionales des comptes, les conseils et commissions.
- Pour un texte « (Conseil d'État) » ou « (Cour des comptes) », tous les articles sont gardés. Pour les autres, seuls les articles qui citent une haute institution. Les décrets « (magistrature) », très nombreux, ne sont ainsi gardés que pour leurs articles sur la Cour de cassation.

## Tournures reconnues (`NominationReader`)

| Événement | Exemple réel |
|---|---|
| Nomination | « M. Paul BERNARD, maître des requêtes au Conseil d'Etat, est nommé conseiller d'Etat en service ordinaire, à compter du 15 mai 2026. » ; aussi « est désigné dans les fonctions de », « est reconduit dans ses fonctions de » |
| Nomination collective | « Sont nommés conseillers maîtres à la Cour des comptes, à compter du 1er octobre 2026 : » puis une ligne par personne |
| Magistrature | « Sont nommés : », section « COUR DE CASSATION », puis « Premier avocat général : M. Xavier TARABEUX, avocat général près ladite cour. » ou un en-tête « Avocates générales référendaires : » suivi des personnes |
| Retraite | « M. Eric SEGUY, conseiller à la Cour de cassation, est admis, sur sa demande, à faire valoir ses droits à la retraite, à compter du 1er janvier 2027. » ; aussi collective (« sont admis … : ») |
| Fin de fonctions | « il est mis fin, à sa demande, à compter du 21 mai 2012, aux fonctions de maître des requêtes en service extraordinaire de M. Alexandre Aïdara » ; « il est mis fin au maintien en activité de … » ; « radié des cadres » |
| Maintien | « … maintenue en activité … du 19 octobre 2026 au 18 octobre 2029 inclus pour exercer les fonctions d'avocate générale près la Cour de cassation » ; « maintenue en position de détachement auprès de la Cour des comptes afin d'exercer les fonctions de … pour une durée de trois ans » |
| Départ implicite | Nomination ailleurs d'une personne dont la qualité cite l'institution : « Mme Julie VIGNERAS, conseillère référendaire à la Cour de cassation », nommée première vice-présidente d'un tribunal |

Le parser gère aussi deux formats de texte :

- **Formats anciens** : « M. Attali (Bernard) » (nom, puis prénoms entre parenthèses), « Art. 1er. - », phrases coupées en plusieurs lignes.
- **Prédécesseur** : « en remplacement de M. X, admis à faire valoir ses droits à la retraite » parle du prédécesseur ; cette fin de phrase est retirée avant la lecture.

Certaines mentions ne sont pas des départs :

- Une fin de fonctions **hors** de l'institution ne fait pas quitter le corps. Exemple : un conseiller maître qui quitte la présidence d'une chambre régionale des comptes.
- « il est mis fin aux fonctions de M. Philippe Belaval, conseiller d'Etat, directeur des Archives de France » termine la direction, pas l'appartenance au Conseil d'État.

## Reconstitution des mandats

Il y a un mandat par personne et par institution, et par poste pour un ambassadeur. Les événements sont appliqués dans l'ordre des dates d'effet ; à date égale, l'ordre est nomination, puis départ, puis maintien.

| Événement | Effet |
|---|---|
| Nomination | Ouvre un mandat. Une promotion dans la même institution (maître des requêtes → conseiller d'État) le prolonge, garde son début et change la fonction affichée. |
| Nomination d'ambassadeur | Ferme aussi le mandat du **prédécesseur au même poste** et le poste précédent de la personne. |
| Départ | Ferme le mandat. Sans mandat connu (nommé avant 1990), en crée un, terminé, sans début. |
| Maintien | Fixe la fin prévue. Il rouvre le mandat si une retraite vient de le fermer. |

**Statut** : `ended` si la fin est passée, `current` si elle est à venir, `unknown` sans fin connue (comme `lu_pep_legilux`).

Un mandat **sans fin connue** est écarté au-delà d'une durée propre à la fonction :

| Paramètre | Défaut | Pourquoi |
|---|---|---|
| `ambassadorMaxYears` | 6 | Un poste d'ambassadeur dure 3 à 4 ans. |
| `extraordinaryMaxYears` | 5 | Les nominations « en service extraordinaire » durent 4 ou 5 ans. |
| `cassationMaxYears` | 15 | On entre à la Cour de cassation en fin de carrière (limite d'âge vers 68 ans). |
| `memberMaxYears` | 40 | On entre au Conseil d'État ou à la Cour des comptes vers 25 ans. |
| `enterpriseMaxYears` | 6 | Les mandats au directoire et au conseil de surveillance d'un grand port maritime durent cinq ans. |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_jorf` |
| Plugin-Id | `fr-pep-jorf` |
| Type de source | `PLUGIN_MANAGED` : le plugin télécharge lui-même |
| Nature de la source | T4 – Journal officiel (`OFFICIAL_GAZETTE`) |
| Fréquence | 168 h |
| Paramètres | `{"cacheDir": "D:/test parser 2/cache/jorf", "since": "1990-01-01", "monthsAfterEnd": "12", "ambassadorMaxYears": "6", "extraordinaryMaxYears": "5", "cassationMaxYears": "15", "memberMaxYears": "40", "governmentMaxYears": "6", "enterpriseMaxYears": "6", "delayMs": "300"}` |

- `cacheDir` (obligatoire) : dossier du cache, à garder entre deux exécutions et à sauvegarder comme un dossier de données.
- `since` : publications plus anciennes ignorées. Après le 13/07/2025, l'archive complète n'est pas téléchargée.
- `indexUrl` (facultatif) : adresse de l'open data, `https://echanges.dila.gouv.fr/OPENDATA/JORF/` par défaut.

### Données brutes archivées

| Fichier | Contenu |
|---|---|
| `jorf-articles.zip` | Le XML officiel de chaque article gardé. Zip reproductible : une semaine sans nouvelle nomination donne la même empreinte, et l'exécution s'arrête en `UNCHANGED`. |
| `jorf-sources.csv` | Les fichiers de la DILA lus, et le nombre d'articles gardés dans chacun. |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-jorf-<prénom nom sans accents>` | Nom, prénom, nom de famille (mots en majuscules, sinon dernier mot), pays `fr`, fonctions, textes sources (Légifrance), topic `role.pep` (et `role.diplo` pour un ambassadeur) |
| `Position` | Une par fonction canonique (« Conseiller d'État », « Ambassadeur de France auprès de la République portugaise ») | Pays `fr`, institution, topic `gov.judicial`, `gov.national` ou `role.diplo` |
| `Occupancy` | Personne + institution (+ poste) + début | Début, fin, statut, textes sources |

## Limites connues

- **Départs sans décret** : un décès, une disponibilité ou un détachement n'apparaissent pas au JO. Le mandat reste `unknown` jusqu'aux durées maximales ci-dessus.
- **Identité** : les décrets ne donnent ni date de naissance ni identifiant. Deux homonymes dans la même institution seraient fusionnés.
- **Listes anciennes sans en-tête** : certaines nominations collectives des années 1990 donnent les noms sans la fonction dans l'article (« M. Dahan (Thierry). »). Elles sont citées dans le message de l'exécution (« textes sans tournure reconnue »).
- **Hors périmètre** : le secrétaire général du Conseil constitutionnel et le chef d'état-major de la Marine ne sont pas encore lus.

## Tests

`FrPepJorfParserTest`, sur 47 articles réels de 30 textes de 1991 à 2026 (`src/test/resources/jorf/jorf-articles.zip`). Il couvre :

- nominations simples, collectives et de magistrature ;
- promotions ;
- retraites, départs implicites, maintiens ;
- successeur au même poste d'ambassadeur ;
- faux positifs (représentant permanent de l'État dans une société, prédécesseur admis à la retraite) ;
- formats anciens ;
- fin de fonctions hors institution ;
- lecteur tar et choix des fichiers de la DILA.
