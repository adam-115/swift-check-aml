import os
import subprocess

html_content = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<title>SwiftCheck AML - Feuille de Route Stratégique : Comment Devenir la Meilleure Solution du Marché</title>
<style>
  @page {
    size: A4 portrait;
    margin: 10mm 12mm 10mm 12mm;
  }

  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }

  body {
    font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, 'Helvetica Neue', Arial, sans-serif;
    color: #1e293b;
    background-color: #ffffff;
    font-size: 11.5px;
    line-height: 1.45;
  }

  .page {
    page-break-after: always;
    position: relative;
    padding-bottom: 25px;
  }

  .page:last-child {
    page-break-after: avoid;
  }

  /* HEADER */
  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 2px solid #2563eb;
    padding-bottom: 8px;
    margin-bottom: 14px;
  }

  .brand-title {
    font-size: 20px;
    font-weight: 800;
    color: #0f172a;
    letter-spacing: -0.5px;
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .brand-badge {
    background: linear-gradient(135deg, #2563eb, #1d4ed8);
    color: #ffffff;
    font-size: 10px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 4px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }

  .brand-subtitle {
    font-size: 10px;
    color: #64748b;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.8px;
    margin-top: 2px;
  }

  .doc-meta {
    text-align: right;
    font-size: 10px;
    color: #64748b;
  }

  .doc-meta strong {
    color: #0f172a;
    font-size: 10.5px;
  }

  /* KPI BAR */
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    margin-bottom: 14px;
  }

  .kpi-card {
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 8px 10px;
    text-align: center;
    border-top: 3px solid #2563eb;
  }

  .kpi-card.emerald { border-top-color: #059669; }
  .kpi-card.amber { border-top-color: #d97706; }
  .kpi-card.violet { border-top-color: #7c3aed; }

  .kpi-val {
    font-size: 18px;
    font-weight: 800;
    color: #0f172a;
    line-height: 1.1;
  }

  .kpi-lbl {
    font-size: 9.5px;
    color: #64748b;
    font-weight: 600;
    text-transform: uppercase;
    margin-top: 2px;
  }

  /* SECTIONS */
  .section-title {
    font-size: 13px;
    font-weight: 800;
    color: #0f172a;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    margin-bottom: 8px;
    padding-bottom: 3px;
    border-bottom: 1.5px solid #e2e8f0;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .section-title .icon {
    display: inline-block;
    width: 7px;
    height: 13px;
    background: #2563eb;
    border-radius: 2px;
  }

  p {
    margin-bottom: 8px;
    color: #334155;
    text-align: justify;
  }

  /* BENCHMARK TABLE */
  .benchmark-table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 14px;
    font-size: 10px;
  }

  .benchmark-table th, .benchmark-table td {
    padding: 6px 7px;
    text-align: left;
    border: 1px solid #cbd5e1;
  }

  .benchmark-table th {
    background-color: #0f172a;
    color: #ffffff;
    font-weight: 700;
    text-transform: uppercase;
    font-size: 9px;
    letter-spacing: 0.3px;
  }

  .benchmark-table th.highlight {
    background: #1d4ed8;
  }

  .benchmark-table tr:nth-child(even) {
    background-color: #f8fafc;
  }

  .benchmark-table tr.total-row {
    background-color: #f1f5f9;
    font-weight: 800;
  }

  .tag-yes {
    display: inline-block;
    padding: 1px 5px;
    border-radius: 3px;
    background-color: #dcfce7;
    color: #166534;
    font-weight: 700;
    font-size: 8.5px;
  }

  .tag-no {
    display: inline-block;
    padding: 1px 5px;
    border-radius: 3px;
    background-color: #fee2e2;
    color: #991b1b;
    font-weight: 700;
    font-size: 8.5px;
  }

  .tag-opt {
    display: inline-block;
    padding: 1px 5px;
    border-radius: 3px;
    background-color: #fef3c7;
    color: #92400e;
    font-weight: 700;
    font-size: 8.5px;
  }

  .tag-target {
    display: inline-block;
    padding: 1px 5px;
    border-radius: 3px;
    background-color: #ede9fe;
    color: #5b21b6;
    font-weight: 800;
    font-size: 8.5px;
  }

  /* PILLARS GRID */
  .pillars-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
    margin-bottom: 12px;
  }

  .pillar-box {
    background: #ffffff;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 9px 11px;
    position: relative;
    border-left: 4px solid #2563eb;
  }

  .pillar-box.p1 { border-left-color: #ef4444; }
  .pillar-box.p2 { border-left-color: #059669; }
  .pillar-box.p3 { border-left-color: #7c3aed; }
  .pillar-box.p4 { border-left-color: #d97706; }

  .pillar-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 5px;
  }

  .pillar-title {
    font-size: 11px;
    font-weight: 800;
    color: #0f172a;
  }

  .pillar-priority {
    font-size: 8.5px;
    font-weight: 800;
    padding: 1px 5px;
    border-radius: 3px;
    text-transform: uppercase;
  }

  .p1 .pillar-priority { background: #fee2e2; color: #b91c1c; }
  .p2 .pillar-priority { background: #d1fae5; color: #047857; }
  .p3 .pillar-priority { background: #ede9fe; color: #6d28d9; }
  .p4 .pillar-priority { background: #fef3c7; color: #b45309; }

  .pillar-desc {
    font-size: 10px;
    color: #475569;
    margin-bottom: 6px;
    line-height: 1.35;
  }

  .pillar-list {
    list-style-type: none;
    font-size: 9.5px;
  }

  .pillar-list li {
    position: relative;
    padding-left: 12px;
    margin-bottom: 3px;
    color: #334155;
  }

  .pillar-list li::before {
    content: "■";
    position: absolute;
    left: 0;
    font-size: 7px;
    color: #2563eb;
    top: 1px;
  }

  /* ROADMAP TIMELINE */
  .roadmap-box {
    background: #f8fafc;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 9px 12px;
    margin-bottom: 12px;
  }

  .roadmap-step {
    display: flex;
    gap: 10px;
    margin-bottom: 7px;
  }

  .roadmap-step:last-child {
    margin-bottom: 0;
  }

  .step-badge {
    background: #0f172a;
    color: #ffffff;
    font-weight: 800;
    font-size: 9px;
    padding: 2px 7px;
    border-radius: 4px;
    height: fit-content;
    white-space: nowrap;
  }

  .step-content {
    flex: 1;
  }

  .step-title {
    font-size: 10.5px;
    font-weight: 700;
    color: #0f172a;
  }

  .step-desc {
    font-size: 9.5px;
    color: #475569;
  }

  /* CALLOUT BOX */
  .callout {
    background-color: #eff6ff;
    border-left: 4px solid #2563eb;
    padding: 8px 10px;
    border-radius: 0 5px 5px 0;
    margin-bottom: 12px;
    font-size: 10px;
    color: #1e3a8a;
  }

  .callout strong {
    color: #1d4ed8;
  }

  /* FOOTER */
  .footer {
    border-top: 1px solid #e2e8f0;
    padding-top: 6px;
    display: flex;
    justify-content: space-between;
    font-size: 8.5px;
    color: #94a3b8;
  }
</style>
</head>
<body>

  <!-- ==================== PAGE 1 ==================== -->
  <div class="page">
    <div class="header">
      <div class="brand">
        <div class="brand-title">
          SWIFTCHECK AML
          <span class="brand-badge">Audit & Stratégie</span>
        </div>
        <div class="brand-subtitle">Feuille de Route d'Excellence Concurrentielle & Benchmark Marché</div>
      </div>
      <div class="doc-meta">
        <strong>Direction Conformité & Architecture</strong><br>
        Date : 14 Septembre 2026 • Statut : Confidentiel
      </div>
    </div>

    <!-- KPI BAR -->
    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-val">376 345</div>
        <div class="kpi-lbl">Entités Actives Détenues</div>
      </div>
      <div class="kpi-card emerald">
        <div class="kpi-val">20 Listes</div>
        <div class="kpi-lbl">Couverture Mondiale Directe</div>
      </div>
      <div class="kpi-card amber">
        <div class="kpi-val">0 € / Req.</div>
        <div class="kpi-lbl">Coût API Tiers (100% Souverain)</div>
      </div>
      <div class="kpi-card violet">
        <div class="kpi-val">&lt; 35 ms</div>
        <div class="kpi-lbl">Latence de Screening Elasticsearch</div>
      </div>
    </div>

    <div class="section-title"><span class="icon"></span>1. État des Lieux : Une Base Déjà Exceptionnelle</div>
    <p>
      Avec l'intégration consécutive du <strong>Contrôle des Exportations US BIS (5 307 entités)</strong> et du registre des <strong>Dirigeants Interdits de Gestion UK Companies House (10 178 entités)</strong>, SwiftCheck AML totalise désormais <strong>376 345 entités sous surveillance continue</strong>.
      La plateforme surclasse l'ensemble des solutions open-source et dépasse en fraîcheur opérationnelle de nombreux éditeurs payants en couvrant l'intégralité du spectre réglementaire : Sanctions Financières (OFAC, UE, ONU, UK, SECO, DFAT, SEMA, DG Trésor), Alertes Fraude (AMF, ACPR, FCA, SEC), Mandats d'Arrêt (Europol, Interpol, DEA), Exclusion Marchés Publics (Banque Mondiale) et PEPs (206k élus et mandataires mondiaux).
    </p>

    <div class="section-title"><span class="icon"></span>2. Matrice Comparative : Benchmark Face aux Leaders Mondiaux</div>
    <table class="benchmark-table">
      <thead>
        <tr>
          <th>Fonctionnalité / Périmètre Analysé</th>
          <th>World-Check One</th>
          <th>ComplyAdvantage</th>
          <th>Dow Jones R&C</th>
          <th class="highlight">SwiftCheck (Actuel)</th>
          <th>SwiftCheck (Cible N°1)</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><strong>Sanctions Internationales (OFAC, UE, ONU, UK, CH, CA, etc.)</strong></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI (8 Listes)</span></td>
          <td><span class="tag-yes">OUI (Natif)</span></td>
        </tr>
        <tr>
          <td><strong>Régulateurs Financiers & Fraude (AMF, ACPR, FCA, SEC)</strong></td>
          <td><span class="tag-opt">Partiel</span></td>
          <td><span class="tag-opt">Partiel</span></td>
          <td><span class="tag-opt">Optionnel</span></td>
          <td><span class="tag-yes">OUI (4 Listes)</span></td>
          <td><span class="tag-yes">OUI (+ BaFin, FINMA)</span></td>
        </tr>
        <tr>
          <td><strong>Contrôle des Exportations & Embargos (US BIS CSL)</strong></td>
          <td><span class="tag-opt">Option $$$</span></td>
          <td><span class="tag-opt">Option $$$</span></td>
          <td><span class="tag-opt">Option $$$</span></td>
          <td><span class="tag-yes">OUI (5 307 entités)</span></td>
          <td><span class="tag-yes">OUI (Natif)</span></td>
        </tr>
        <tr>
          <td><strong>Dirigeants Interdits de Gestion (UK Companies House)</strong></td>
          <td><span class="tag-opt">Option</span></td>
          <td><span class="tag-opt">Option</span></td>
          <td><span class="tag-opt">Option</span></td>
          <td><span class="tag-yes">OUI (10 178 entités)</span></td>
          <td><span class="tag-yes">OUI (+ FNIG France)</span></td>
        </tr>
        <tr>
          <td><strong>Personnes Politiquement Exposées (PEP & RCA Monde)</strong></td>
          <td><span class="tag-yes">OUI (Mondial)</span></td>
          <td><span class="tag-yes">OUI (Mondial)</span></td>
          <td><span class="tag-yes">OUI (Mondial)</span></td>
          <td><span class="tag-yes">OUI (206k PEPs)</span></td>
          <td><span class="tag-yes">OUI (250k+ PEPs)</span></td>
        </tr>
        <tr>
          <td><strong>Règle de Refroidissement 12 mois & Tiers PEP Automatisés</strong></td>
          <td><span class="tag-no">Manuel</span></td>
          <td><span class="tag-opt">Partiel</span></td>
          <td><span class="tag-no">Manuel</span></td>
          <td><span class="tag-yes">OUI (Algorithmique)</span></td>
          <td><span class="tag-yes">OUI (Temps Réel)</span></td>
        </tr>
        <tr>
          <td><strong>Presse Négative & Signaux Faibles (Adverse Media)</strong></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI (IA)</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-no">Non disponible</span></td>
          <td><span class="tag-target">Cible N°1 (NLP/LLM)</span></td>
        </tr>
        <tr>
          <td><strong>Moteur de Règle des 50% (OFAC/EU 50% Rule & UBO)</strong></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-opt">UBO Direct</span></td>
          <td><span class="tag-target">Propagation Graphe</span></td>
        </tr>
        <tr>
          <td><strong>Surveillance Continue Automatisée (Ongoing Monitoring)</strong></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-yes">OUI</span></td>
          <td><span class="tag-opt">Screening API</span></td>
          <td><span class="tag-target">Nightly Re-Screening</span></td>
        </tr>
        <tr class="total-row">
          <td><strong>Modèle Économique & Indépendance Stratégique</strong></td>
          <td><span class="tag-no">Abonnement Très Cher</span></td>
          <td><span class="tag-no">Facturation / Requête</span></td>
          <td><span class="tag-no">Contrat Cadre Lourd</span></td>
          <td><span class="tag-yes">0 € / Req. (100% Souverain)</span></td>
          <td><span class="tag-yes">0 € (Zéro Dépendance)</span></td>
        </tr>
      </tbody>
    </table>

    <div class="callout">
      <strong>Constat Stratégique :</strong> SwiftCheck AML possède déjà la quasi-totalité des actifs de données "dures" (Sanctions, PEPs, Fraude, Export Control). La marche pour devenir le <strong>Leader Incontesté</strong> réside désormais dans <strong>l'Adverse Media</strong>, la <strong>Surveillance Continue (Ongoing Monitoring)</strong> et le <strong>Moteur d'extension 50% Rule</strong>.
    </div>

    <div class="footer">
      <div>SwiftCheck AML • Rapport d'Excellence Stratégique & Concurrentielle</div>
      <div>Page 1 / 2</div>
    </div>
  </div>

  <!-- ==================== PAGE 2 ==================== -->
  <div class="page">
    <div class="header">
      <div class="brand">
        <div class="brand-title">
          SWIFTCHECK AML
          <span class="brand-badge">Plan Directeur</span>
        </div>
        <div class="brand-subtitle">Les 4 Piliers d'Excellence pour Surpasser le Marché</div>
      </div>
      <div class="doc-meta">
        <strong>Feuille de Route d'Exécution</strong><br>
        Horizon : T4 2026 - T1 2027
      </div>
    </div>

    <div class="section-title"><span class="icon"></span>3. Les 4 Piliers Fondamentaux à Implémenter</div>
    <div class="pillars-grid">
      <!-- PILIER 1 -->
      <div class="pillar-box p1">
        <div class="pillar-header">
          <div class="pillar-title">1. Adverse Media & Negative News</div>
          <span class="pillar-priority">Priorité Absolue</span>
        </div>
        <div class="pillar-desc">
          60% des signaux faibles de fraude, blanchiment et corruption apparaissent dans la presse d'investigation des mois avant toute condamnation judiciaire.
        </div>
        <ul class="pillar-list">
          <li><strong>Connexion Flux Ouverts :</strong> Intégration du GDELT Project (Global Database of Events) & Google News RSS.</li>
          <li><strong>Classifieur IA Typologies GAFI :</strong> Détection automatique : Blanchiment, Pots-de-vin, Fraude fiscale, Narcotrafic, Terrorisme.</li>
          <li><strong>Score de Sévérité Médiatique :</strong> Élimination des bruits médiatiques pour ne remonter que les faits avérés.</li>
        </ul>
      </div>

      <!-- PILIER 2 -->
      <div class="pillar-box p2">
        <div class="pillar-header">
          <div class="pillar-title">2. Données Stratégiques Ciblées</div>
          <span class="pillar-priority">Haute Priorité</span>
        </div>
        <div class="pillar-desc">
          Compléter les angles morts nationaux et multilatéraux pour devancer World-Check sur le marché européen et africain.
        </div>
        <ul class="pillar-list">
          <li><strong>FNIG France (BODACC / Greffes) :</strong> Fichier National des Interdits de Gérer et faillites d'entreprises françaises.</li>
          <li><strong>Banques Multilatérales (Cross-Debarment) :</strong> BEI (Europe), BERD, BAD (Afrique) et BAsD (Asie).</li>
          <li><strong>FBI Most Wanted :</strong> Top 10 Fugitives, Cybercrime et Criminalité Financière internationale.</li>
        </ul>
      </div>

      <!-- PILIER 3 -->
      <div class="pillar-box p3">
        <div class="pillar-header">
          <div class="pillar-title">3. Moteur OFAC / UE 50% Rule</div>
          <span class="pillar-priority">Avantage Tech</span>
        </div>
        <div class="pillar-desc">
          Calcul automatique de l'extension des sanctions le long de la chaîne d'actionnariat indirecte des entreprises clientes.
        </div>
        <ul class="pillar-list">
          <li><strong>Propagation Capitalistique :</strong> Si un UBO sanctionné détient &ge; 50% d'une filiale, celle-ci est sanctionnée par extension.</li>
          <li><strong>Graphe Relationnel Interactif :</strong> Visualisation visuelle des liens UBO &harr; Société &harr; Sanctions &harr; PEPs.</li>
          <li><strong>Agrégation Participations Croisées :</strong> Cumul automatique des pourcentages de détention de plusieurs entités sanctionnées.</li>
        </ul>
      </div>

      <!-- PILIER 4 -->
      <div class="pillar-box p4">
        <div class="pillar-header">
          <div class="pillar-title">4. Ongoing Monitoring & Audit</div>
          <span class="pillar-priority">Opérationnel</span>
        </div>
        <div class="pillar-desc">
          Passer du screening ponctuel à une plateforme bancaire d'audit continu certifiable ACPR, FCA et BaFin.
        </div>
        <ul class="pillar-list">
          <li><strong>Surveillance Continue Nocturne :</strong> Re-screening par lots (Batch) de tout le portefeuille dès qu'une liste officielle évolue.</li>
          <li><strong>Workflow Case Management :</strong> Gestion d'alertes à 4 yeux (Maker-Checker), Whitelisting et justifications d'audit.</li>
          <li><strong>Piste d'Audit Immuable :</strong> Traçabilité complète exportable en PDF pour les contrôles réglementaires.</li>
        </ul>
      </div>
    </div>

    <div class="section-title"><span class="icon"></span>4. Feuille de Route d'Implémentation Recommandée</div>
    <div class="roadmap-box">
      <div class="roadmap-step">
        <div class="step-badge">PHASE 1 (Semaine 1)</div>
        <div class="step-content">
          <div class="step-title">Données Stratégiques Immédiates (FNIG France & Accord MDB Multilatéral)</div>
          <div class="step-desc">Ajout du Fichier National des Interdits de Gérer français (BODACC) et des sanctions croisées BEI / BERD / BAD (+15k entités).</div>
        </div>
      </div>
      <div class="roadmap-step">
        <div class="step-badge">PHASE 2 (Semaines 2-3)</div>
        <div class="step-content">
          <div class="step-title">Moteur Adverse Media & Negative News par IA</div>
          <div class="step-desc">Implémentation du connecteur d'actualités et du classifieur NLP sur les 6 typologies de crimes financiers du GAFI.</div>
        </div>
      </div>
      <div class="roadmap-step">
        <div class="step-badge">PHASE 3 (Semaine 4)</div>
        <div class="step-content">
          <div class="step-title">Moteur de Règle des 50% & Surveillance Continue (Ongoing Monitoring)</div>
          <div class="step-desc">Re-screening automatique nocturne des portefeuilles, alertes Webhooks et propagation des sanctions sur l'arbre capitalistique UBO.</div>
        </div>
      </div>
    </div>

    <div class="section-title"><span class="icon"></span>5. Conclusion & Synthèse Décisionnelle</div>
    <p>
      Le modèle développé pour SwiftCheck AML est <strong>stratégiquement gagnant</strong> : en éliminant les coûts récurrents exorbitants des fournisseurs d'API propriétaires (facturant entre 0,50 € et 3,00 € par vérification), la solution offre une <strong>marge brute maximale</strong> tout en garantissant une <strong>souveraineté totale des données bancaires</strong>. 
      En exécutant les 4 piliers ci-dessus, SwiftCheck AML s'imposera sans conteste comme la solution AML la plus complète, agile et compétitive du marché européen et international.
    </p>

    <div class="footer">
      <div>SwiftCheck AML • Document Stratégique Exécutif • Confidentiel</div>
      <div>Page 2 / 2</div>
    </div>
  </div>

</body>
</html>
"""

html_path = r"d:\swiftcheck-aml\swiftcheck-aml\Analyse_Strategique_Leader_Marche_SwiftCheck_AML.html"
pdf_path = r"d:\swiftcheck-aml\swiftcheck-aml\Analyse_Strategique_Leader_Marche_SwiftCheck_AML.pdf"

with open(html_path, "w", encoding="utf-8") as f:
    f.write(html_content)

print(f"HTML successfully generated at: {html_path} ({os.path.getsize(html_path):,} bytes)")

# Convert to PDF
browser_paths = [
    r'C:\Program Files\Google\Chrome\Application\chrome.exe',
    r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
]

browser_exec = next((p for p in browser_paths if os.path.exists(p)), None)

if browser_exec:
    print(f"Using browser for PDF generation: {browser_exec}")
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
        print(f"PDF successfully generated at: {pdf_path} ({os.path.getsize(pdf_path):,} bytes)")
    else:
        print(f"PDF generation failed: {res.stderr}")
else:
    print("No browser found for PDF generation.")
