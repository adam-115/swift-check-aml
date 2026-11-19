# Plugins de listes de sanctions

Un dossier par fournisseur, un module (un JAR PF4J) par liste. Tous suivent le même modèle :

```
Plugin (lit sa source)                     swiftcheck-aml-sanction-common (identique pour toutes les listes)
──────────────────────                     ─────────────────────────────────────────────────────────────────
read(fichier) ou download()+readRaw()  →   List<SanctionRecord>  →  SanctionDataset.convert()  →  NDJSON FtM
                                                                 →  SanctionDataset.sourceRecords() (bouton « Tester »)
```

* `SanctionRecord` : une inscription (schéma FtM, clé officielle stable, propriétés du sujet, propriétés de la sanction).
* `SanctionDataset` : sujet `<préfixe>-<clé>` + entité `Sanction` `<préfixe>-<clé>-sanction`. Un pays ou une date que le
  SDK ne sait pas lire est gardé en note au lieu d'être rejeté (la publication n'est pas bloquée pour une valeur exotique).
* `AbstractSanctionParser` : `parse()` et `readSourceRecords()` communs ; source `HTTP_FILE` → `read(Path)`,
  source `PLUGIN_MANAGED` → `download()` (réponses brutes dans `rawDir`) puis `readRaw()`.
* Outils : `Http` (essais, User-Agent), `XlsxReader` (sans POI, lecture d'une seule feuille possible), `OdsReader`,
  `Dates` (« 12 Mar 1965 », « circa 1960 », « 29 juin 1985 »…), `SanctionTestSupport` (contrôles et XLSX de test).

La bibliothèque commune est copiée dans chaque JAR (maven-shade) : ne pas déposer
`swiftcheck-aml-sanction-common-*.jar` dans le dossier des plugins (`copy-plugins.bat` l'ignore).

## Listes

| Fournisseur | Dataset | Plugin-Id | Source | Format |
|---|---|---|---|---|
| ONU | `un_sc_sanctions` | `un-sc-sanctions` | Conseil de sécurité, liste récapitulative | XML |
| OFAC (US) | `us_ofac_sdn_sanctions` | `us-ofac-sanctions` (`idPrefix` = `us-ofac-sdn`) | SDN | XML |
| OFAC (US) | `us_ofac_consolidated_sanctions` | `us-ofac-sanctions` (`idPrefix` = `us-ofac-cons`) | SSI, NS-MBS, CMIC, FSE, PLC, HKAA | XML |
| ITA (US) | `us_csl_sanctions` | `us-csl-sanctions` | Consolidated Screening List : BIS (EL, DPL, UVL, MEU), State (ITAR, ACN) | CSV |
| FCDO (UK) | `uk_fcdo_sanctions` | `uk-fcdo-sanctions` | UK Sanctions List | XML |
| Affaires mondiales Canada | `ca_sema_sanctions` | `ca-sema-sanctions` | LMES / loi Magnitski | XML |
| Sécurité publique Canada | `ca_terrorist_sanctions` | `ca-terrorist-sanctions` | Entités terroristes (Code criminel 83.05) | HTML |
| SECO (Suisse) | `ch_seco_sanctions` | `ch-seco-sanctions` | Gesamtliste (radiations ignorées) | XML |
| DG Trésor (France) | `fr_dgtresor_sanctions` | `fr-dgtresor-sanctions` | Registre national des gels | JSON |
| DFAT (Australie) | `au_dfat_sanctions` | `au-dfat-sanctions` | Consolidated List | XLSX |
| MOF (Japon) | `jp_mof_sanctions` | `jp-mof-sanctions` | Gel des avoirs (CSV trouvé sur la page) | CSV, PLUGIN_MANAGED |
| MFAT (Nouvelle-Zélande) | `nz_mfat_sanctions` | `nz-mfat-sanctions` | Russia Sanctions Register | XLSX |
| Pays-Bas | `nl_terror_sanctions` | `nl-terror-sanctions` | Liste nationale terrorisme | ODS |
| MSWiA (Pologne) | `pl_mswia_sanctions` | `pl-mswia-sanctions` | Liste de la loi du 13/04/2022 | HTML |
| SPF Finances (Belgique) | `be_national_sanctions` | `be-national-sanctions` | Liste nationale terrorisme (XLSX trouvé sur la page, défi anti-robot) | XLSX, PLUGIN_MANAGED |

Le parser UE (`eu_sanctions`) reste dans `swiftcheck-aml-plugins/swiftcheck-aml-eu`.

Enregistrement en base : `db/changelog/changes/158-register-sanction-plugins.xml`.

## Listes étudiées mais pas encore couvertes

| Liste | Blocage |
|---|---|
| Ukraine (NSDC, registre d'État des sanctions) | Export CSV/XLSX et API (`api-drs.nsdc.gov.ua`) sans adresse documentée publiquement ; site fermé aux robots depuis le poste de dev |
| Émirats (Local Terrorist List, EOCN) | Fichier servi par `uaeiec.gov.ae/API/Upload/DownloadFile?FileID=…`, rejeté par le pare-feu du site hors navigateur |
| Israël (NBCTF) | Export du portail `matal.mod.gov.il` protégé par reCAPTCHA |
| Monaco, Kazakhstan, Kirghizistan, Russie (Rosfinmonitoring), Chine (MOFCOM) | Sites injoignables (403 ou délai dépassé) depuis le poste de dev : à réessayer depuis le serveur |
| Inde (MHA, organisations interdites) | L'ancienne page redirige vers l'accueil du ministère |

## Tests

Chaque plugin a un test sur un extrait réel de sa source. Le contrôle sur le fichier complet (aucune valeur rejetée,
aucun conflit d'ID) s'exécute si la variable d'environnement `SANCTION_SAMPLES` désigne un dossier contenant les
fichiers officiels (`un.xml`, `ofac_sdn.xml`, `ofac_cons.xml`, `csl.csv`, `uk.xml`, `ca.xml`, `ch.xml`, `fr.json`,
`jp.csv`, `nz.xlsx`, `pl.html`, `au.xlsx`).

```
mvn -pl swiftcheck-aml-plugins/sanction -amd install
```
