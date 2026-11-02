# fr_pep_state_enterprises – Dirigeants des entreprises publiques de l'État (France, FR-g)

## Ce que fait ce parser

Il produit les **dirigeants et administrateurs** (personnes physiques) des entreprises publiques de l'État :

- les entreprises de la page **« Les entreprises de l'APE »** (Agence des participations de l'État) ;
- les **EPIC nationaux** (natures juridiques 4110 et 4120 : ONF, RATP, BRGM, La Monnaie de Paris…).

Les dirigeants viennent de l'**API Recherche d'entreprises** : registre national des entreprises (RNE) et Sirene, Licence ouverte, gratuite et sans clé. Elle donne le nom, les prénoms, le mois de naissance et la qualité.

Mesure du 02/11/2026 : **1 671 dirigeants et administrateurs, 1 591 personnes**. Ils viennent de 60 entreprises de l'APE (sur 71 listées hors entités à une seule action) et de 96 EPIC nationaux, en 26 s.

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-g** – Dirigeant ou administrateur d'un établissement ou d'une société publique de l'État |
| Cadre légal | Directive (UE) 2015/849, art. 3, point 9 g) ; CMF, art. R. 561-18 |
| Rétention | 12 mois (gérée par le core) |

## Sources

| Source | Paramètre | Contenu |
|---|---|---|
| Page de l'APE | `apeUrl` | noms des 87 entreprises, par secteur (Énergie, Industrie, Services et finance, Transports, entités à une seule action) |
| `ape-entreprises.json` (dans le plugin) | – | SIREN de chaque nom de la page, vérifiés le 02/11/2026 ; raison quand il n'y en a pas (société étrangère, liquidée, SIREN à confirmer) |
| API Recherche d'entreprises | `apiUrl` | fiche par SIREN, et liste des entreprises actives par nature juridique (`natures`) |

**La page de l'APE est protégée par un contrôle anti-robots (Cloudflare)**. Elle répond à un navigateur, mais souvent pas au client du parser, et **ce contrôle n'est jamais contourné**. Quand elle ne répond pas, le périmètre est celui de `ape-entreprises.json`, et le message de l'exécution commence par « Page de l'APE inaccessible ». Quand elle répond, c'est elle qui fait foi :

- une entreprise retirée de la page disparaît du dataset ;
- un nom nouveau est signalé, à ajouter dans le fichier. Il n'est jamais deviné.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_state_enterprises` |
| Plugin-Id | `fr-pep-state-enterprises` |
| Type de source | `PLUGIN_MANAGED` (API) |
| Fréquence | 168 h |
| Paramètres | `apeUrl`, `apiUrl`, `natures` (`4110,4120`), `includeSingleShare` (`false`) |

`includeSingleShare` : `true` pour reprendre aussi les entités où l'État ne détient qu'une action (Bpifrance SA, SFIL, ArianeGroup…). Il ne les contrôle pas : elles sont exclues par défaut.

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | nom + prénoms + mois de naissance | nom, alias (tous les prénoms, nom de naissance), date de naissance `AAAA-MM`, fonctions (« Administrateur – Civipol »), topic `role.pep` |
| `Company` | SIREN | nom, SIREN (`registrationNumber`), contexte (« Portefeuille de l'APE – Énergie »), topic `gov.soe` (pas `role.pep` : la société n'est pas une PEP) |
| `Position` | SIREN + qualité | « Administrateur – Civipol », topic `gov.soe` |
| `Occupancy` | personne + SIREN + qualité | statut `current` |
| `Directorship` | personne + SIREN + qualité | lien personne → société, avec la qualité |

La même personne au conseil de deux entreprises est une seule `Person` avec deux fonctions (ex. Bernard Fontana : EDF et Thales).

Sont écartés :

- les personnes morales (État, collectivités, sociétés administratrices) ;
- les commissaires aux comptes ;
- les liquidateurs.

La qualité « Autre » du registre (fréquente pour les EPIC) devient « Dirigeant (qualité non précisée au registre) ».

## Limites connues

- **Aucune date de fonction dans le registre** : chaque dirigeant inscrit est repris comme en fonction. Un ancien administrateur non radié peut subsister, et le registre peut avoir quelques semaines de retard sur une nomination.
- **20 entreprises sans dirigeant au registre** : grands ports maritimes (Marseille, Dunkerque, Martinique…), Caisse nationale des autoroutes, FDPITMA, plusieurs EPIC. Leurs dirigeants sont nommés par décret : c'est l'extension prévue de `fr_pep_jorf`.
- **11 entreprises de l'APE sans SIREN français** :
  - sociétés de droit étranger : Airbus Group SE, KNDS NV, John Cockerill Defense, CFL, Société internationale de la Moselle ;
  - SIREN à confirmer, car plusieurs sociétés portent le même nom : Odas, SNPE, SOGEPA, TSA, Casino d'Aix-les-Bains, AREA.

  Le message de l'exécution les liste.

## Tests

`FrPepStateEnterprisesParserTest`, sur des extraits réels du 02/11/2026 : la page APE et les réponses de l'API pour Civipol, le grand port maritime de Marseille, la société aéroportuaire de Guadeloupe Pôle Caraïbes et trois EPIC. Les tests couvrent :

- la lecture des secteurs ;
- la présence de chaque nom de la page dans `ape-entreprises.json` ;
- les entités produites ;
- la qualité « Autre » ;
- les entités à une seule action ;
- les téléchargements page par page ;
- le repli quand la page est bloquée.
