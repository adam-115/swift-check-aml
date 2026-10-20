# Plugin PEP Luxembourg : Membre luxembourgeois de la Commission européenne

Module Maven : `swiftcheck-aml-lu-pep-eu-commission`  
Plugin-Id PF4J : `lu-pep-eu-commission`  
Version : `1.0.0`

---

## 1. Description et Catégorie PEP

Ce plugin extrait le **Commissaire européen luxembourgeois** ainsi que les anciens commissaires récents (dans la limite de la période de rétention PEP).

Il couvre la catégorie officielle **LU-EU** (*Luxembourgeois exerçant une fonction européenne*) déclarée par le Grand-Duché de Luxembourg au Journal officiel de l'Union européenne (**JO C/2023/724**) en application de la directive (UE) 2015/849 (LCB-FT).

### Fonction couverte :
- **LU-EU** : *Membre luxembourgeois de la Commission européenne* (incluant Président et Commissaires).

---

## 2. Source de données et Résolution Dynamique

- **Source officielle :** Commission européenne (Collège des commissaires).
- **Emplacement par défaut (`source_location`) :**  
  `https://commission.europa.eu/about/organisation/college-commissioners_fr`
- **Type de source :** `HTTP_FILE` (page web officielle `T5`).
- **Résolution dynamique :**  
  L'URL source est extraite en temps réel depuis le paramètre `sourceUrl` (ou `url`, `sourceLocation`) de l'objet `ParserContext ctx` passé à l'exécution de `parse(ctx)`. L'URL par défaut ne sert que de repli de sécurité si aucun paramètre n'est spécifié.

---

## 3. Données extraites & Modèle FollowTheMoney

Pour chaque commissaire, le plugin produit :
1. **`Person`** :
   - `id` : `lu-pep-eu-commission-<slug>` (ex. `lu-pep-eu-commission-christophe-hansen`)
   - `name` : Prénom Nom
   - `firstName`, `lastName`
   - `birthDate` : date de naissance (`YYYY-MM-DD`)
   - `country` : `lu`
   - `topics` : `role.pep`
   - `sourceUrl` : URL dynamique issue de `ParserContext`
2. **`Position`** :
   - `id` : `lu-pep-eu-commission-commissaire-europeen-luxembourgeois`
   - `name` : `Membre luxembourgeois de la Commission européenne`
   - `country` : `lu`
   - `topics` : `gov.national`, `gov.executive`
3. **`Occupancy`** :
   - `holder` : référence vers la `Person`
   - `post` : référence vers la `Position`
   - `startDate` : date de début de mandat
   - `endDate` : date de fin de mandat (ou null si en fonction)
   - `status` : `current` ou `ended`
   - `description` : portefeuille ministériel européen
   - `sourceUrl` : URL dynamique

---

## 4. Paramètres configurables

Dans le champ `parameters` du parser (format JSON) :
- `sourceUrl` : URL officielle de la source téléchargée par le core.
- `yearsAfterEnd` (défaut `5`) : nombre d'années de rétention pour les anciens commissaires. `0` pour conserver l'ensemble de l'historique disponible.
