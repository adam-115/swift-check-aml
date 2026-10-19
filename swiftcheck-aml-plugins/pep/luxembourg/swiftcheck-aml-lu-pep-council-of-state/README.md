# Plugin PEP Luxembourg : Membres du Conseil d'État

Module Maven : `swiftcheck-aml-lu-pep-council-of-state`  
Plugin-Id PF4J : `lu-pep-council-of-state`  
Version : `1.0.0`

---

## 1. Description et Catégorie PEP

Ce plugin extrait la composition des **membres du Conseil d'État** du Grand-Duché de Luxembourg.

Il couvre la catégorie officielle **LU-b** (Parlementaires et Conseil d'État) déclarée par le Luxembourg au Journal officiel de l'Union européenne (**JO C/2023/724**) en application de l'article 20 bis de la directive (UE) 2015/849 (LCB-FT).

### Fonction couverte :
- **LU-b** : *Membre du Conseil d'État* (incluant Président, Vice-Présidents et Conseillers).

---

## 2. Source de données

- **Source officielle :** Conseil d'État du Grand-Duché de Luxembourg.
- **Emplacement par défaut (`source_location`) :**  
  `https://conseil-etat.public.lu/fr/conseil-d-etat/historique/membresdepuis1857.html`
- **Type de source :** `HTTP_FILE` (page web officielle `T5`).
- **Périodicité de mise à jour conseillée :** Hebdomadaire (168 h) ou mensuelle.

---

## 3. Données extraites & Modèle FollowTheMoney

Pour chaque conseiller, le plugin produit :
1. **`Person`** :
   - `id` : `lu-pep-council-of-state-<slug>`
   - `name` : Prénom NOM (et NOM Prénom en alias)
   - `firstName`, `lastName`
   - `birthDate` : date de naissance exacte (`YYYY-MM-DD`)
   - `country` : `lu`
   - `topics` : `role.pep`
   - `sourceUrl` : URL de la page officielle du Conseil d'État.
2. **`Position`** :
   - `id` : `lu-pep-council-of-state-<slug-fonction>` (ex. `membre-du-conseil-d-etat`, `president-du-conseil-d-etat`, `vice-president-du-conseil-d-etat`)
   - `name` : intitulé officiel de la fonction
   - `country` : `lu`
   - `topics` : `gov.national`, `gov.legislative`
3. **`Occupancy`** :
   - `holder` : référence vers la `Person`
   - `post` : référence vers la `Position`
   - `startDate` : date de nomination ou d'assermentation (`YYYY-MM-DD`)
   - `endDate` : date de démission ou de cessation de mandat
   - `status` : `current` (en fonction) ou `ended` (mandat achevé)
   - `sourceUrl` : provenance officielle

---

## 4. Paramètres configurables

Dans le champ `parameters` du parser (format JSON) :
- `yearsAfterEnd` (entier, défaut `5`) : nombre d'années de rétention pour les anciens membres ayant démissionné ou quitté leurs fonctions. `0` pour retenir l'ensemble de l'historique.

Exemple :
```json
{
  "yearsAfterEnd": "5"
}
```

---

## 5. Compilation et Déploiement

### Compilation du plugin :
```bash
mvn clean package -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-council-of-state -am -DskipTests=false
```

Le JAR produit se trouve dans :  
`swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-council-of-state/target/swiftcheck-aml-lu-pep-council-of-state-0.0.1-SNAPSHOT.jar`
