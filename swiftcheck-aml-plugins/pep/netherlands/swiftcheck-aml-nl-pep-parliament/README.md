# nl-pep-parliament

Dataset `nl_pep_parliament` : membres de la Tweede Kamer et de l'Eerste Kamer (catégorie NL-b).

**Sources officielles**
- Tweede Kamer : API OData ouverte `https://gegevensmagazijn.tweedekamer.nl/OData/v4/2.0` (`Persoon` pour l'identité, la
  naissance et le décès ; `FractieZetelPersoon` pour les sièges avec dates et groupe).
- Eerste Kamer : liste officielle `https://www.eerstekamer.nl/alle_leden` (75 membres, groupe, naissance, ancienneté en jours :
  le début du mandat est la date du jour moins ces jours). Le nom complet et l'identifiant viennent de la fiche `Persoon` de
  même naissance et de même nom de famille (55 sur 75 à ce jour) ; les autres sont publiés depuis la liste.

**Type** : `PLUGIN_MANAGED`. Paramètres :

```json
{"odataUrl": "https://gegevensmagazijn.tweedekamer.nl/OData/v4/2.0",
 "senateUrl": "https://www.eerstekamer.nl/alle_leden", "monthsAfterEnd": "12", "delayMs": "500"}
```

**Règles** : siège en cours (sans fin) ou terminé depuis moins de `monthsAfterEnd` mois ; décédés ignorés. L'API ne dit pas dans
quelle chambre un ancien membre a siégé en dehors de la Tweede Kamer : les anciens sénateurs ne sont pas repris.

**Résultat réel (11/2026)** : 150 députés en fonction, 121 anciens de moins d'un an, 75 sénateurs. Avec `monthsAfterEnd = 600`
(dataset `nl_pep_parliament_history`) : environ 840 personnes, 35 décédés écartés.
