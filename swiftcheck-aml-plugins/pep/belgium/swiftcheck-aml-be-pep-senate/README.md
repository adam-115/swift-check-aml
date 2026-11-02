# swiftcheck-aml-be-pep-senate

Plugin PF4J (`be-pep-senate`) pour l'extraction des membres du **Sénat de Belgique** (`be_pep_senate`).

## Base légale
- **Loi du 18 septembre 2017**, art. 4, 28°, b) et Annexe IV – *« Sénateur, y compris coopté »* (catégorie `BE-b`).

## Source officielle
- Liste des sénateurs en fonction : `https://www.senate.be/www/?MIval=/WieIsWie/LijstDerSenatoren&LANG=fr`

## Particularités
- Conversion de l'encodage `ISO-8859-1` vers `UTF-8`.
- Inversion de l'ordre `Nom Prénom` affiché par le Sénat vers `Prénom Nom` (avec conservation de `Nom Prénom` en alias).
- Distinction entre les **50 sénateurs des entités fédérées** (avec mention du parlement d'origine) et les **10 sénateurs cooptés**, ainsi que du **Président du Sénat**.
- **Protection des données personnelles** : les adresses privées ou de correspondance, numéros de téléphone et adresses courriel présents sur `senate.be` sont intégralement purgés et ignorés.

