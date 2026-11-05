# ie-pep-parliament

Dataset `ie_pep_parliament` : membres de l'Oireachtas (Dáil Éireann, Seanad Éireann) et du Gouvernement irlandais
(Taoiseach, ministres, ministres d'État, Ceann Comhairle, Cathaoirleach…), catégories IE-a et IE-b.

**Source** : API ouverte officielle `https://api.oireachtas.ie/v1` (sans clé). Le plugin lit `/houses`, garde le Dáil et le
Seanad en cours (et ceux terminés depuis moins de `monthsAfterEnd` mois), puis `/members?house_no=N&chamber=dail|seanad`
pour chacun. Chaque membre a ses mandats (dates, parti, circonscription ou panel) et ses fonctions (`offices`) avec dates.

**Type** : `PLUGIN_MANAGED`. Paramètres :

```json
{"apiUrl": "https://api.oireachtas.ie/v1", "monthsAfterEnd": "12", "delayMs": "1000"}
```

**Règles** : membre en fonction = mandat sans date de fin ; ancien membre = mandat terminé depuis moins de
`monthsAfterEnd` mois ; fonctions en cours ou terminées dans la période ; décédés ignorés. Identifiant = `pId` de
l'Oireachtas, stable d'une législature à l'autre.

**Limites** : ni date de naissance ni sexe dans l'API ; pas de fonction « Tánaiste » distincte. Résultat réel (11/2026) :
233 membres en fonction, 2 anciens, 52 fonctions de ministre ou de président de chambre.
