# fr_pep_government – Membres du Gouvernement (France)

## Ce que fait ce parser

Il lit l'**open data de la HATVP** (Haute Autorité pour la transparence de la vie publique), où chaque membre du Gouvernement dépose ses déclarations. Il produit le Premier ministre, les ministres, les ministres délégués et les secrétaires d'État **en fonction**, ainsi que les **anciens membres** encore listés, avec leur date de naissance et les dates de leur fonction.

Mesure du 28/10/2026 : 35 membres en fonction (identiques à la composition publiée sur info.gouv.fr) et 1 ancien membre ; 35 dates de naissance sur 36.

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-a** « Président de la République, Premier ministre et membres du Gouvernement » (CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 a) |
| Fonctions couvertes | Premier ministre, ministre ou secrétaire d'État (complète) ; Ancien membre du Gouvernement (partielle) |
| Classe de risque | 1 |
| Rétention | 12 mois : un ancien membre dont la fin de fonctions date de plus de 12 mois est écarté (`monthsAfterEnd`) ; un membre qui disparaît de la liste reste publié 12 mois par le core |

Pourquoi pas info.gouv.fr : la page « Composition du Gouvernement » refuse l'accès aux robots (HTTP 403, protection Cloudflare). Le plugin ne contourne pas cette protection.

## Source

| | |
|---|---|
| Éditeur | Haute Autorité pour la transparence de la vie publique |
| Liste | `https://www.hatvp.fr/livraison/opendata/liste.csv` (≈ 3,3 Mo, séparateur `;`, UTF-8) : une ligne par déclaration |
| Déclarations | `https://www.hatvp.fr/livraison/dossiers/<fichier>.xml` (colonne `open_data`) |
| Licence | Licence Ouverte / Open Licence (Etalab 2.0) : citer « HATVP, www.hatvp.fr » |

Lignes gardées : `type_mandat = gouvernement`. La colonne `qualite` donne la fonction ; « Ancien … » ou une déclaration de fin de fonctions (`dspfm`) indique un ancien membre.

```csv
civilite;prenom;nom;classement;type_mandat;qualite;type_document;departement;date_publication;date_depot;nom_fichier;url_dossier;open_data;…
M.;Sébastien;LECORNU;lecornuaaasebastien5183;gouvernement;Premier ministre;di;;2026-04-20;2025-11-13;…;/pages_nominatives/lecornu-sebastien;lecornu-sebastien-di34789-gouvernement.xml;…
```

Dans la déclaration XML, la partie `<general>` donne `dateDebutMandat`, `dateFinMandat` et `declarant/dateNaissance`.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_government` |
| Plugin-Id | `fr-pep-government` |
| Type de source | `PLUGIN_MANAGED` : le plugin télécharge la liste puis une déclaration par membre (≈ 37 fichiers, pause de 300 ms) et les dépose dans `rawDir` |
| Nature de la source | T7 – Registre de déclarations |
| Fréquence | 24 h |
| Paramètres | `{"listUrl": "https://www.hatvp.fr/livraison/opendata/liste.csv", "declarationsUrl": "https://www.hatvp.fr/livraison/dossiers/", "delayMs": "300", "monthsAfterEnd": "12"}` |

Le plugin s'identifie avec le User-Agent `SwiftCheck-AML/1.0`.

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-government-<page HATVP>` (ex. `lecornu-sebastien`) | nom, prénom, date de naissance, genre, pays et nationalité `fr`, fonction, lien vers la page HATVP, topic `role.pep` |
| `Position` | intitulé de la fonction | ex. « Ministre de l'intérieur », topics `gov.executive`, `gov.national` (et `gov.head` pour le Premier ministre) |
| `Occupancy` | page + intitulé | lien personne → fonction, début, fin (ancien membre), statut `current` / `ended` |

## Règles et erreurs

- **La liste décide qui est en fonction.** Après un remaniement, la dernière déclaration publiée d'un membre resté au Gouvernement peut porter sur sa fonction précédente, avec une date de fin (cas réel : Maud Bregeon, Marie-Pierre Vedrenne, 26/02/2026). Il reste alors « en fonction », sans les dates de l'ancienne fonction.
- Déclaration choisie pour les dates : celle de fin de fonctions si elle est publiée, sinon la plus récente.
- Déclaration pas encore publiée ou non téléchargée : le membre est gardé sans date, et cité dans le message de l'exécution.
- Paramètre `listUrl` ou `declarationsUrl` absent, liste sans aucune ligne « gouvernement », ou aucun membre en fonction : l'exécution s'arrête (FAILED).
- Date de fin illisible dans une déclaration : l'exécution s'arrête (un ancien membre ne doit pas passer pour un membre en fonction).

## Limites connues

- Un nouveau membre n'apparaît qu'après le dépôt de sa première déclaration (dans les deux mois de sa nomination), et ses dates qu'après sa publication (plusieurs mois). Pour combler ce délai : le Journal officiel (`fr_pep_jorf`, lot 2) ou un accès autorisé à info.gouv.fr.
- La HATVP ne donne pas le lieu de naissance.
- Combien de temps un ancien membre reste dans la liste HATVP n'est pas documenté ; au-delà, la rétention du core prend le relais.

## Tests

`FrPepGovernmentParserTest`, sur un extrait réel (`src/test/resources/hatvp/` : liste avec Sébastien Lecornu, Laurent Panifous, Michel Fournier et un député, et deux déclarations XML, 27/10/2026) : lecture de la liste, dates des déclarations, membres en fonction et anciens, rétention, cas du remaniement, téléchargement simulé avec une erreur, `readSourceRecords`, paramètres manquants.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-government -am package
```

Copier ensuite `target/swiftcheck-aml-fr-pep-government-*.jar` dans le dossier des plugins.
