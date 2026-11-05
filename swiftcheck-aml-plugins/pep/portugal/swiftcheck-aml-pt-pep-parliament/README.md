# pt-pep-parliament

Dataset `pt_pep_parliament` : députés de l'Assembleia da República et membres de son bureau (catégorie PT-b).

**Source** : données ouvertes du Parlement portugais, « Composição de órgãos ». Le plugin suit la page
`DAComposicaoOrgaos.aspx`, le dossier de la législature la plus récente, puis le lien signé vers
`OrgaoComposicaoXVII_json.txt` (≈ 5 Mo). Les liens changent : ils sont relus à chaque exécution. Aucune protection n'est
contournée (pages publiques, User-Agent générique, 1,5 s entre deux requêtes).

**Type** : `PLUGIN_MANAGED`. Paramètres :

```json
{"pageUrl": "https://www.parlamento.pt/Cidadania/Paginas/DAComposicaoOrgaos.aspx",
 "legislature": "XVII", "monthsAfterEnd": "12", "delayMs": "1500"}
```

`legislature` est facultatif (par défaut, la plus récente).

**Règles** : député en fonction = période `Efetivo*` sans fin ; mandat `Suspenso(Eleito)` sans fin = repris avec une note
(membre du Gouvernement, par exemple) ; ancien député = repris pendant `monthsAfterEnd` mois. Les suppléants et
démissionnaires qui n'ont jamais siégé sont ignorés. Fonctions du bureau (Presidente, Vice-Presidente, Secretário,
Vice-Secretário) ajoutées avec leurs dates.

**Limites** : pas de date de naissance ni de sexe dans le fichier. Résultat réel (11/2026) : 230 députés en fonction,
26 mandats suspendus, 15 anciens, 13 fonctions du bureau.
