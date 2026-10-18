# lu_pep_government – Membres du gouvernement (Luxembourg)

## Ce que fait ce parser

Il lit la page officielle du gouvernement luxembourgeois et en extrait les **membres en fonction** : Premier ministre, ministres et secrétaires d'État, soit 15 personnes aujourd'hui. Il les convertit en entités FtM.

## Contexte

| | |
|---|---|
| Pays | Luxembourg |
| Catégorie PEP | **LU-a** « Chef de l'État, Premier ministre, ministres et secrétaires d'État » (directive (UE) 2015/849, art. 3, point 9 a) |
| Liste officielle | JO C/2023/724 |
| Fonction couverte | Membre du gouvernement (nommé selon l'article 76 de la Constitution) |
| Classe de risque | 1 (la plus élevée) |
| Rétention | 12 mois : **indispensable ici**. La page ne liste que les membres en fonction, sans date de fin. Un ministre qui quitte le gouvernement disparaît simplement de la page. Grâce à la rétention du core, il reste publié 12 mois. |

## Source

| | |
|---|---|
| Éditeur | Gouvernement du Grand-Duché de Luxembourg |
| Adresse | `https://gouvernement.lu/fr/gouvernement.html` |
| Format | Page HTML : le gouvernement ne publie pas cette liste en open data |
| Licence | Non précisée : citer « gouvernement.lu » |

La page contient un lien par membre. Le nom de famille y est écrit en majuscules :

```html
<a href="//gouvernement.lu/fr/gouvernement/luc-frieden.html" ...><span>Luc FRIEDEN</span></a>
```

Comme l'adresse se termine par `.html`, le core accepte une réponse HTML. Pour une source de données, une réponse HTML est normalement une erreur, par exemple une page de connexion.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `lu_pep_government` |
| Plugin-Id | `lu-pep-government` |
| Type de source | `HTTP_FILE`, **pas** « Géré par le plugin » : le plugin ne télécharge rien |
| Nature de la source | T5 – Page web officielle |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | aucun |
| Source PEP | « gouvernement.lu – membres du gouvernement » |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-government-<page du membre>`, ex. `lu-pep-government-luc-frieden` | nom, prénom (les mots en majuscules forment le nom), pays `lu`, lien vers sa page (`sourceUrl`), topic `role.pep` |
| `Position` | `lu-pep-government-member` (la même pour tous) | « Membre du gouvernement du Luxembourg », topics `gov.national`, `gov.executive` |
| `Occupancy` | page du membre + fonction | statut `current` |

L'ID repose sur l'identifiant de la page du membre sur gouvernement.lu. Il est stable tant que le site ne change pas ses adresses.

## Règles et erreurs

- Chaque membre apparaît dans plusieurs menus de la page : il n'est gardé qu'une fois.
- Le lien « anciens membres du gouvernement » est ignoré.
- **Page sans aucun membre** (le site a changé de structure) : l'exécution échoue (FAILED) au lieu de publier une liste vide. Yente garde alors la version précédente.
- En configuration `PLUGIN_MANAGED`, le plugin s'arrête avec un message qui demande de choisir `HTTP_FILE`.

## Limites connues

- La fonction exacte (Premier ministre, ministre de…, secrétaire d'État) n'est pas lue : tous sont « membre du gouvernement ».
- Ni date de naissance ni date d'entrée en fonction : la page ne les donne pas.
- Le Grand-Duc et les **anciens** membres du gouvernement ne sont pas couverts. Les sources sont dans « Sources PEP » (monarchie.lu, page des anciens membres, Legilux).
- Comme pour toute page web, une refonte du site peut casser le parser. L'échec est alors visible tout de suite (alerte FAILED).

## Tests

`LuPepGovernmentParserTest`, sur un extrait réel de la page (`src/test/resources/government.html`, 3 membres). Il vérifie :
- la personne et le nombre d'entités (7) ;
- un membre gardé une seule fois, sans le lien des anciens membres ;
- le découpage prénom / nom ;
- l'échec sur une page sans membre ;
- le message clair en configuration `PLUGIN_MANAGED`.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-government -am package
```
