# fr_pep_eu_institutions – Français dans les institutions de l'UE

## Ce que fait ce parser

Il lit les pages officielles des institutions de l'UE dont les fonctions sont PEP (liste de la Commission, JO C/2023/611), en dehors du Parlement européen et de la Commission. Il retient les membres **de nationalité française**, chacun avec la **raison** qui le dit français.

**Exécution réelle du 30/10/2026** : 124 membres lus, 9 Français retenus, en 11 s, 0 valeur rejetée. Le site du Conseil refuse le client du plugin (HTTP 403) ; avec ses pages, le test donne 10 Français.

| Institution | Français retenus (30/10/2026) | Raison |
|---|---|---|
| Cour de justice | Stéphane Gervasoni (juge), Jean Richard de la Tour (avocat général), Stéphanie Laulhé Shaelou (avocate générale) | Née ou né en France ; double nationalité écrite |
| Tribunal | Laurent Truchot, Hervé Cassagnabère, Raphaël Meyer | Né en France ; carrière en France (M. Meyer, né à Zurich) |
| Cour des comptes européenne | Pierre Moscovici | Pays indiqué par la Cour |
| BCE | Christine Lagarde (présidente), Denis Beau (conseil de surveillance) | Lieu de naissance du CV ; représentant de la Banque de France |
| Conseil de l'UE | Thérèse Blanchet (secrétaire générale) | « Nationalité : française » |

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-EU** « Français exerçant une autre fonction PEP dans une institution de l'UE » |
| Fonctions (C/2023/611) | Juges, avocats généraux et greffier de la CJUE ; membres de la Cour des comptes européenne ; directoire et conseil de surveillance prudentielle de la BCE ; secrétaire général du Conseil |
| Déjà couvert ailleurs | Députés européens (`fr_pep_europarl`), commissaire (`fr_pep_eu_commission`), gouverneur de la Banque de France au Conseil des gouverneurs de la BCE (listes nationales) |
| Couverture | **Partielle** : les chefs des délégations de l'UE (SEAE) ne sont pas lus, faute de nationalité publiée |

## Sources

| Fichier brut | Source | Ce qu'elle donne |
|---|---|---|
| `cjue-cour.html`, `cjue-tribunal.html` | curia.europa.eu, membres de la Cour et du Tribunal | Nom, fonction, biographie. **Pas de nationalité.** |
| `eca-composition.pdf` | eca.europa.eu, « Composition » (PDF) | Chaque membre et **son pays**. La page des membres est construite en JavaScript ; le PDF lié depuis la page est lisible. |
| `bce-directoire.html`, `bce-cv-<nom>.html` | ecb.europa.eu, directoire et CV de chaque membre | Fonction ; **lieu de naissance** dans le CV |
| `bce-surveillance.html` | bankingsupervision.europa.eu | Chaque représentant national et son autorité |
| `conseil-sg.html`, `conseil-sg-biographie.html` | consilium.europa.eu | Nom ; **« Nationalité : française »** |
| `sources-en-echec.txt` | — | Sources non téléchargées et raison |

Chaque adresse peut être remplacée par un paramètre : `cjUrl`, `gcUrl`, `ecaUrl`, `ecbBoardUrl`, `ssmUrl`, `councilSgUrl`, `councilSgBioUrl`.

## Nationalité : écrite ou déduite

Une seule source écrit la nationalité (le Conseil) et une autre le pays de chaque membre (la Cour des comptes). Pour la CJUE et la BCE, elle est **déduite**. La raison est toujours écrite dans `Person.notes` (« Nationalité française : … ») et dans le message de l'exécution.

Règle sur les biographies de la CJUE (`EuReaders.frenchFromBiography`), dans l'ordre :

1. la nationalité française est écrite (« possède la double nationalité chypriote et française ») ;
2. la naissance en France (« Né en 1962 à Paris (France) ») ;
3. la carrière en France : au moins 4 mentions « (France) », et deux fois plus que tout autre pays.

Mesure du 30/10/2026 sur les 90 membres de la Cour et du Tribunal :

- la règle trouve les 6 Français ;
- elle écarte 5 juges étrangers formés en France (Luxembourg, Chypre, Pays-Bas…), qui ont 1 à 3 mentions « (France) ».

**Limites** : un juge français né à l'étranger avec une carrière surtout européenne pourrait être manqué, et un étranger né en France retenu. C'est pourquoi la raison est publiée, pour que l'analyste puisse vérifier.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_eu_institutions` |
| Plugin-Id | `fr-pep-eu-institutions` |
| Type de source | `PLUGIN_MANAGED` |
| Nature de la source | T5 – Page web |
| Fréquence | 168 h |
| Paramètres | `{"delayMs": "500"}`, et les adresses ci-dessus si besoin |

PDFBox est embarqué dans le jar (lecture du PDF de la Cour des comptes).

## Ce qui est produit (FtM)

| Entité | Contenu |
|---|---|
| `Person` | Nom, nationalité `fr`, fonction, `notes` = raison de la nationalité, page source, topic `role.pep` |
| `Position` | Fonction (« Juge au Tribunal », « Présidente de la BCE »…), institution, topic `gov.igo` |
| `Occupancy` | Statut `current` |

Seuls les Français sont écrits ; les autres membres sont seulement comptés dans le message.

## Erreurs

- **Source inaccessible** : elle est citée, et les autres sont lues. consilium.europa.eu répond HTTP 403 au client du plugin (alors qu'un navigateur ou curl lisent la page) ; ce filtre n'est pas contourné.
- **Aucun membre lu dans aucune source** : l'exécution échoue.

## Tests

`FrPepEuInstitutionsParserTest`, sur les pages et le PDF réels du 30/10/2026. Il couvre :

- la liste exacte des 10 Français, avec la raison de chacun ;
- les fonctions ;
- les juges étrangers formés en France, qui sont écartés ;
- la règle sur les biographies ;
- les liens des CV de la BCE ;
- le test de l'application (`readSourceRecords`).
