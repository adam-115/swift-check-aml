# Conception – `fr_pep_jorf` (Journal officiel via l'API Légifrance)

Document de travail, 28/10/2026. À valider avant d'écrire le code (règle du plan : analyse, proposition validée, puis code).

## 1. Objectif

Reconstituer, à partir des décrets publiés au Journal officiel, **qui occupe aujourd'hui** (ou a quitté depuis moins de 12 mois) les fonctions PEP françaises qui n'ont **pas de liste nominative publique** :

| Catégorie | Fonction (R. 561-18 CMF, JO C/2023/724) | Pourquoi le JO |
|---|---|---|
| FR-d | Membres du Conseil d'État (art. L. 121-2 CJA), sauf conseillers en service extraordinaire sans fonction juridictionnelle | aucun annuaire nominatif sur conseil-etat.fr |
| FR-d | Membres de la Cour de cassation (art. R. 421-1 COJ, 1° à 4° et 6° à 9°) | courdecassation.fr bloque les robots |
| FR-d | Secrétaire général du Conseil constitutionnel | non nommé sur le site |
| FR-e | Magistrats de la Cour des comptes (art. L. 112-1 CJF) | pas de liste nominative |
| FR-f | Ambassadeurs et chargés d'affaires ; chefs d'état-major | lot suivant (voir § 9) |
| FR-g | Dirigeants d'entreprises publiques nommés par décret | lot suivant (voir § 9) |

Modèle : `lu_pep_legilux` (Luxembourg), qui fait la même chose avec Legilux depuis 2017.

## 2. Source : API Légifrance (PISTE)

| | |
|---|---|
| Éditeur | DILA, via le portail PISTE (inscription gratuite, application « Légifrance ») |
| Authentification | OAuth2 *client credentials* : `POST https://oauth.piste.gouv.fr/api/oauth/token` (`grant_type=client_credentials`, `client_id`, `client_secret`, `scope=openid`) → jeton valable environ 1 h |
| Base | `https://api.piste.gouv.fr/dila/legifrance/lf-engine-app` |
| Recherche | `POST /search` avec `{"fond": "JORF", "recherche": {...}}` |
| Texte | `POST /consult/jorf` avec `{"textCid": "JORFTEXT…"}` → titre, NOR, date de parution, articles |
| Licence | Licence ouverte : citer « Journal officiel de la République française, Légifrance » |

Corps de recherche prévu (format relevé dans un client open source de l'API ; **à confirmer sur le swagger PISTE** dès que le compte existe) :

```json
{
  "fond": "JORF",
  "recherche": {
    "champs": [{ "typeChamp": "TITLE", "operateur": "ET",
                 "criteres": [{ "typeRecherche": "TOUS_LES_MOTS_DANS_UN_CHAMP", "valeur": "nomination Conseil d'Etat", "operateur": "ET" }] }],
    "filtres": [{ "facette": "NATURE", "valeurs": ["DECRET"] },
                { "facette": "DATE_PUBLICATION", "dates": { "start": "2026-01-01", "end": "2026-12-31" } }],
    "operateur": "ET", "pageNumber": 1, "pageSize": 100,
    "sort": "PUBLICATION_DATE_ASC", "typePagination": "DEFAUT"
  }
}
```

Réponse : `totalResultNumber`, `results[]` avec `titles[].cid`, `titles[].title`, `nature`, `nor`, `datePublication`, `dateSignature`.

## 3. Requêtes

Une famille de requêtes par corps, découpée **par année de publication** (pour rester sous les limites de pagination), du `since` (paramètre) à aujourd'hui :

| Corps | Mots du titre (nomination / entrée) | Mots du titre (sortie) |
|---|---|---|
| Conseil d'État | « nomination Conseil d'Etat », « promotion … », « titularisation … » | « cessation de fonctions … Conseil d'Etat », « admission à la retraite … », « honorariat » |
| Cour de cassation | « nomination magistrats Cour de cassation », « nomination … procureur général près la Cour de cassation » | « admission à la retraite … magistrat », « cessation … » |
| Cour des comptes | « nomination Cour des comptes » | « admission à la retraite … Cour des comptes », « cessation … » |
| Conseil constitutionnel | « nomination secrétaire général du Conseil constitutionnel » | (nomination du successeur) |

`since` par défaut : **1995**. Un magistrat nommé auditeur ou conseiller référendaire en 1995 peut encore être en fonction en 2026 ; la date sera ajustée après la mesure des volumes (§ 8).

Exemples de titres réels (fichier JORF du 27/10/2026) :

```text
Décret du 26 octobre 2026 portant nomination du directeur général du centre hospitalier universitaire de Lille (Nord) - M. BOSSARD (Olivier)
Arrêté du 26 octobre 2026 portant cessation de fonctions (directions départementales interministérielles)
```

## 4. Lecture des textes

1. **Filtrer par le titre** (« portant nomination », « cessation », « admission à la retraite »…) et par le corps.
2. **Nom** : d'abord dans le titre (« - M. BOSSARD (Olivier) », « - Mme X (Y) ») ; sinon dans les articles :
   « M. Jean DUPONT, conseiller d'Etat, est nommé président de la section … », « Mme Marie MARTIN, conseillère référendaire, est nommée conseillère … ».
   Comme pour Legilux : les mots en majuscules forment le nom, une phrase par personne, les décrets collectifs (« sont nommés ») donnent plusieurs événements.
3. **Fonction** : le grade ou la fonction après « est nommé(e) » (conseiller d'État, maître des requêtes, président de chambre, avocat général…), rattachée au corps.
4. **Date d'effet** : « à compter du … » si présent, sinon date de signature du décret.

## 5. Reconstitution des carrières

Pour chaque personne et chaque corps, les événements triés par date :

| Dernier événement | Statut |
|---|---|
| nomination, promotion, titularisation, maintien en activité | `current` |
| cessation, admission à la retraite, honorariat, radiation, nomination dans un autre corps | `ended` (fin = date d'effet) |
| nomination très ancienne sans autre acte | `unknown`, gardé tant que le dernier acte a moins de `maxYearsWithoutAct` ans (40 par défaut) |

Paramètre `monthsAfterEnd` (12) : un mandat terminé depuis plus longtemps est écarté (R. 561-18).

## 6. Entités produites

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-jorf-<prénom nom normalisés>` | nom, prénom, genre (M. / Mme), pays `fr`, fonctions, décrets sources (`sourceUrl` Légifrance), topic `role.pep` |
| `Position` | une par fonction et corps | ex. « Conseiller d'État », « Avocat général à la Cour de cassation », topics `gov.national`, `gov.judicial` (`gov.financial` pour la Cour des comptes) |
| `Occupancy` | personne + fonction + date de début | début, fin, statut, décret de nomination |

**Limite assumée** : les décrets ne donnent pas la date de naissance. L'ID repose sur le nom ; deux homonymes dans le même corps seraient fusionnés (rare, signalé dans le message de l'exécution).

## 7. Exécution et données brutes

- Type **`PLUGIN_MANAGED`**, fréquence **168 h** (hebdomadaire), comme `lu_pep_legilux`.
- Le plugin reste **sans état** : chaque exécution relit toute la période depuis `since`.
- Fichiers déposés dans `rawDir` (signés et archivés par le core) : `jorf-recherches.json` (réponses de `/search`) et `jorf-textes.zip` (réponse de `/consult/jorf` par texte), construits de façon reproductible : une semaine sans nouveau décret donne la même empreinte → `UNCHANGED`.
- `readSourceRecords` relit ces deux fichiers sans appel réseau.
- Pause entre deux appels (`delayMs`, 300 par défaut) ; respect des quotas PISTE (à relever sur le compte).

Paramètres :

```json
{"clientId": "", "clientSecret": "",
 "oauthUrl": "https://oauth.piste.gouv.fr/api/oauth/token",
 "apiUrl": "https://api.piste.gouv.fr/dila/legifrance/lf-engine-app",
 "since": "1995-01-01", "monthsAfterEnd": "12", "maxYearsWithoutAct": "40", "delayMs": "300",
 "bodies": "CONSEIL_ETAT,COUR_CASSATION,COUR_COMPTES,CONSEIL_CONSTITUTIONNEL_SG"}
```

**Configuration et identifiants PISTE** (décision du 28/10/2026) : dans les **paramètres du plugin** (JSON, écran
« Parsers »), comme la configuration des autres parsers. Le core les transmet au plugin à chaque exécution réelle et
à chaque test (`ParserContext.parameters()`).

Règles :
- le changeset Liquibase déclare `clientId` et `clientSecret` **vides** (il est commité) ; les vrais identifiants sont saisis dans l'écran « Parsers », en local et sur le serveur ;
- pas de masquage (décision du 28/10/2026) : les paramètres, identifiants compris, sont affichés tels quels dans l'écran « Parsers » et stockés en clair dans la table `parser` ;
- identifiant vide : l'exécution s'arrête (FAILED) avec un message qui dit de les saisir dans l'écran « Parsers » ; le plugin n'écrit jamais les identifiants dans les journaux ni dans le message d'exécution.

## 8. Ce qu'il faut mesurer avant de coder (dès que le compte PISTE existe)

1. Confirmer le format exact de `/search` et `/consult/jorf` sur le swagger PISTE.
2. Volumes par corps et par année (nombre de textes, taille maximale de `pageSize`), donc la durée d'une exécution complète.
3. Relever 30 à 50 décrets réels par corps pour fixer les tournures (nomination, promotion, retraite, honorariat, décrets collectifs) et en faire les jeux de test.
4. Valider le résultat sur un échantillon : présidents de section du Conseil d'État, présidents de chambre de la Cour de cassation et de la Cour des comptes (pages de présentation des institutions).

## 9. Découpage proposé

| Étape | Contenu | Couverture visée |
|---|---|---|
| 1 | Conseil d'État, Cour de cassation, secrétaire général du Conseil constitutionnel (FR-d) ; Cour des comptes (FR-e) | FR-d et FR-e « partielle », puis « complète » après validation |
| 2 | Ambassadeurs, chargés d'affaires, chefs d'état-major (FR-f) | FR-f |
| 3 | Dirigeants d'entreprises publiques nommés par décret (FR-g) | FR-g partielle (le périmètre vient de l'APE et du RECME) |

## 10. Décisions à prendre

| # | Question | Proposition |
|---|---|---|
| 1 | Création du compte PISTE et de l'application Légifrance | par l'utilisateur ; il saisit aussi les identifiants dans l'écran « Parsers » |
| 2 | Où ranger les identifiants | **décidé** : paramètres du plugin (`clientId`, `clientSecret`), sans masquage |
| 3 | Date de départ (`since`) | 1995, à revoir après les mesures |
| 4 | Périmètre de la première version | étape 1 seulement (FR-d, FR-e) |
