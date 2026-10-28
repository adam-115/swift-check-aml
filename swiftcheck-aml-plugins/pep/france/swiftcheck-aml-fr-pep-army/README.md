# fr_pep_army – Chefs d'état-major (France)

## Ce que fait ce parser

Il lit la biographie de chaque chef d'état-major sur defense.gouv.fr : **chef d'état-major des armées (CEMA)**, de l'**armée de Terre (CEMAT)**, de la **Marine (CEMM)** et de l'**armée de l'Air et de l'Espace (CEMAAE)**, avec leur date et leur lieu de naissance.

Mesure du 28/10/2026 : 3 chefs (Fabien Mandon, Jacques de Montgros, Jérôme Bellanger). **La Marine n'est pas couverte** : le site n'a pas de biographie à jour du CEMM (la seule page trouvée, un article du magazine Cols bleus, montre encore l'amiral Vaujour, remplacé par l'amiral Cluzel en juillet 2026).

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-f** « Ambassadeurs, chargés d'affaires et chefs d'état-major » (code de la défense, art. R*3121-1 et R*3121-25 ; CMF, art. R. 561-18) |
| Fonction couverte | Chef d'état-major des armées, de l'armée de Terre, de la Marine ou de l'armée de l'Air et de l'Espace : **partielle** (3 sur 4) |
| Classe de risque | 2 |
| Rétention | 12 mois : le chef sortant reste publié un an (rétention gérée par le core) |

## Source

Une page par fonction, configurée dans les paramètres. Le parser cherche trois tournures du texte :

| Tournure | Exemple réel |
|---|---|
| grade et nom | « le général d'armée aérienne Jérôme BELLANGER devient chef d'état-major … » |
| naissance | « Fabien Mandon est né le 19 octobre 1969 à Montmorency (95). » |
| prise de fonctions | « Le 26 juillet 2026, il est élevé … et nommé chef d'état-major de l'armée de Terre. » |

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_army` |
| Plugin-Id | `fr-pep-army` |
| Type de source | `PLUGIN_MANAGED` (une page par chef) |
| Fréquence | 168 h |
| Paramètres | `{"cemaUrl": "https://www.defense.gouv.fr/ema/chef-detat-major-armees", "cematUrl": "https://www.defense.gouv.fr/terre/chef-detat-major-larmee-terre/biographie-du-chef-detat-major-larmee-terre", "cemmUrl": "", "cemaaeUrl": "https://www.defense.gouv.fr/air/biographie-du-chef-detat-major-larmee-lair-lespace"}` |

Une adresse vide = fonction non couverte (citée dans le message de l'exécution). Dès qu'une biographie du CEMM est publiée, il suffit de renseigner `cemmUrl`.

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | nom + date de naissance | nom, prénom, grade (`title`), date et lieu de naissance, pays et nationalité `fr`, fonction, topic `role.pep` |
| `Position` | `cema`, `cemat`, `cemm`, `cemaae` | intitulé de la fonction, topics `gov.national`, `gov.security` |
| `Occupancy` | nom + naissance + fonction | date de prise de fonctions (si la page la donne), statut `current` |

## Règles et erreurs

- Page sans « général d'armée » ou « amiral » suivi d'un nom : l'exécution s'arrête et cite la fonction et l'adresse en cause.
- Aucune adresse configurée : l'exécution s'arrête.

## Tests

`FrPepArmyParserTest`, sur les trois biographies réelles (octobre 2026).
