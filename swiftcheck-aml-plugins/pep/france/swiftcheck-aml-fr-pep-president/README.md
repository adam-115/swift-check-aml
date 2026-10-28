# fr_pep_president – Président de la République (France)

## Ce que fait ce parser

Il lit la **biographie officielle du Président de la République** sur elysee.fr et la convertit en entités FollowTheMoney (FtM). Le core la publie ensuite dans Yente.

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-a** « Président de la République, Premier ministre et membres du Gouvernement » (CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 a) |
| Liste officielle | Liste des fonctions PEP de la France, JO C/2023/724 |
| Fonction couverte | Président de la République (1 titulaire) |
| Classe de risque | 1 (la plus élevée) |
| Rétention | 12 mois : le Président sortant reste publié un an après la fin de ses fonctions (R. 561-18, rétention gérée par le core) |

Le Premier ministre et les membres du Gouvernement sont lus par [`fr_pep_government`](../swiftcheck-aml-fr-pep-government/README.md).

## Source

| | |
|---|---|
| Éditeur | Présidence de la République |
| Page | biographie officielle, ex. `https://www.elysee.fr/emmanuel-macron` |
| Format | page HTML |
| Ce qui est lu | nom (`og:title`), « président de la République depuis 2017 » (`og:description`), « Né en décembre 1977 à Amiens » (texte) |

**À chaque nouveau Président, l'adresse de la page change** : il faut la remplacer dans l'écran « Parsers ». Le Président sortant disparaît alors de la source, et le core le garde publié pendant la rétention.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_president` |
| Plugin-Id | `fr-pep-president` |
| Type de source | `HTTP_FILE` : le core télécharge la page, le plugin la lit seulement |
| Nature de la source | T5 – Page web officielle |
| Adresse | `https://www.elysee.fr/emmanuel-macron` |
| Fréquence | 168 h (une fois par semaine) |
| Rétention | 12 mois |
| Paramètres | aucun |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-president-<nom>` | nom, prénom, date de naissance (au mois près : `1977-12`), lieu de naissance, pays et nationalité `fr`, topic `role.pep` |
| `Position` | `fr-pep-president-president` | « Président de la République française », topics `gov.head`, `gov.national` |
| `Occupancy` | nom + `president` | lien personne → fonction, année de début, statut `current` |

## Règles et erreurs

- Une page qui ne mentionne pas « président(e) de la République » arrête l'exécution (FAILED) : mauvaise adresse, page d'erreur ou page changée.
- Sans fichier reçu (type `PLUGIN_MANAGED`), le plugin s'arrête avec un message qui demande de choisir `HTTP_FILE`.

## Limites connues

- La page ne donne la date de naissance qu'au mois près.
- Le début du mandat n'est connu qu'à l'année (« depuis 2017 »).

## Tests

`FrPepPresidentParserTest`, sur la page réelle (`src/test/resources/elysee-emmanuel-macron.html`, octobre 2026) : nom, naissance, début du mandat, refus d'une page qui n'est pas la biographie, entités écrites et `readSourceRecords`.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-president -am package
```

Copier ensuite `target/swiftcheck-aml-fr-pep-president-*.jar` dans le dossier des plugins.
