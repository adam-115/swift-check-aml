# fr-pep-rne-elected

Dataset `fr_pep_rne_elected` : élus locaux du Répertoire national des élus (ministère de l'Intérieur, data.gouv.fr,
Licence ouverte), au-delà des chefs d'exécutif déjà repris par `fr_pep_local_executives` et
`fr_pep_local_executives_all` :

- maires délégués et adjoints au maire (toutes communes) ;
- membres du bureau des intercommunalités (vice-présidents, membres du bureau) ;
- conseillers départementaux, conseillers régionaux, élus des collectivités à statut particulier ;
- conseillers municipaux sans fonction : seulement si `includeCouncillors` vaut `true` (357 000 personnes).

Les maires et les présidents de conseil communautaire, départemental, régional ou d'assemblée sont ignorés : ils sont
dans l'autre plugin. Identité = nom, prénom, date de naissance.

**Type** : `PLUGIN_MANAGED` (66 Mo à télécharger). Paramètres :

```json
{"municipalUrl": "https://www.data.gouv.fr/api/1/datasets/r/d5f400de-ae3f-4966-8cb6-a85c70c6c24a",
 "epciUrl": "https://www.data.gouv.fr/api/1/datasets/r/41d95d7d-b172-4636-ac44-32656367cdc7",
 "departmentsUrl": "https://www.data.gouv.fr/api/1/datasets/r/601ef073-d986-4582-8e1a-ed14dc857fba",
 "regionsUrl": "https://www.data.gouv.fr/api/1/datasets/r/430e13f9-834b-4411-a1a8-da0b4b6e715c",
 "specialUrl": "https://www.data.gouv.fr/api/1/datasets/r/a595be27-cfab-4810-b9d4-22e193bffe35",
 "includeCouncillors": "false"}
```

Résultat réel (fichiers du 11/08/2026) : 157 901 personnes, 171 548 mandats, mémoire de l'ordre de 1 Go à prévoir.
