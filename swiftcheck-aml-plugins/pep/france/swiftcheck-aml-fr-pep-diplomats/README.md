# fr_pep_diplomats – Ambassadeurs en poste (France)

## Ce que fait ce parser

Il lit les deux listes publiées par le **ministère de l'Europe et des Affaires étrangères** (MEAE) :

- la **liste des chefs de poste** : ambassadeurs, représentants permanents et chargés d'affaires, avec la date de prise de fonctions ;
- la **liste des ambassadeurs et ambassadrices thématiques** nommés en Conseil des ministres.

Il publie les titulaires en poste en entités FtM.

**Mesure du 30/10/2026** :

| Liste | Date de mise à jour | Contenu |
|---|---|---|
| Chefs de poste | 22/05/2026 | 176 en poste, dont 16 représentants permanents et 4 chargés d'affaires ; 7 postes sans titulaire |
| Ambassadeurs thématiques | 16/02/2026 | 18 |

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-f** « Ambassadeur ou chargé d'affaires » (CMF, art. R. 561-18) |
| Couverture | **Complète** pour les titulaires à la date de la liste |
| Complémentarité | [`fr_pep_jorf`](../swiftcheck-aml-fr-pep-jorf) lit les décrets de nomination. Il apporte les nominations parues **après** la mise à jour de la liste et les ambassadeurs **sortis** depuis moins d'un an. Cette liste apporte les titulaires nommés **avant** la période lue par le JO, sans incertitude sur leur statut. |
| Rétention | Titulaires en poste seulement ; un ambassadeur qui quitte la liste reste 12 mois (rétention du core) |

## Source

| | |
|---|---|
| Éditeur | Ministère de l'Europe et des Affaires étrangères, www.diplomatie.gouv.fr |
| Pages | `/fr/le-ministere/reseau-diplomatique/ambassades` et `/fr/le-ministere/reseau-diplomatique/ambassadeurs-et-ambassadrices-thematiques` |
| Fichiers | Un PDF cité par chaque page. Leur adresse change à chaque mise à jour (`260522_liste_chefs_de_poste.pdf`) : elle est **lue dans la page** à chaque exécution. |
| Licence | Licence Ouverte / Open Licence (Etalab 2.0), indiquée en pied de page du site |
| Robots | Pages et fichiers autorisés par `robots.txt` |

### Format des listes (`DiplomatListReader`)

```text
Allemagne BERLIN M. DELATTRE François 20 septembre 2022 DUE
Belgique RP UE BRUXELLES M. LEGLISE-COSTA Philippe 19 novembre 2017 DUE
Mali BAMAKO M. FOURRIERE Thibaut (chargé d'affaires) 15 septembre 2025 DAOI
Pologne VARSOVIE M. de MONTAIGNE de PONCINS Etienne 20 août 2023 DUE
Syrie DAMAS sis à BEYROUTH M. FAIVRE Jean-Baptiste (chargé d'affaires) 5 mai 2025 ANMO
JERUSALEM (FSLT) M. KASSIANIDES Nicolas 25 août 2023 ANMO
Afghanistan KABOUL AS                                   ← poste sans titulaire

Mme AMPROU Anne-Claire 01/11/2022 Ambassadrice pour la santé mondiale
M. FARACO Benoît 23/05/2025
Ambassadeur chargé des négociations sur le changement climatique pour les   ← fonction sur plusieurs lignes
énergies renouvelables et la prévention des risques climatiques
```

- **Pays et poste** : le poste commence au premier mot en majuscules. « RP … » désigne une représentation permanente.
- **Nom** : les mots en majuscules, avec les particules qui les précèdent (« de MONTAIGNE de PONCINS ») ; le reste est le prénom.
- **Poste sans titulaire** : ligne sans civilité, finie par le code de la direction géographique. Il est compté dans le message de l'exécution.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_diplomats` |
| Plugin-Id | `fr-pep-diplomats` |
| Type de source | `PLUGIN_MANAGED` : le plugin lit les pages, puis télécharge les PDF qu'elles citent |
| Nature de la source | T6 – Document PDF |
| Fréquence | 168 h |
| Paramètres | `{"embassiesUrl": "https://www.diplomatie.gouv.fr/fr/le-ministere/reseau-diplomatique/ambassades", "thematicUrl": "https://www.diplomatie.gouv.fr/fr/le-ministere/reseau-diplomatique/ambassadeurs-et-ambassadrices-thematiques", "maxListAgeDays": "200", "delayMs": "500"}` |

`maxListAgeDays` : au-delà, le message de l'exécution signale une liste ancienne. Le ministère la met à jour quelques fois par an, en général après les mouvements d'été.

### Données brutes archivées

| Fichier | Contenu |
|---|---|
| `ambassades.html`, `ambassadeurs-thematiques.html` | Les pages, qui prouvent quel PDF était cité |
| `chefs-de-poste.pdf`, `ambassadeurs-thematiques.pdf` | Les listes officielles |
| `sources.txt` | L'adresse des deux PDF, reprise en `sourceUrl` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-diplomats-<prénom nom sans accents>` | Nom, prénom, nom de famille, genre (civilité), pays `fr`, poste, topics `role.pep` et `role.diplo` |
| `Position` | Une par poste : « Ambassadeur de France – Allemagne (BERLIN) », « Représentant permanent de la France – ONU NEW YORK », « Chargé d'affaires – Mali (BAMAKO) », ou la fonction thématique | Pays `fr`, ministère, topic `role.diplo` |
| `Occupancy` | Personne + poste | Début (prise de fonctions ou nomination en Conseil des ministres), statut `current` |

## Erreurs

- Lien du PDF introuvable dans la page, ou fichier qui n'est pas un PDF : l'exécution échoue (page modifiée).
- Moins de 100 chefs de poste lus : l'exécution échoue (mise en page du PDF changée). Les lignes non lues sont citées.

## Limites connues

- **Décalage** : la liste a la date de sa dernière mise à jour. Un ambassadeur nommé depuis n'y est pas encore ; il est dans `fr_pep_jorf`. Exemple : Portugal, nouvelle nomination au JO en août 2026.
- **Pas de date de naissance** ni d'identifiant : l'ID repose sur le nom.
- Les consuls généraux ne sont pas PEP et ne sont pas lus.

## Tests

`FrPepDiplomatsParserTest`, sur les deux PDF et les deux pages réels du 30/10/2026. Il couvre :

- les comptes : 176 en poste, 4 chargés d'affaires, 7 postes sans titulaire, 18 thématiques ;
- les représentants permanents, les particules, les postes « sis à » et sans pays ;
- les fonctions thématiques sur deux lignes ;
- l'alerte de liste ancienne ;
- la recherche des liens PDF dans les pages ;
- le test de l'application (`readSourceRecords`) et la mise en page inconnue.
