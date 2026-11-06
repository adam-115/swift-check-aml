# no_pep_parliament – Storting (Norvège)

Représentants du Storting (API ouverte https://data.stortinget.no/eksport), catégorie NO-b.

- Jeux : `no_pep_parliament` (monthsAfterEnd=12) et `no_pep_parliament_history` (monthsAfterEnd=600), même plugin.
- Type PLUGIN_MANAGED ; paramètres : `apiUrl`, `monthsAfterEnd`, `delayMs`.
- L'API ne donne pas la date de départ : un ancien est publié avec la fin de la législature comme fin de mandat. Décédés écartés.
- Tests : `mvn -o -pl swiftcheck-aml-plugins/pep/norway/swiftcheck-aml-no-pep-parliament -am test`
