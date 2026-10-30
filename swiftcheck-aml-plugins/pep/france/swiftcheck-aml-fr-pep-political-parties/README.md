# fr_pep_political_parties – Dirigeants des partis politiques français (Wikidata)

## Ce que fait ce parser

Il interroge Wikidata pour trouver les dirigeants vivants des partis politiques français actifs : président (P488), secrétaire général (P3975) et « directeur ou dirigeant » (P1037). Il garde les mandats en cours et ceux terminés depuis moins de 12 mois.

Mesure du 29/10/2026 : 124 partis, 198 déclarations, 149 mandats en cours, 137 dirigeants, 0 valeur rejetée (environ 7 s).

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-c** « Membres des organes exécutifs des partis politiques (loi n° 88-27 du 11 mars 1988) » |
| Fonction couverte | Président ou membre de l'organe exécutif d'un parti politique : **partielle** |
| Pourquoi Wikidata | Aucune source officielle ne publie les dirigeants des partis : la CNCCFP ne donne que les mandataires financiers, le RNA ne publie pas les dirigeants. Wikidata est structuré, sourcé et sous licence CC0 : c'est la première source à utiliser avant toute recherche web. |
| Rétention | Mandats terminés depuis moins de 12 mois (`monthsAfterEnd`), puis rétention du core (12 mois) |

## Quels partis ?

Tout élément « parti politique » (Q7278 ou sous-classe) dont le pays (P17) est la France, sauf :

- les partis dissous (P576) ou qui ont un successeur (P156, P1366) : par exemple l'ancien « Front national », suivi du Rassemblement national ;
- les partis transfrontaliers (un autre pays en P17) : Parti nationaliste basque, Sortu, Eusko Alkartasuna, dont les dirigeants sont espagnols.

## Quel dirigeant est « actuel » ?

Wikidata garde l'historique, souvent sans date de fin. Exemple pour Renaissance : Christophe Castaner, Stanislas Guerini, Stéphane Séjourné et Gabriel Attal sont tous « président » sans date de fin. Les règles s'appliquent parti par parti et rôle par rôle (`FrPepPoliticalPartiesParser.select`) :

1. un mandat terminé est gardé comme ancien s'il a pris fin depuis moins de 12 mois, sinon il est écarté ;
2. si le rôle a une déclaration au rang **préféré** (convention Wikidata pour la valeur actuelle), seule celle-ci est en cours ;
3. sinon, un mandat sans fin est remplacé si un autre mandat du même rôle a commencé après lui. Un mandat sans date de début est gardé : mieux vaut une alerte de trop qu'un dirigeant manqué.

Les noms sont demandés en `fr`, puis `mul`, puis `en`. Beaucoup de personnes n'ont plus de libellé qu'en `mul` (par exemple Jordan Bardella) : sans `mul`, le service renvoie le QID à la place du nom.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_political_parties` |
| Plugin-Id | `fr-pep-political-parties` |
| Type de source | `PLUGIN_MANAGED` (point SPARQL de Wikidata) |
| Nature de la source | T3 – SPARQL |
| Fréquence | 168 h |
| Paramètres | `{"endpoint": "https://query.wikidata.org/sparql", "country": "Q142", "monthsAfterEnd": "12"}` |

La réponse brute est déposée dans `rawDir` sous le nom `leaders.json` ; le core la signe et l'archive.

## Ce qui est produit (FtM)

- `Person` (`role.pep`) : nom, QID (`wikidataId`), date de naissance à la précision de Wikidata, genre, pays `fr`, parti (`political`), fonction (« Président ou présidente – Les Républicains » ; « Ancien : … » pour un mandat terminé) ;
- `Position` : une par parti et par rôle, avec le QID du parti ;
- `Occupancy` : le mandat, avec ses dates et son statut (`current` ou `ended`).

Les identifiants reprennent les QID Wikidata (préfixe `fr-pep-parties`).

## Erreurs

- Wikidata répond autre chose que HTTP 200 : trois tentatives pour 429 et 5xx, puis échec de l'exécution.
- Aucun mandat en cours dans la réponse : exception (réponse vide ou format changé), rien n'est publié.

## Limites connues

- **Couverture partielle** : seuls le président, le secrétaire général et le dirigeant principal sont décrits. Les bureaux nationaux, comités exécutifs et trésoriers n'y sont pas.
- **Données collaboratives** : un dirigeant ancien sans date de fin et sans successeur daté reste en cours (par exemple des petits partis peu suivis). Les dates de début ou de fin à l'année près sont écrites au 1er janvier.
- Un parti absent de Wikidata, ou sans dirigeant renseigné, n'est pas couvert.

## Tests

`FrPepPoliticalPartiesParserTest`, sur la réponse réelle du 29/10/2026 (`src/test/resources/wikidata/leaders.json`) : rang préféré (Renaissance, LR, RN, Écologistes), libellé `mul` (Bardella), rôles P3975 et P1037 (LFI, PCF), partis exclus (basques, ancien FN), règles de sélection sur des cas construits, test de l'application (`readSourceRecords`), réponse vide et requête protégée contre un paramètre qui n'est pas un QID.
