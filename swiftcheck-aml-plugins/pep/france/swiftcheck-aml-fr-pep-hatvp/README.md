# fr-pep-hatvp

Dataset `fr_pep_hatvp` : personnes assujetties à la HATVP, d'après la liste officielle des déclarations
(`https://www.hatvp.fr/livraison/opendata/liste.csv`, Licence ouverte).

**Type** : `HTTP_FILE` (le cœur télécharge `liste.csv`, 3 Mo). Paramètre facultatif :

```json
{"types": "commune,epci,departement,region,ctsp,autre"}
```

`types` filtre sur la colonne `type_mandat` (`gouvernement`, `president`, `depute`, `senateur`, `europe`, `commune`,
`epci`, `departement`, `region`, `ctsp`, `autre`) ; tous par défaut.

Le fichier a une ligne par déclaration : le plugin regroupe par `classement` (clé stable de la HATVP) puis par fonction.
Une qualité « Ancien … » est enregistrée comme fonction terminée. Photo officielle ajoutée quand `url_photo` est donnée.

**Limites** : ni date de naissance ni date de début (elles sont dans les déclarations XML, non lues). Résultat réel
(11/2026) : 7 085 personnes, 7 905 fonctions. Recoupe `fr_pep_government`, `fr_pep_national_assembly` et `fr_pep_senate`
avec un autre préfixe d'identifiants.
