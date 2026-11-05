# nl-pep-government

Dataset `nl_pep_government` : ministre-président, ministres et secrétaires d'État (catégorie NL-a).

**Source** : pages officielles de `rijksoverheid.nl`. La page `https://www.rijksoverheid.nl/regering/bewindspersonen` liste chaque
membre (nom, fonction, lien) ; la fiche donne « Geboren op 25 maart 1987 in Veghel », « Partij: D66 » et la date de nomination
(rubrique « Loopbaan », ligne « Benoeming »).

**Type** : `PLUGIN_MANAGED`. Paramètres :

```json
{"listUrl": "https://www.rijksoverheid.nl/regering/bewindspersonen", "delayMs": "1000"}
```

**Résultat réel (11/2026)** : 28 membres, dont 2 sans date de naissance sur leur fiche. La page ne liste que les membres en fonction :
les anciens ministres restent publiés pendant la rétention du cœur.
