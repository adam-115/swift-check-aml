# gr_pep_audit – Cour des comptes hellénique (NON déployé)

Page `https://www.elsyn.gr/en/judges` : blocs `<div id="info"><p>NOM</p><p><em>FONCTION</em></p></div>` du président (Douni Sotiria) et du procureur général (Stamatios Poulis) ; ordre inverse du nom ajouté en alias. Les onglets des vice-présidents sont chargés par script, non lus.

**Pas de parser déclaré en base** : le serveur présente un certificat `*.elsyn.gr` émis par Sectigo avec une chaîne RapidSSL/DigiCert (chaîne incomplète). `curl` l'accepte, Java (21.0.12) refuse : `PKIX path building failed`, même avec `-Dcom.sun.security.enableAIAcaIssuers=true` ou `-Djavax.net.ssl.trustStoreType=Windows-ROOT`. La vérification TLS n'est pas désactivée. À déclarer (migration + jar) quand le certificat sera corrigé côté serveur ou ajouté volontairement au magasin de confiance du back.
