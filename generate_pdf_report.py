import os
import subprocess

html_content = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<title>Analyse des Coûts & Architecture de Déploiement - SwiftCheck AML</title>
<style>
  @page {
    size: A4 portrait;
    margin: 15mm 15mm 15mm 15mm;
  }
  
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }

  body {
    font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, Helvetica, Arial, sans-serif;
    color: #1e293b;
    background-color: #ffffff;
    font-size: 13px;
    line-height: 1.5;
  }

  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 2px solid #2563eb;
    padding-bottom: 12px;
    margin-bottom: 20px;
  }

  .brand {
    display: flex;
    flex-direction: column;
  }

  .brand-title {
    font-size: 22px;
    font-weight: 800;
    color: #0f172a;
    letter-spacing: -0.5px;
  }

  .brand-subtitle {
    font-size: 11px;
    color: #2563eb;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 1px;
  }

  .doc-meta {
    text-align: right;
    font-size: 11px;
    color: #64748b;
  }

  .doc-meta strong {
    color: #0f172a;
  }

  .badge-tag {
    display: inline-block;
    background-color: #eff6ff;
    color: #2563eb;
    border: 1px solid #bfdbfe;
    padding: 3px 8px;
    border-radius: 4px;
    font-size: 10px;
    font-weight: 700;
    margin-bottom: 6px;
  }

  h1 {
    font-size: 18px;
    color: #0f172a;
    font-weight: 800;
    margin-bottom: 8px;
  }

  h2 {
    font-size: 14px;
    color: #1e293b;
    font-weight: 700;
    border-left: 4px solid #2563eb;
    padding-left: 8px;
    margin-top: 18px;
    margin-bottom: 10px;
  }

  p {
    color: #334155;
    margin-bottom: 8px;
    font-size: 12.5px;
  }

  .hero-box {
    background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
    color: #ffffff;
    border-radius: 8px;
    padding: 14px 18px;
    margin-bottom: 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .hero-text h3 {
    font-size: 14px;
    color: #38bdf8;
    margin-bottom: 4px;
  }

  .hero-text p {
    color: #cbd5e1;
    font-size: 11.5px;
    margin-bottom: 0;
  }

  .hero-price {
    text-align: right;
    background: rgba(37, 99, 235, 0.25);
    border: 1px solid #38bdf8;
    padding: 8px 14px;
    border-radius: 6px;
  }

  .hero-price .amount {
    font-size: 20px;
    font-weight: 800;
    color: #38bdf8;
  }

  .hero-price .period {
    font-size: 10px;
    color: #e2e8f0;
  }

  .grid-3 {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 10px;
    margin-bottom: 14px;
  }

  .card {
    background-color: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 10px 12px;
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
    padding-bottom: 4px;
    border-bottom: 1px solid #e2e8f0;
  }

  .card-title {
    font-size: 12px;
    font-weight: 700;
    color: #0f172a;
  }

  .card-price {
    font-size: 11px;
    font-weight: 700;
    color: #2563eb;
  }

  .card-specs {
    font-size: 10.5px;
    color: #64748b;
    margin-bottom: 6px;
    font-family: Consolas, monospace;
    background: #ffffff;
    padding: 3px 6px;
    border-radius: 4px;
    border: 1px solid #f1f5f9;
  }

  .card ul {
    list-style-type: none;
    font-size: 11px;
    color: #475569;
  }

  .card ul li {
    margin-bottom: 4px;
    padding-left: 12px;
    position: relative;
  }

  .card ul li::before {
    content: "•";
    color: #2563eb;
    position: absolute;
    left: 0;
    font-weight: bold;
  }

  table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 10px;
    margin-bottom: 14px;
    font-size: 11.5px;
  }

  th {
    background-color: #0f172a;
    color: #ffffff;
    text-align: left;
    padding: 7px 10px;
    font-weight: 700;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }

  th:last-child, td:last-child, th:nth-child(4), td:nth-child(4) {
    text-align: right;
  }

  td {
    padding: 7px 10px;
    border-bottom: 1px solid #e2e8f0;
    color: #334155;
  }

  tr:nth-child(even) {
    background-color: #f8fafc;
  }

  tr.total-row {
    background-color: #eff6ff;
    font-weight: 700;
  }

  tr.total-row td {
    color: #1d4ed8;
    border-top: 2px solid #2563eb;
    border-bottom: 2px solid #2563eb;
    font-size: 12px;
  }

  .callout {
    background-color: #f0fdf4;
    border: 1px solid #bbf7d0;
    border-left: 4px solid #16a34a;
    padding: 10px 14px;
    border-radius: 4px;
    margin-bottom: 12px;
    font-size: 11.5px;
    color: #166534;
  }

  .callout-title {
    font-weight: 700;
    margin-bottom: 2px;
    color: #14532d;
  }

  .workflow-box {
    background-color: #f8fafc;
    border: 1px dashed #cbd5e1;
    border-radius: 6px;
    padding: 10px 14px;
    margin-top: 8px;
    margin-bottom: 14px;
  }

  .workflow-steps {
    display: flex;
    justify-content: space-between;
    gap: 8px;
    margin-top: 6px;
  }

  .wf-step {
    flex: 1;
    background: #ffffff;
    border: 1px solid #e2e8f0;
    border-radius: 4px;
    padding: 6px 8px;
    font-size: 10.5px;
  }

  .wf-step-num {
    font-weight: 700;
    color: #2563eb;
    margin-bottom: 2px;
  }

  .footer {
    margin-top: 20px;
    border-top: 1px solid #e2e8f0;
    padding-top: 8px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 10px;
    color: #94a3b8;
  }

  .page-break {
    page-break-before: always;
  }
</style>
</head>
<body>

  <!-- HEADER -->
  <div class="header">
    <div class="brand">
      <div class="brand-title">SwiftCheck AML</div>
      <div class="brand-subtitle">Plateforme RegTech & Conformité LCB-FT</div>
    </div>
    <div class="doc-meta">
      <div><strong>Document :</strong> Analyse d'Infrastructure & Coûts</div>
      <div><strong>Fournisseur Cible :</strong> OVHcloud (Datacenters France / Europe)</div>
      <div><strong>Date :</strong> Septembre 2026</div>
    </div>
  </div>

  <span class="badge-tag">INFRASTRUCTURE CLOUD & BUDGET OPÉRATIONNEL</span>
  <h1>Analyse du Coût de Déploiement sur VPS OVHcloud</h1>
  <p>Cette analyse détaille le dimensionnement, la topologie réseau, la politique de sauvegarde et les coûts mensuels/annuels pour le déploiement de la solution <strong>SwiftCheck AML</strong> en architecture 3-Tiers isolée et sécurisée.</p>

  <!-- HERO SUMMARY -->
  <div class="hero-box">
    <div class="hero-text">
      <h3>Architecture 3 Serveurs Dédiés + S3 + Snapshots d'OS</h3>
      <p>Cloisonnement total des responsabilités (Yente FTM, Keycloak IAM, Application Core & Base de données).</p>
    </div>
    <div class="hero-price">
      <div class="amount">~44,30 € HT</div>
      <div class="period">par mois (~531,60 € HT / an)</div>
    </div>
  </div>

  <!-- 3 SERVERS GRID -->
  <h2>1. Découpage & Dimensionnement des 3 Serveurs Dédiés</h2>
  <div class="grid-3">
    <!-- Server 1 -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Serveur 1 : Yente</div>
        <div class="card-price">14,50 € / m</div>
      </div>
      <div class="card-specs">VPS Comfort (4 vCPU / 8 Go RAM / 160 Go NVMe)</div>
      <ul>
        <li><strong>Rôle :</strong> Moteur de screening FollowTheMoney.</li>
        <li><strong>Charge mémoire :</strong> 85 000+ entités (OFAC, UE, ONU, etc.) indexées en RAM.</li>
        <li><strong>Performance :</strong> Temps de réponse &lt; 50 ms par requête de matching.</li>
        <li><strong>Conteneur :</strong> Docker autonome sur port interne 8000.</li>
      </ul>
    </div>

    <!-- Server 2 -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Serveur 2 : Keycloak</div>
        <div class="card-price">8,50 € / m</div>
      </div>
      <div class="card-specs">VPS Essential (2 vCPU / 4 Go RAM / 80 Go NVMe)</div>
      <ul>
        <li><strong>Rôle :</strong> IAM & Authentification sécurisée (OAuth2 / OIDC).</li>
        <li><strong>Sécurité :</strong> Émission des jetons JWT, gestion des rôles (RBAC) et MFA (2FA).</li>
        <li><strong>Base dédiée :</strong> PostgreSQL dédié pour l'annuaire et sessions.</li>
        <li><strong>Stabilité :</strong> Isolation totale des accès et politique d'audit.</li>
      </ul>
    </div>

    <!-- Server 3 -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Serveur 3 : App Core</div>
        <div class="card-price">14,50 € / m</div>
      </div>
      <div class="card-specs">VPS Comfort (4 vCPU / 8 Go RAM / 160 Go NVMe)</div>
      <ul>
        <li><strong>Rôle :</strong> Backend Spring Boot, Frontend Angular, DB Métier.</li>
        <li><strong>Composants :</strong> Gestionnaire PF4J, JasperReports, Reverse Proxy Nginx.</li>
        <li><strong>Expositions :</strong> Seul serveur ouvert sur ports 80/443 (HTTPS).</li>
        <li><strong>Base Métier :</strong> PostgreSQL (historique, exécutions, providers).</li>
      </ul>
    </div>
  </div>

  <!-- S3 & SNAPSHOTS SECTION -->
  <h2>2. Stockage S3 & Gestion du Risque Opérationnel (Snapshots)</h2>
  
  <div class="callout">
    <div class="callout-title">🛡️ Sauvegardes Immuables sur OVH Object Storage (Compatible S3 API)</div>
    Sauvegarde quotidienne automatisée par script cron : dumps PostgreSQL chiffrés (GPG), archives des flux FTM .ndjson, fichiers JAR de plugins et rapports d'audit PDF. Rétention glissante sur 30 jours (RGPD / LCB-FT). Coût ultra-faible : <strong>~0,80 € HT / mois pour 50 à 100 Go</strong>.
  </div>

  <div class="workflow-box">
    <div style="font-weight: 700; font-size: 11.5px; color: #0f172a;">📸 Option Snapshot d'OS : Gestion des Manipulations Risquées (Rollback en 2 min)</div>
    <p style="font-size: 11px; margin-top: 3px; color: #64748b;">Prise d'un instantané de l'état complet du serveur (OS + Données + Config) avant toute opération sensible :</p>
    <div class="workflow-steps">
      <div class="wf-step">
        <div class="wf-step-num">Étape 1 : Déclenchement</div>
        Création du Snapshot via API OVH ou Manager avant l'intervention.
      </div>
      <div class="wf-step">
        <div class="wf-step-num">Étape 2 : Maintenance</div>
        Mise à jour d'OS, nouveau plugin PF4J ou migration de schéma DB.
      </div>
      <div class="wf-step">
        <div class="wf-step-num">Étape 3 : Rollback / Valide</div>
        En cas d'anomalie, restauration immédiate de l'état exact en 2 minutes.
      </div>
    </div>
  </div>

  <!-- PRICING TABLE -->
  <h2>3. Tableau Récapitulatif Détaillé des Coûts (Scénario Optimal)</h2>
  <table>
    <thead>
      <tr>
        <th>Composant</th>
        <th>Configuration / Spécifications</th>
        <th>Rôle Fonctionnel</th>
        <th>Mensuel (HT)</th>
        <th>Annuel (HT)</th>
      </tr>
    </thead>
    <tbody>
      <tr>
        <td><strong>Serveur 1 : Yente Engine</strong></td>
        <td>OVH VPS Comfort (4 vCPU / 8 Go RAM / 160 Go NVMe)</td>
        <td>Screening temps réel Yente & FTM en RAM</td>
        <td>14,50 €</td>
        <td>174,00 €</td>
      </tr>
      <tr>
        <td><strong>Serveur 2 : Keycloak IAM</strong></td>
        <td>OVH VPS Essential (2 vCPU / 4 Go RAM / 80 Go NVMe)</td>
        <td>Authentification JWT, rôles & sécurité MFA</td>
        <td>8,50 €</td>
        <td>102,00 €</td>
      </tr>
      <tr>
        <td><strong>Serveur 3 : App Core & UI</strong></td>
        <td>OVH VPS Comfort (4 vCPU / 8 Go RAM / 160 Go NVMe)</td>
        <td>Spring Boot, Plugins PF4J, Angular & PostgreSQL</td>
        <td>14,50 €</td>
        <td>174,00 €</td>
      </tr>
      <tr>
        <td><strong>Stockage S3 (Object Storage)</strong></td>
        <td>50 à 100 Go OVH Object Storage (S3 API)</td>
        <td>Sauvegardes chiffrées quotidiennes & archives</td>
        <td>0,80 €</td>
        <td>9,60 €</td>
      </tr>
      <tr>
        <td><strong>Option Snapshots (x3 VPS)</strong></td>
        <td>1 Snapshot instantané par serveur</td>
        <td>Sécurisation des opérations à risque / Rollback</td>
        <td>5,00 €</td>
        <td>60,00 €</td>
      </tr>
      <tr>
        <td><strong>Nom de Domaine & SSL</strong></td>
        <td>Domaine .com / .fr + Let's Encrypt Wildcard</td>
        <td>Chiffrement TLS 1.3 / HTTPS</td>
        <td>1,00 €</td>
        <td>12,00 €</td>
      </tr>
      <tr class="total-row">
        <td colspan="3"><strong>TOTAL GÉNÉRAL DU DÉPLOIEMENT</strong></td>
        <td><strong>44,30 € HT</strong></td>
        <td><strong>531,60 € HT</strong></td>
      </tr>
    </tbody>
  </table>

  <!-- SECURITY & PRODUCTION BEST PRACTICES -->
  <h2>4. Sécurité Réseau & Recommandations de Déploiement</h2>
  <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; font-size: 11px;">
    <div style="background: #f8fafc; border: 1px solid #e2e8f0; padding: 8px 10px; border-radius: 4px;">
      <strong style="color: #0f172a;">🔒 Cloisonnement Réseau & Pare-feu (UFW) :</strong><br>
      • Seul le Serveur 3 ouvre les ports 80/443 (HTTPS) au public.<br>
      • Les Serveurs 1 (Yente port 8000) et 2 (Keycloak) ne répondent qu'aux requêtes internes provenant du Serveur 3.
    </div>
    <div style="background: #f8fafc; border: 1px solid #e2e8f0; padding: 8px 10px; border-radius: 4px;">
      <strong style="color: #0f172a;">🇪🇺 Souveraineté & Conformité Réglementaire :</strong><br>
      • Hébergement 100% européen (Datacenters Gravelines / Roubaix / Strasbourg).<br>
      • Conformité stricte RGPD et exigences de l'ACPR / AMF pour la conservation des logs LCB-FT.
    </div>
  </div>

  <!-- FOOTER -->
  <div class="footer">
    <div>Document généré pour la plateforme <strong>SwiftCheck AML</strong> • Confidentiel</div>
    <div>Page 1 / 1</div>
  </div>

</body>
</html>
"""

html_path = r"d:\swiftcheck-aml\swiftcheck-aml\Analyse_Cout_Deploiement_OVH_SwiftCheck_AML.html"
pdf_path = r"d:\swiftcheck-aml\swiftcheck-aml\Analyse_Cout_Deploiement_OVH_SwiftCheck_AML.pdf"

with open(html_path, "w", encoding="utf-8") as f:
    f.write(html_content)

print(f"HTML generated at: {html_path}")

# Convert HTML to PDF using headless Chrome or Edge
browser_paths = [
    r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
    r'C:\Program Files\Google\Chrome\Application\chrome.exe'
]

browser_exec = next((p for p in browser_paths if os.path.exists(p)), None)

if browser_exec:
    cmd = [
        browser_exec,
        "--headless",
        "--disable-gpu",
        "--no-pdf-header-footer",
        f"--print-to-pdf={pdf_path}",
        html_path
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if os.path.exists(pdf_path) and os.path.getsize(pdf_path) > 0:
        print(f"PDF successfully generated at: {pdf_path} (Size: {os.path.getsize(pdf_path)} bytes)")
    else:
        print(f"PDF generation failed: {res.stderr}")
else:
    print("No browser found for PDF conversion.")
