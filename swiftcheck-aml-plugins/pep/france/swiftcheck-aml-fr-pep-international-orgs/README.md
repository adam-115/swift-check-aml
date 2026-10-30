# fr_pep_international_orgs – Dirigeants des organisations internationales situées en France

## Ce que fait ce parser

Il lit la **page officielle** de chaque organisation internationale de la liste notifiée par la France. Il y trouve les titulaires des fonctions PEP de cette liste et les publie en entités FtM.

**Exécution réelle du 30/10/2026** : 28 dirigeants dans 8 organisations, en 9 s, 0 valeur rejetée. La 9e organisation configurée (Alliance Bioversity–CIAT) refuse le client du plugin (HTTP 403) ; avec sa page, le test sur pages réelles donne 40 dirigeants.

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-h** « Dirigeant d'une organisation internationale située en France » |
| Périmètre | Liste notifiée par la France à la Commission (JO C/2023/724, catégorie h) : 21 organisations, dont 3 sans fonction PEP (Agence OTAN de soutien et d'acquisition, PNUE, Organisation hydrographique internationale) |
| Source de la liste | Office des publications de l'UE (`publications.europa.eu/resource/oj/C_202300724`) ; EUR-Lex refuse les robots |
| Couverture | **Partielle** : 9 organisations lues sur 18 (voir « Non couvert ») |
| Rétention | Titulaires en poste ; un dirigeant qui quitte la page reste 12 mois (rétention du core) |

**Différence avec `lu_pep_international_orgs`** : le parser luxembourgeois ne lit qu'une page (la Cour AELE) et écrit les autres dirigeants dans son code. Ici, **aucun nom n'est écrit** dans le code ni dans la configuration : ils sont tous lus dans les pages à chaque exécution.

## Organisations lues

| Organisation | Page | Fonctions lues |
|---|---|---|
| OFAJ | `ofaj.org/institution/organigramme` | Les deux secrétaires généraux (français et allemand) |
| OEPP | `eppo.int/ABOUT_EPPO/eppo_secretariat` | Directeur général |
| OMSA | `woah.org/fr/…/direction-generale/` | Directeur général |
| Unesco | `unesco.org/fr/director-general` | Directeur général (titre de la page) |
| IPGRI (Alliance Bioversity–CIAT) | `alliancebioversityciat.org/who-we-are/governance` | Directeur, président et vice-président du conseil, membres du conseil |
| CGIAR | `cgiar.org/cgiar-board` | Directeur (Executive Managing Director), membres du conseil |
| CIHEAM | `ciheam.org/…/governance/` | Président du conseil, secrétaire général |
| BIPM | `bipm.org/fr/bipm-staff` | Directeur du BIPM |
| OIML | `oiml.org/fr/structure/biml` | Directeur du BIML |

## Non couvert (champ `pending` de la configuration)

Ces fonctions sont citées dans le message de **chaque exécution** :

- **OIF** : secrétaire générale, administrateur, cabinet, 18 directeurs. Organigramme introuvable (404).
- **CCNR** : secrétaire générale, adjointe, ingénieur en chef. Non nommés dans les pages lues.
- **CICR** (délégation régionale à Paris), **PAM** (France et Monaco), **BCEAO** (représentation en France) : pas de page nominative.
- **OACI** Bureau Europe et Atlantique Nord : page introuvable (404).
- **IIF** : directeur général et adjoint. Page de l'équipe sans nom dans le texte.
- **BEAC** : organigramme en image.
- **FMI** : le site refuse les robots (HTTP 403).
- Fonctions partielles :
  - OEPP : président, vice-président, adjoint ;
  - OMSA : directeurs généraux adjoints, directeur financier ;
  - Unesco : directeur général adjoint ;
  - CIHEAM : 13 délégués ;
  - BIPM : président du CIPM, par intérim depuis le 16/10/2026 ;
  - OIML : président du CIML.

Pour les ajouter, une organisation n'a besoin que d'une entrée dans la configuration, sans code.

## Configuration (`international-orgs.json`)

Fichier livré dans le plugin. Le paramètre `configFile` permet de le remplacer, par exemple pour corriger un motif après un changement de site, sans reconstruire le plugin.

```json
{ "key": "ofaj", "name": "Office franco-allemand pour la jeunesse (OFAJ)",
  "pages": [ { "url": "https://www.ofaj.org/institution/organigramme",
               "functions": [ { "label": "Secrétaire général", "pattern": "^{NAME}\\nSecrétaire générale?$" } ] } ] }
```

- **`label`** : la fonction telle qu'écrite dans la liste officielle.
- **`pattern`** : une expression régulière appliquée au **texte** de la page, une ligne par paragraphe, titre ou cellule (`PageText`).
  - Le nom est capturé dans le groupe `name`, ou dans `first` et `last` (OIML : prénom, NOM, fonction sur trois lignes).
  - `{NAME}` vaut deux à cinq mots commençant par une majuscule.
- **Titres** : « H.E. », « Dr », « docteure », « Mme »… sont retirés du nom.
- **Une personne par nom** : Julia Marton-Lefèvre, présidente du conseil d'IPGRI et membre de celui du CGIAR, a deux fonctions.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_international_orgs` |
| Plugin-Id | `fr-pep-international-orgs` |
| Type de source | `PLUGIN_MANAGED` : le plugin télécharge chaque page |
| Nature de la source | T5 – Page web |
| Fréquence | 168 h |
| Paramètres | `{"delayMs": "1000", "configFile": ""}` |

### Données brutes archivées

| Fichier | Contenu |
|---|---|
| `<clé>-<n>.html` | La page de chaque organisation (ex. `ofaj-1.html`) |
| `pages-en-echec.txt` | Les pages non téléchargées et la raison (HTTP 403…) |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-intorgs-<nom sans accents>` | Nom, fonctions (« Directeur général – Unesco »), pages sources, topic `role.pep` |
| `Position` | Organisation + fonction | Pays `fr` (siège ou bureau en France), organisation, topic `gov.igo` |
| `Occupancy` | Personne + organisation + fonction | Statut `current` |

Pas de nationalité ni de date de naissance : les pages ne les donnent pas.

## Erreurs

- **Page inaccessible** : elle est citée (« Pages en échec ») et les autres organisations sont publiées. Si beaucoup échouent, la baisse est arrêtée par le contrôle qualité du core (−20 %).
- **Fonction introuvable dans une page lue** : citée (« Fonctions non trouvées (page modifiée ?) »). Il faut alors revoir son motif.
- **Aucun dirigeant trouvé** : l'exécution échoue.

## Tests

`FrPepInternationalOrgsParserTest`, sur les 9 pages officielles réelles du 30/10/2026. Il couvre :

- la configuration fidèle à la liste : 21 organisations, dont 3 sans fonction ;
- chaque organisation et chaque forme de page ;
- la personne présente dans deux organisations, les titres retirés ;
- la page en échec, la page modifiée, l'absence de dirigeant ;
- la configuration remplacée par `configFile` ;
- le test de l'application (`readSourceRecords`).
