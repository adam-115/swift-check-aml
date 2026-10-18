import os
import sys
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

# -------------------------------------------------------------
# Color Palette & Design Constants
# -------------------------------------------------------------
NAVY_DARK   = RGBColor(15, 23, 42)      # #0F172A - Dominant dark
NAVY_CARD   = RGBColor(30, 41, 59)      # #1E293B - Dark card background
NAVY_LIGHT  = RGBColor(51, 65, 85)      # #334155 - Lighter navy for borders
PRIMARY_BLUE= RGBColor(37, 99, 235)     # #2563EB - Tech Blue
LIGHT_BLUE  = RGBColor(239, 246, 255)   # #EFF6FF - Soft blue background
CYAN_ACCENT = RGBColor(6, 182, 212)     # #06B6D4 - Cyan accent
TEAL_GREEN  = RGBColor(16, 185, 129)    # #10B981 - Success / Clean / Safety
AMBER_ALERT = RGBColor(245, 158, 11)    # #F59E0B - Alert / Warning
INDIGO_ACC  = RGBColor(99, 102, 241)    # #6366F1 - Indigo
PURPLE_ACC  = RGBColor(139, 92, 246)    # #8B5CF6 - Purple Accent
BG_LIGHT    = RGBColor(248, 250, 252)   # #F8FAFC - Main slide background
CARD_BG     = RGBColor(255, 255, 255)   # #FFFFFF - Card background
CARD_BORDER = RGBColor(226, 232, 240)   # #E2E8F0 - Card border
TEXT_MAIN   = RGBColor(30, 41, 59)      # #1E293B - Main text
TEXT_MUTED  = RGBColor(100, 116, 139)   # #64748B - Muted text
TEXT_WHITE  = RGBColor(255, 255, 255)   # #FFFFFF - White text

FONT_TITLE = "Segoe UI"
FONT_BODY  = "Segoe UI"

def create_presentation():
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank_slide_layout = prs.slide_layouts[6]

    def set_slide_background(slide, color):
        background = slide.background
        fill = background.fill
        fill.solid()
        fill.fore_color.rgb = color

    def add_slide_header(slide, category_tag, title_text, subtitle_text=None):
        set_slide_background(slide, BG_LIGHT)
        
        # Decorative top bar
        top_bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(13.333), Inches(0.06))
        top_bar.fill.solid()
        top_bar.fill.fore_color.rgb = PRIMARY_BLUE
        top_bar.line.fill.background()

        # Category Tag (Badge)
        badge_box = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(0.35), Inches(3.2), Inches(0.32))
        badge_box.fill.solid()
        badge_box.fill.fore_color.rgb = LIGHT_BLUE
        badge_box.line.color.rgb = PRIMARY_BLUE
        badge_box.line.width = Pt(1)
        tf_badge = badge_box.text_frame
        tf_badge.word_wrap = True
        p_badge = tf_badge.paragraphs[0]
        p_badge.text = "✦  " + category_tag.upper()
        p_badge.font.name = FONT_TITLE
        p_badge.font.size = Pt(9.5)
        p_badge.font.bold = True
        p_badge.font.color.rgb = PRIMARY_BLUE
        p_badge.alignment = PP_ALIGN.CENTER
        
        # Main Title
        title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.72), Inches(11.7), Inches(0.55))
        tf = title_box.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        p = tf.paragraphs[0]
        p.text = title_text
        p.font.name = FONT_TITLE
        p.font.size = Pt(21)
        p.font.bold = True
        p.font.color.rgb = NAVY_DARK

        # Subtitle if present
        if subtitle_text:
            sub_box = slide.shapes.add_textbox(Inches(0.8), Inches(1.30), Inches(11.7), Inches(0.35))
            tf_sub = sub_box.text_frame
            tf_sub.word_wrap = True
            tf_sub.margin_left = tf_sub.margin_top = tf_sub.margin_right = tf_sub.margin_bottom = 0
            p_sub = tf_sub.paragraphs[0]
            p_sub.text = subtitle_text
            p_sub.font.name = FONT_BODY
            p_sub.font.size = Pt(11)
            p_sub.font.color.rgb = TEXT_MUTED

    def add_card(slide, left, top, width, height, title=None, title_color=PRIMARY_BLUE, bg_color=CARD_BG, border_color=CARD_BORDER, corner_radius=None):
        card = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(left), Inches(top), Inches(width), Inches(height))
        card.fill.solid()
        card.fill.fore_color.rgb = bg_color
        if border_color:
            card.line.color.rgb = border_color
            card.line.width = Pt(1.5)
        else:
            card.line.fill.background()
        
        if title:
            tb = slide.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.18), Inches(width - 0.5), Inches(0.42))
            tf = tb.text_frame
            tf.word_wrap = True
            tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
            p = tf.paragraphs[0]
            p.text = title
            p.font.name = FONT_TITLE
            p.font.size = Pt(13)
            p.font.bold = True
            p.font.color.rgb = title_color
            
        return card

    # =========================================================================
    # SLIDE 1: Cover Slide (Dark Theme Modern Tech)
    # =========================================================================
    s1 = prs.slides.add_slide(blank_slide_layout)
    set_slide_background(s1, NAVY_DARK)

    # Decorative top bar
    bar = s1.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(13.333), Inches(0.12))
    bar.fill.solid()
    bar.fill.fore_color.rgb = CYAN_ACCENT
    bar.line.fill.background()

    # Main Badge
    cov_badge = s1.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(1.0), Inches(1.1), Inches(4.8), Inches(0.38))
    cov_badge.fill.solid()
    cov_badge.fill.fore_color.rgb = NAVY_CARD
    cov_badge.line.color.rgb = PRIMARY_BLUE
    cov_badge.line.width = Pt(1)
    tf_cbadge = cov_badge.text_frame
    p_cbadge = tf_cbadge.paragraphs[0]
    p_cbadge.text = "✦  PLATEFORME REGTECH & SCREENING LCB-FT"
    p_cbadge.font.name = FONT_TITLE
    p_cbadge.font.size = Pt(11)
    p_cbadge.font.bold = True
    p_cbadge.font.color.rgb = CYAN_ACCENT
    p_cbadge.alignment = PP_ALIGN.CENTER

    # Title
    t_box = s1.shapes.add_textbox(Inches(1.0), Inches(1.65), Inches(11.3), Inches(2.0))
    tf = t_box.text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "SwiftCheck AML"
    p.font.name = FONT_TITLE
    p.font.size = Pt(44)
    p.font.bold = True
    p.font.color.rgb = TEXT_WHITE

    p2 = tf.add_paragraph()
    p2.text = "Architecture Modulaire PF4J, Normalisation FollowTheMoney & Moteur de Screening Yente"
    p2.font.name = FONT_BODY
    p2.font.size = Pt(19)
    p2.font.color.rgb = RGBColor(148, 163, 184)
    p2.space_before = Pt(8)

    # 3 Summary Cards at bottom
    features = [
        ("Architecture Modulaire PF4J", "Framework de plugins isolés avec chargement à chaud et mise à jour de parsers sans arrêt de service.", CYAN_ACCENT),
        ("7 Providers Internationaux", "Normalisation automatique (OFAC, UE, ONU, UKSL, DG Trésor, SECO, DFAT) vers le standard FTM .ndjson.", PRIMARY_BLUE),
        ("Moteur Yente & 100% Test Coverage", "Fuzzy matching haute performance, recherche multi-critères, UBOs et banc de test d'intégrité exhaustive.", TEAL_GREEN)
    ]

    for i, (f_title, f_desc, accent_c) in enumerate(features):
        c_left = 1.0 + i * 3.85
        card = s1.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(c_left), Inches(4.1), Inches(3.6), Inches(2.4))
        card.fill.solid()
        card.fill.fore_color.rgb = NAVY_CARD
        card.line.color.rgb = RGBColor(51, 65, 85)
        card.line.width = Pt(1.5)

        line = s1.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(c_left + 0.3), Inches(4.3), Inches(0.8), Inches(0.06))
        line.fill.solid()
        line.fill.fore_color.rgb = accent_c
        line.line.fill.background()

        tb = s1.shapes.add_textbox(Inches(c_left + 0.3), Inches(4.5), Inches(3.0), Inches(1.8))
        tf = tb.text_frame
        tf.word_wrap = True
        p_t = tf.paragraphs[0]
        p_t.text = f_title
        p_t.font.name = FONT_TITLE
        p_t.font.size = Pt(14)
        p_t.font.bold = True
        p_t.font.color.rgb = TEXT_WHITE

        p_d = tf.add_paragraph()
        p_d.text = f_desc
        p_d.font.name = FONT_BODY
        p_d.font.size = Pt(10.5)
        p_d.font.color.rgb = RGBColor(148, 163, 184)
        p_d.space_before = Pt(6)

    # Footer
    foot_box = s1.shapes.add_textbox(Inches(1.0), Inches(6.8), Inches(11.3), Inches(0.4))
    tf_f = foot_box.text_frame
    p_f = tf_f.paragraphs[0]
    p_f.text = "Déploiement UI : http://localhost:8087/providers  •  Backend Spring Boot Core  •  Moteur Yente Docker (Port 8000)"
    p_f.font.name = FONT_BODY
    p_f.font.size = Pt(11)
    p_f.font.color.rgb = RGBColor(100, 116, 139)

    # =========================================================================
    # SLIDE 2: Vision & Enjeux Métier
    # =========================================================================
    s2 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s2, "Vision & Enjeux Métier", "Contexte Réglementaire et Défi de Conformité AML", "Répondre aux obligations strictes de conformité financière (LCB-FT / Sanctions Internationales)")

    add_card(s2, 0.8, 1.8, 5.7, 5.2, "La Problématique du Screening AML", RGBColor(220, 38, 38))
    tb_prob = s2.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_prob = tb_prob.text_frame
    tf_prob.word_wrap = True
    
    bullets_prob = [
        ("Hétérogénéité des Formats :", "Chaque autorité (OFAC, UE, ONU, etc.) publie sous un schéma propriétaire (XML complexe, CSV, JSON, XLS)."),
        ("Évolution Permanente & Risque Réglementaire :", "Mises à jour quotidiennes des sanctions. Un seul faux négatif (sanction manquée) entraîne des pénalités massives."),
        ("Coûts Exorbitants des Fournisseurs Commerciaux :", "Les solutions fermées (World-Check, LexisNexis) imposent des coûts récurrents élevés et une opacité totale sur les algorithmes."),
        ("Faux Positifs & Homonymie :", "La difficulté de concilier vitesse de traitement et précision du matching flou (orthographes, translittérations).")
    ]
    for i, (head, text) in enumerate(bullets_prob):
        p = tf_prob.paragraphs[0] if i == 0 else tf_prob.add_paragraph()
        p.space_after = Pt(8)
        r1 = p.add_run()
        r1.text = "• " + head + " "
        r1.font.bold = True
        r1.font.color.rgb = NAVY_DARK
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = text
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    add_card(s2, 6.8, 1.8, 5.7, 5.2, "L'Approche Technologique SwiftCheck", PRIMARY_BLUE)
    tb_sol = s2.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_sol = tb_sol.text_frame
    tf_sol.word_wrap = True
    
    bullets_sol = [
        ("Modularité Plugin (PF4J) :", "Chaque source de sanctions est isolée dans un JAR autonome. Ajout ou mise à jour sans redémarrage du socle."),
        ("Standard Universel FollowTheMoney (FTM) :", "Unification de toutes les entités (Personnes, Sociétés, Navires, Sanctions) sous un schéma sémantique éprouvé."),
        ("Moteur Haute Performance Yente :", "Screening instantané en mémoire avec scoring de pertinence, multi-critères et support phonétique."),
        ("Test d'Intégrité Automatisé (Zero Missed) :", "Validation exhaustive de 100% des entités de chaque liste pour garantir qu'aucune sanction n'est omise.")
    ]
    for i, (head, text) in enumerate(bullets_sol):
        p = tf_sol.paragraphs[0] if i == 0 else tf_sol.add_paragraph()
        p.space_after = Pt(8)
        r1 = p.add_run()
        r1.text = "✔ " + head + " "
        r1.font.bold = True
        r1.font.color.rgb = TEAL_GREEN
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = text
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    # =========================================================================
    # SLIDE 3: Architecture Globale du Système
    # =========================================================================
    s3 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s3, "Architecture Technique", "Architecture Globale en 3 Couches", "De la collecte des données brutes au screening en temps réel et à la restitution")

    cols = [
        ("1. Ingestion & Plugins (PF4J)", [
            ("Sources Officielles", "OFAC, UE, ONU, UKSL, DG Trésor, SECO, DFAT"),
            ("Plugins Dynamiques", "JARs autonomes implémentant l'interface Iparser"),
            ("Normalisation FTM", "Génération de fichiers normalisés .ndjson"),
            ("Validation Stricte", "Contrôle de validité des entités FollowTheMoney")
        ], PRIMARY_BLUE),
        ("2. Indexation & Moteur (Yente / FTM)", [
            ("Moteur Docker Yente", "Service conteneurisé exposé sur port 8000"),
            ("Manifest Dynamique", "Configuration automatique des jeux de données (manifest.yml)"),
            ("Dataset Consolidé", "Scope 'default' fusionnant toutes les listes mondiales"),
            ("Fuzzy Matching", "Indexation et scoring sémantique haute performance")
        ], PURPLE_ACC),
        ("3. Core Backend & UI (Spring / Angular)", [
            ("Spring Boot Core", "Orchestration, API REST, Gestion des plugins, JasperReports"),
            ("Base de Données", "Gestion des Providers, Historique d'Audit, Exécutions"),
            ("Frontend Angular", "Interface moderne déployée sur localhost:8087"),
            ("Reporting & Export", "Génération de certificats de vérification PDF certifiés")
        ], TEAL_GREEN)
    ]

    for i, (col_title, items, accent) in enumerate(cols):
        c_left = 0.8 + i * 3.95
        add_card(s3, c_left, 1.8, 3.8, 5.2, col_title, accent)
        
        tb = s3.shapes.add_textbox(Inches(c_left + 0.2), Inches(2.4), Inches(3.4), Inches(4.4))
        tf = tb.text_frame
        tf.word_wrap = True
        
        for j, (header, desc) in enumerate(items):
            p = tf.paragraphs[0] if j == 0 else tf.add_paragraph()
            p.space_after = Pt(10)
            r1 = p.add_run()
            r1.text = f"• {header}\n"
            r1.font.bold = True
            r1.font.size = Pt(11)
            r1.font.color.rgb = NAVY_DARK
            
            r2 = p.add_run()
            r2.text = desc
            r2.font.size = Pt(10)
            r2.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 4: La Modularité avec PF4J
    # =========================================================================
    s4 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s4, "Modularité & Extensibilité", "Architecture Plugin Dynamique avec PF4J", "Chargement, exécution et mise à jour à chaud des parsers de listes sans interruption de service")

    add_card(s4, 0.8, 1.8, 6.0, 5.2, "Fonctionnement du Moteur PF4J", PRIMARY_BLUE)
    tb_pf4j = s4.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.5), Inches(4.4))
    tf_p = tb_pf4j.text_frame
    tf_p.word_wrap = True
    
    pf4j_points = [
        ("Découplage Total (SPI) :", "Le socle applicatif ne dépend d'aucun parser spécifique. Tous les parsers implémentent l'interface standard Iparser."),
        ("Chargement Dynamique de JARs :", "Chaque parser est packagé en JAR indépendant dans /plugins. SpringPluginManager charge et initialise les classes dynamiquement."),
        ("Cycle de Vie Isolé (Zero Downtime) :", "Détection automatique, activation, désactivation et remplacement de plugin sans redémarrer le serveur Spring Boot."),
        ("Exécution & Isolation des Erreurs :", "Si un schéma source change ou produit une erreur, seul le plugin concerné est marqué FAILED ; le reste du système continue de fonctionner.")
    ]
    for i, (title, desc) in enumerate(pf4j_points):
        p = tf_p.paragraphs[0] if i == 0 else tf_p.add_paragraph()
        p.space_after = Pt(10)
        r1 = p.add_run()
        r1.text = "▶ " + title + " "
        r1.font.bold = True
        r1.font.color.rgb = NAVY_DARK
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = desc
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    add_card(s4, 7.1, 1.8, 5.4, 5.2, "Contrat d'Interface : Iparser", NAVY_CARD, bg_color=NAVY_DARK, border_color=PRIMARY_BLUE)
    tb_code = s4.shapes.add_textbox(Inches(7.3), Inches(2.4), Inches(5.0), Inches(4.4))
    tf_c = tb_code.text_frame
    tf_c.word_wrap = True

    code_text = (
        "// swiftcheck-aml-api : Contrat commun PF4J\n"
        "package com.swiftcheck.aml.config;\n\n"
        "import org.pf4j.ExtensionPoint;\n\n"
        "public interface Iparser extends ExtensionPoint {\n\n"
        "    // Parsing de la source brute vers FTM .ndjson\n"
        "    PraserExecutionResult parse(ParserConfig config);\n\n"
        "    // Validation complète de 100% des entités\n"
        "    ExecutionTestResult executeTests(\n"
        "        ParserTestConfig config\n"
        "    );\n"
        "}\n\n"
        "/* Services Clés Core :\n"
        " * - PluginManagementService (Lifecycle)\n"
        " * - ParserExecutionService (Historique)\n"
        " * - ParserTestExecutionService (Validation)\n"
        " */"
    )
    p_code = tf_c.paragraphs[0]
    p_code.text = code_text
    p_code.font.name = "Consolas"
    p_code.font.size = Pt(9.5)
    p_code.font.color.rgb = RGBColor(226, 232, 240)

    # =========================================================================
    # SLIDE 5: Les 7 Fournisseurs de Sanctions
    # =========================================================================
    s5 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s5, "Couverture Réglementaire", "Les 7 Fournisseurs de Sanctions Officiels", "Intégration et parsing exhaustif des principales listes de sanctions mondiales")

    providers = [
        ("🇺🇸 US OFAC", "SDN & Consolidated List", "XML / CSV", "Trésor américain - Individus, entités terroristes, narcotrafiquants, pays sous embargo."),
        ("🇪🇺 Union Européenne", "EU Financial Sanctions", "XML Global", "Service Européen pour l'Action Extérieure (SEAE) - Mesures restrictives de l'UE."),
        ("🇺🇳 Nations Unies", "UN Consolidated List", "XML Structuré", "Conseil de Sécurité de l'ONU - Sanctions internationales obligatoires pour tous les États."),
        ("🇬🇧 UKSL / OFSI", "UK Sanctions List", "XML / CSV", "HM Treasury & FCDO - Régimes de sanctions post-Brexit du Royaume-Uni."),
        ("🇫🇷 DG Trésor", "Registre National des Gels", "JSON API/File", "Direction Générale du Trésor - Décisions nationales françaises de gel des avoirs."),
        ("🇨🇭 SECO Suisse", "Sanctions de la Suisse", "XML Officiel", "Secrétariat d'État à l'économie - Mesures de coercition de la Confédération suisse."),
        ("🇦🇺 DFAT Australie", "Australian Sanctions", "CSV / Excel", "Department of Foreign Affairs and Trade - Listes de sanctions du gouvernement australien.")
    ]

    for i, (name, role, fmt, desc) in enumerate(providers[:4]):
        c_left = 0.8 + i * 2.95
        card = add_card(s5, c_left, 1.8, 2.8, 2.45, name, PRIMARY_BLUE)
        tb = s5.shapes.add_textbox(Inches(c_left + 0.15), Inches(2.35), Inches(2.5), Inches(1.8))
        tf = tb.text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = f"{role}\nFormat : {fmt}"
        p.font.bold = True
        p.font.size = Pt(10)
        p.font.color.rgb = NAVY_DARK
        p2 = tf.add_paragraph()
        p2.text = desc
        p2.font.size = Pt(9)
        p2.font.color.rgb = TEXT_MUTED
        p2.space_before = Pt(4)

    for i, (name, role, fmt, desc) in enumerate(providers[4:]):
        c_left = 0.8 + i * 3.95
        card = add_card(s5, c_left, 4.45, 3.8, 2.5, name, PRIMARY_BLUE)
        tb = s5.shapes.add_textbox(Inches(c_left + 0.2), Inches(4.95), Inches(3.4), Inches(1.9))
        tf = tb.text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = f"{role}  •  Format : {fmt}"
        p.font.bold = True
        p.font.size = Pt(10.5)
        p.font.color.rgb = NAVY_DARK
        p2 = tf.add_paragraph()
        p2.text = desc
        p2.font.size = Pt(9.5)
        p2.font.color.rgb = TEXT_MUTED
        p2.space_before = Pt(4)

    # =========================================================================
    # SLIDE 6: Standard FollowTheMoney (FTM) & NDJSON
    # =========================================================================
    s6 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s6, "Modélisation des Données", "Le Standard FollowTheMoney (FTM)", "Unification des données hétérogènes sous un modèle sémantique de graphe orienté entités")

    add_card(s6, 0.8, 1.8, 5.7, 5.2, "Pourquoi FollowTheMoney (FTM) ?", PRIMARY_BLUE)
    tb_ftm = s6.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_f = tb_ftm.text_frame
    tf_f.word_wrap = True
    
    ftm_points = [
        ("Standard International Ouvert :", "Créé par OpenSanctions et l'OCCRP, FTM est la référence mondiale pour la modélisation des entités financières, des sanctions et des PEP."),
        ("Modèle Basé sur les Entités (Schema-driven) :", "Support natif de types riches : Person, Company, Organization, Vessel, Airplane, Sanction, Address, Passport, Directorship."),
        ("Liaisons & Relations Complexes :", "Capacité à exprimer les liens familiaux, bénéficiaires effectifs (UBO), prête-noms et appartenance à des groupes."),
        ("Format NDJSON (Newline Delimited JSON) :", "Chaque ligne représente une entité JSON autonome, idéale pour le streaming haute vitesse et l'indexation sans blocage mémoire.")
    ]
    for i, (title, desc) in enumerate(ftm_points):
        p = tf_f.paragraphs[0] if i == 0 else tf_f.add_paragraph()
        p.space_after = Pt(10)
        r1 = p.add_run()
        r1.text = "◆ " + title + " "
        r1.font.bold = True
        r1.font.color.rgb = NAVY_DARK
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = desc
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    add_card(s6, 6.8, 1.8, 5.7, 5.2, "Exemple d'Entité FTM Générée", NAVY_CARD, bg_color=NAVY_DARK, border_color=PRIMARY_BLUE)
    tb_json = s6.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_j = tb_json.text_frame
    tf_j.word_wrap = True

    sample_json = (
        "{\n"
        '  "id": "ofac-12345",\n'
        '  "schema": "Person",\n'
        '  "caption": "VLADIMIR IVANOV",\n'
        '  "properties": {\n'
        '    "name": ["VLADIMIR IVANOV", "IVANOV Vladimir"],\n'
        '    "alias": ["Volodya"],\n'
        '    "birthDate": ["1975-04-12"],\n'
        '    "nationality": ["ru"],\n'
        '    "passportNumber": ["758492019"],\n'
        '    "topics": ["sanction", "role.pep"],\n'
        '    "sanctions": ["ofac-sanction-889"]\n'
        "  },\n"
        '  "datasets": ["ofac_sanctions"]\n'
        "}\n\n"
        "// Fichier produit : ofac_sanctions_ftm.ndjson\n"
        "// Consommé directement par le moteur Yente"
    )
    p_j = tf_j.paragraphs[0]
    p_j.text = sample_json
    p_j.font.name = "Consolas"
    p_j.font.size = Pt(9.5)
    p_j.font.color.rgb = RGBColor(226, 232, 240)

    # =========================================================================
    # SLIDE 7: Moteur de Screening Yente (Docker & API REST)
    # =========================================================================
    s7 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s7, "Moteur de Screening", "Moteur de Recherche Yente & Fuzzy Matching", "Le moteur ultra-rapide basé sur FTM pour le filtrage en temps réel et le calcul de pertinence")

    cards_yente = [
        ("Architecture Conteneurisée", [
            ("Déploiement Docker :", "Yente s'exécute dans un conteneur dédié (port 8000), garantissant scalabilité et isolation."),
            ("Manifest Dynamique :", "Le fichier manifest.yml catalogue chaque jeu de données (EU, ONU, OFAC, etc.) et configure le scope consolidé 'default'."),
            ("Synchronisation à Chaud :", "Appel automatique à l'API Yente POST /update dès qu'un parser termine son exécution.")
        ], PRIMARY_BLUE),
        ("Algorithmes de Matching Flou", [
            ("Scoring Multi-Critères :", "Calcul d'un score de 0.0 à 1.0 basé sur la distance Levenshtein, Jaro-Winkler et la similarité n-gram."),
            ("Translittération & Phonétique :", "Prise en charge des variations de noms (alphabet cyrillique, arabe, alias officiels et prénoms secondaires)."),
            ("Seuils Configurables :", "Filtrage paramétrable (ex: threshold=0.5) pour ajuster la sensibilité et réduire les faux positifs.")
        ], CYAN_ACCENT),
        ("API REST Dédiée", [
            ("Endpoint POST /match/{dataset} :", "Soumission de requêtes FTM (Person, Company) pour vérification unitaire ou par lot."),
            ("Support Multi-Datasets :", "Possibilité de cibler une liste spécifique (ex: /match/eu_sanctions) ou toutes les listes (/match/default)."),
            ("Enrichissement des Réponses :", "Renvoi des scores, motifs de sanction, alias correspondants et détails complets de l'entité.")
        ], TEAL_GREEN)
    ]

    for i, (title, items, accent) in enumerate(cards_yente):
        c_left = 0.8 + i * 3.95
        add_card(s7, c_left, 1.8, 3.8, 5.2, title, accent)
        tb = s7.shapes.add_textbox(Inches(c_left + 0.2), Inches(2.4), Inches(3.4), Inches(4.3))
        tf = tb.text_frame
        tf.word_wrap = True
        
        for j, (head, desc) in enumerate(items):
            p = tf.paragraphs[0] if j == 0 else tf.add_paragraph()
            p.space_after = Pt(10)
            r1 = p.add_run()
            r1.text = "• " + head + " "
            r1.font.bold = True
            r1.font.size = Pt(10.5)
            r1.font.color.rgb = NAVY_DARK
            r2 = p.add_run()
            r2.text = desc
            r2.font.size = Pt(10)
            r2.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 8 (NOUVELLE): Typologie des Recherches Réalisées par Yente
    # =========================================================================
    s8 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s8, "Moteur de Recherche", "Typologie des Recherches Réalisées par Yente", "Panorama des modes de screening implémentés dans l'API et l'IHM SwiftCheck AML")

    search_types = [
        ("1. Screening Nom/Prénom (Name Screening)", "Recherche floue sur nom complet, nom de famille, prénom et alias. Tolérance aux inversions (Nom Prénom vs Prénom Nom) et fautes de frappe.", "Ex: 'Vladimir Putin' vs 'PUTIN, Vladimir'", PRIMARY_BLUE),
        ("2. Recherche Multi-Critères (Property-Based)", "Combinaison simultanée de plusieurs attributs FTM : Nom + Date de naissance + Nationalité + Pays de résidence pour affiner drastiquement le score.", "Ex: schema: Person, birthDate: 1975, nationality: ru", INDIGO_ACC),
        ("3. Recherche Entreprise & UBOs en Parallèle", "Screening de la personne morale (Company) et exécution concurrente (parallelStream) sur l'ensemble de ses bénéficiaires effectifs (UBOs).", "Ex: 'Gazprom LLC' + UBOs ['Alexey M.', 'Igor S.']", TEAL_GREEN),
        ("4. Screening par Identifiants & Documents", "Recherche exacte ou semi-exacte sur numéros de passeport, cartes nationales d'identité, numéros d'enregistrement fiscal (TIN/SIREN).", "Ex: passportNumber: '758492019', taxNumber: '99201'", PURPLE_ACC),
        ("5. Multi-Entités Spécialisées (Navires / Avions)", "Screening adapté aux biens matériels sous embargo : navires marchands (Vessel / IMO number) et aéronefs (Airplane / Tail number).", "Ex: schema: Vessel, imoNumber: '9123456'", AMBER_ALERT),
        ("6. Filtrage par Scope Dédié vs Consolidé", "Interrogation ciblée d'une juridiction précise (/match/ofac_sanctions) ou screening global unifié sur les 7 listes (/match/default).", "Ex: dataset: 'eu_sanctions' ou 'default'", CYAN_ACCENT)
    ]

    for i, (title, desc, example, accent) in enumerate(search_types):
        row = i // 3
        col = i % 3
        c_left = 0.8 + col * 3.95
        c_top = 1.8 + row * 2.65
        
        card = add_card(s8, c_left, c_top, 3.8, 2.5, title, accent)
        
        tb = s8.shapes.add_textbox(Inches(c_left + 0.2), Inches(c_top + 0.65), Inches(3.4), Inches(1.75))
        tf = tb.text_frame
        tf.word_wrap = True
        
        p = tf.paragraphs[0]
        p.text = desc
        p.font.name = FONT_BODY
        p.font.size = Pt(10)
        p.font.color.rgb = TEXT_MAIN
        
        p2 = tf.add_paragraph()
        p2.space_before = Pt(6)
        r_ex_lbl = p2.add_run()
        r_ex_lbl.text = "Cas d'usage : "
        r_ex_lbl.font.bold = True
        r_ex_lbl.font.size = Pt(9.5)
        r_ex_lbl.font.color.rgb = NAVY_DARK
        
        r_ex = p2.add_run()
        r_ex.text = example
        r_ex.font.size = Pt(9)
        r_ex.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 9 (NOUVELLE): Schéma Interactif - Pipeline de Données de Bout en Bout
    # =========================================================================
    s9 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s9, "Pipeline Automatisé", "Pipeline de Données : De la Source Brute à Yente", "Cycle de vie complet de l'ingestion, transformation FTM, indexation et validation d'intégrité")

    pipeline_steps = [
        ("1. Téléchargement Source", "Collecte du flux officiel brut", "XML, CSV, JSON", "Téléchargement automatique depuis les serveurs officiels (OFAC, UE, ONU, etc.) ou chargement manuel.", PRIMARY_BLUE),
        ("2. Parsing StAX & FTM", "Transformation en NDJSON", "StAX / ValueEntity", "Le parser PF4J extrait les entités, résout les schémas et écrit dans un fichier .ndjson standardisé.", INDIGO_ACC),
        ("3. Sync Manifest", "Mise à jour Yente Manifest", "manifest.yml", "YenteManifestService actualise les datasets et appelle l'API Yente POST /update pour notifier le conteneur.", PURPLE_ACC),
        ("4. Indexation Yente", "Chargement en mémoire", "Fuzzy Search Engine", "Yente recharge le fichier NDJSON, indexe les termes, phonétiques et alias pour un screening immédiat.", TEAL_GREEN),
        ("5. Test d'Intégrité Exhaustif", "Validation 100% des entités", "executeTests()", "Re-vérifie automatiquement chaque individu de la liste originale contre Yente pour garantir 0 oubli !", AMBER_ALERT)
    ]

    for i, (st_num, st_title, st_badge, st_desc, accent) in enumerate(pipeline_steps):
        c_left = 0.8 + i * 2.38
        
        # Step Container Card
        card = s9.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(c_left), Inches(1.8), Inches(2.25), Inches(5.2))
        card.fill.solid()
        card.fill.fore_color.rgb = CARD_BG
        card.line.color.rgb = CARD_BORDER
        card.line.width = Pt(1.5)

        # Number Header Pill
        pill = s9.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(c_left + 0.15), Inches(1.95), Inches(1.95), Inches(0.45))
        pill.fill.solid()
        pill.fill.fore_color.rgb = accent
        pill.line.fill.background()
        tf_pill = pill.text_frame
        p_pill = tf_pill.paragraphs[0]
        p_pill.text = st_num
        p_pill.font.name = FONT_TITLE
        p_pill.font.size = Pt(11)
        p_pill.font.bold = True
        p_pill.font.color.rgb = TEXT_WHITE
        p_pill.alignment = PP_ALIGN.CENTER

        # Title
        tb_t = s9.shapes.add_textbox(Inches(c_left + 0.15), Inches(2.55), Inches(1.95), Inches(0.6))
        tf_t = tb_t.text_frame
        tf_t.word_wrap = True
        p_t = tf_t.paragraphs[0]
        p_t.text = st_title
        p_t.font.name = FONT_TITLE
        p_t.font.size = Pt(12)
        p_t.font.bold = True
        p_t.font.color.rgb = NAVY_DARK

        # Format Badge
        bdg = s9.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(c_left + 0.15), Inches(3.2), Inches(1.95), Inches(0.35))
        bdg.fill.solid()
        bdg.fill.fore_color.rgb = LIGHT_BLUE
        bdg.line.color.rgb = PRIMARY_BLUE
        bdg.line.width = Pt(1)
        tf_bdg = bdg.text_frame
        p_bdg = tf_bdg.paragraphs[0]
        p_bdg.text = st_badge
        p_bdg.font.name = "Consolas"
        p_bdg.font.size = Pt(9)
        p_bdg.font.bold = True
        p_bdg.font.color.rgb = PRIMARY_BLUE
        p_bdg.alignment = PP_ALIGN.CENTER

        # Description
        tb_d = s9.shapes.add_textbox(Inches(c_left + 0.15), Inches(3.7), Inches(1.95), Inches(3.1))
        tf_d = tb_d.text_frame
        tf_d.word_wrap = True
        p_d = tf_d.paragraphs[0]
        p_d.text = st_desc
        p_d.font.name = FONT_BODY
        p_d.font.size = Pt(10)
        p_d.font.color.rgb = TEXT_MUTED

        # Arrow indicator between cards (except last)
        if i < 4:
            arrow = s9.shapes.add_textbox(Inches(c_left + 2.15), Inches(3.8), Inches(0.4), Inches(0.4))
            tf_arr = arrow.text_frame
            p_arr = tf_arr.paragraphs[0]
            p_arr.text = "➔"
            p_arr.font.size = Pt(16)
            p_arr.font.bold = True
            p_arr.font.color.rgb = PRIMARY_BLUE
            p_arr.alignment = PP_ALIGN.CENTER

    # =========================================================================
    # SLIDE 10 (NOUVELLE): Banc de Tests d'Intégrité - "Zéro Sanction Ratée"
    # =========================================================================
    s10 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s10, "Qualité & Fiabilité", "Banc de Tests d'Intégrité : 'Zéro Sanction Ratée'", "Validation automatique exhaustive de chaque entité de la liste pour garantir l'absence de faux négatifs")

    add_card(s10, 0.8, 1.8, 6.0, 5.2, "Mécanisme de Test Intégré (executeTests)", PRIMARY_BLUE)
    tb_t = s10.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.5), Inches(4.4))
    tf_t = tb_t.text_frame
    tf_t.word_wrap = True
    
    test_points = [
        ("Test Automatisé de Chaque Entité :", "Après chaque parsing, le système boucle sur 100% des cibles (individus/entreprises) de la source originale pour les soumettre à Yente."),
        ("Exécution Parallélisée Haute Vitesse :", "Utilisation de CompletableFuture et ExecutorService pour tester des milliers d'entités en quelques secondes."),
        ("Détection des Ruptures de Schémas :", "Si l'OFAC ou l'UE modifie une balise XML, les entités manquantes sont immédiatement identifiées avec leurs détails d'erreur exacts."),
        ("Arrêt d'Urgence & Sécurité :", "Si 100 tests échouent consécutivement, le système stoppe le test et marque le parser FAILED pour éviter de déployer un index corrompu."),
        ("Journalisation Précise (Failed Cases) :", "Enregistrement du JSON envoyé, du statut HTTP, de la réponse Yente et du motif d'échec pour une correction immédiate.")
    ]
    for i, (title, desc) in enumerate(test_points):
        p = tf_t.paragraphs[0] if i == 0 else tf_t.add_paragraph()
        p.space_after = Pt(8)
        r1 = p.add_run()
        r1.text = "✔ " + title + " "
        r1.font.bold = True
        r1.font.color.rgb = TEAL_GREEN
        r1.font.size = Pt(10.5)
        r2 = p.add_run()
        r2.text = desc
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10)

    add_card(s10, 7.1, 1.8, 5.4, 5.2, "Résultats & Métriques de Validation", NAVY_CARD, bg_color=NAVY_DARK, border_color=TEAL_GREEN)
    tb_res = s10.shapes.add_textbox(Inches(7.3), Inches(2.4), Inches(5.0), Inches(4.4))
    tf_res = tb_res.text_frame
    tf_res.word_wrap = True

    metric_blocks = [
        ("Taux de Réussite (Success Rate)", "100.0%", "Pourcentage d'entités officiellement reconnues par Yente", TEAL_GREEN),
        ("Statut de Non-Régression", "PASSED", "Validation stricte du schéma FTM et des index phonétiques", CYAN_ACCENT),
        ("Zéro Faux Négatif", "Garantie Réglementaire", "Aucun individu sous sanction n'est omis lors du filtrage", AMBER_ALERT),
        ("Tableau de Bord Global", "/global-test-executions", "Suivi centralisé de la conformité de tous les parsers", RGBColor(148, 163, 184))
    ]

    for i, (lbl, val, sub, col) in enumerate(metric_blocks):
        p = tf_res.paragraphs[0] if i == 0 else tf_res.add_paragraph()
        p.space_after = Pt(10)
        
        r1 = p.add_run()
        r1.text = f"{lbl} : "
        r1.font.size = Pt(10.5)
        r1.font.color.rgb = RGBColor(203, 213, 225)
        
        r2 = p.add_run()
        r2.text = f"{val}\n"
        r2.font.bold = True
        r2.font.size = Pt(13)
        r2.font.color.rgb = col
        
        r3 = p.add_run()
        r3.text = sub
        r3.font.size = Pt(9)
        r3.font.color.rgb = RGBColor(148, 163, 184)

    # =========================================================================
    # SLIDE 11: Architecture Backend Spring Boot Core & JasperReports
    # =========================================================================
    s11 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s11, "Backend & Services", "Services Clés du Backend Spring Boot", "Orchestration, logique métier, audit et génération de rapports réglementaires")

    services = [
        ("PluginManagementService", "Gestionnaire central du cycle de vie PF4J. Détecte les JARs dans /plugins, instancie les Iparser, exécute les parsers et pilote les tests de validation."),
        ("AmlVerificationService", "Point d'entrée du screening AML. Construit les requêtes FTM, interroge l'API Yente (/match/default), calcule les correspondances et enregistre l'historique."),
        ("YenteManifestService", "Génère et synchronise dynamiquement le fichier manifest.yml de Yente en fonction des parsers configurés, puis déclenche le rechargement via webhook."),
        ("AmlSearchHistoryService", "Assure la traçabilité complète de chaque requête de vérification (identité cherchée, date, utilisateur, score, statut, correspondances trouvées)."),
        ("ParserExecutionService", "Supervise l'état d'exécution des crawlers (RUNNING, SUCCESS, FAILED), consigne les logs d'erreurs et les métriques de traitement."),
        ("Reporting JasperReports", "Moteur d'export PDF produisant des certificats officiels de conformité avec QR Code, score de risque et pistes d'audit pour les régulateurs.")
    ]

    for i, (name, desc) in enumerate(services):
        row = i // 2
        col = i % 2
        c_left = 0.8 + col * 5.95
        c_top = 1.8 + row * 1.7
        
        card = add_card(s11, c_left, c_top, 5.8, 1.55, name, PRIMARY_BLUE)
        tb = s11.shapes.add_textbox(Inches(c_left + 0.2), Inches(c_top + 0.55), Inches(5.4), Inches(0.9))
        tf = tb.text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = desc
        p.font.name = FONT_BODY
        p.font.size = Pt(10)
        p.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 12: Frontend Angular (localhost:8087)
    # =========================================================================
    s12 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s12, "Frontend & IHM", "Interface Utilisateur Angular (localhost:8087)", "Une IHM moderne, intuitive et modulaire dédiée aux analystes et officiers de conformité")

    ui_modules = [
        ("Gestion des Fournisseurs (/providers)", "Visualisation des 7 providers officiels, statut des flux, configuration des URLs sources et destination.", PRIMARY_BLUE),
        ("Exécution des Parsers (/parser-executions)", "Suivi en temps réel des traitements, logs détaillés, volume d'entités extraites et déclenchement manuel ou planifié.", PURPLE_ACC),
        ("Banc de Tests Global (/global-test-executions)", "Tableau de bord de validation des parsers pour détecter immédiatement tout changement de format des listes.", TEAL_GREEN),
        ("Screening AML (/aml-verification)", "Formulaire de vérification unitaire et avancée (Personne physique ou morale), affichage des scores et des correspondances.", CYAN_ACCENT),
        ("Historique d'Audit (/aml-search-history)", "Registre complet des recherches de conformité, filtres temporels et téléchargement des rapports officiels PDF.", AMBER_ALERT),
        ("Internationalisation & Ergonomie", "Support multilingue complet (ngx-translate FR/EN), design responsive et composants UI optimisés.", RGBColor(79, 70, 229))
    ]

    for i, (title, desc, accent) in enumerate(ui_modules):
        row = i // 3
        col = i % 3
        c_left = 0.8 + col * 3.95
        c_top = 1.8 + row * 2.65
        
        card = add_card(s12, c_left, c_top, 3.8, 2.5, title, accent)
        tb = s12.shapes.add_textbox(Inches(c_left + 0.2), Inches(c_top + 0.65), Inches(3.4), Inches(1.7))
        tf = tb.text_frame
        tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = desc
        p.font.name = FONT_BODY
        p.font.size = Pt(10.5)
        p.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 13: Exemple Concret de Cas d'Usage & Rapport
    # =========================================================================
    s13 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s13, "Cas Pratique", "Exemple de Screening & Rapport JasperReports", "Démonstration du flux complet d'une vérification client jusqu'à l'export certifié PDF")

    add_card(s13, 0.8, 1.8, 5.7, 5.2, "Exemple de Requête & Réponse JSON Yente", NAVY_CARD, bg_color=NAVY_DARK, border_color=PRIMARY_BLUE)
    tb_req = s13.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_req = tb_req.text_frame
    tf_req.word_wrap = True

    json_demo = (
        "// POST http://localhost:8087/api/aml/verify\n"
        "{\n"
        '  "schema": "Person",\n'
        '  "dataset": "default",\n'
        '  "properties": {\n'
        '    "name": ["VLADIMIR IVANOV"],\n'
        '    "nationality": ["ru"],\n'
        '    "birthDate": ["1975-04-12"]\n'
        "  }\n"
        "}\n\n"
        "// Réponse Yente (Score & Entité correspondante) :\n"
        "{\n"
        '  "score": 0.94,\n'
        '  "match": true,\n'
        '  "id": "ofac-12345",\n'
        '  "caption": "VLADIMIR IVANOV",\n'
        '  "datasets": ["ofac_sanctions"],\n'
        '  "sanctions": ["Executive Order 14024"]\n'
        "}"
    )
    p_req = tf_req.paragraphs[0]
    p_req.text = json_demo
    p_req.font.name = "Consolas"
    p_req.font.size = Pt(9.5)
    p_req.font.color.rgb = RGBColor(226, 232, 240)

    add_card(s13, 6.8, 1.8, 5.7, 5.2, "Restitution & Certificat Officiel PDF", PRIMARY_BLUE)
    tb_rep = s13.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_rep = tb_rep.text_frame
    tf_rep.word_wrap = True

    report_bullets = [
        ("Génération Automatique JasperReports :", "Template officiel aml_report.jrxml compilé dynamiquement."),
        ("Indicateur Visuel Statut :", "Badge 'MATCH' rouge en cas de détection positive, ou badge 'CLEAN' vert si aucune correspondance."),
        ("Détail Exhaustif des Correspondances :", "Affichage des alias, numéros de passeport, programmes de sanctions applicables et pays concernés."),
        ("Horodatage & Traçabilité Réglementaire :", "Horodatage certifié, identifiant unique de recherche pour présentation aux régulateurs lors des audits LCB-FT."),
        ("Export Immédiat :", "Téléchargeable en 1 clic depuis l'IHM Angular (/aml-search-history).")
    ]
    for i, (title, desc) in enumerate(report_bullets):
        p = tf_rep.paragraphs[0] if i == 0 else tf_rep.add_paragraph()
        p.space_after = Pt(10)
        r1 = p.add_run()
        r1.text = "📄 " + title + " "
        r1.font.bold = True
        r1.font.color.rgb = NAVY_DARK
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = desc
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    # =========================================================================
    # SLIDE 14: Synthèse & Roadmap Stratégique
    # =========================================================================
    s14 = prs.slides.add_slide(blank_slide_layout)
    add_slide_header(s14, "Bilan & Perspectives", "Synthèse Technique & Roadmap d'Évolution", "Une base technologique d'excellence prête pour les fonctionnalités avancées de conformité")

    add_card(s14, 0.8, 1.8, 5.7, 5.2, "Forces Actuelles de la Plateforme", PRIMARY_BLUE)
    tb_f = s14.shapes.add_textbox(Inches(1.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_f2 = tb_f.text_frame
    tf_f2.word_wrap = True
    
    strengths = [
        ("Architecture Plugin PF4J :", "Extensibilité infinie sans refactorisation du socle."),
        ("Standard FollowTheMoney :", "Interopérabilité maximale avec l'écosystème open-source mondial."),
        ("Performance Yente :", "Screening en quelques millisecondes sur des millions d'enregistrements."),
        ("Intégrité 100% Testée :", "Banc de validation automatique garantissant zéro sanction manquée."),
        ("Expérience Complète :", "IHM Angular réactive, historique, i18n et rapports PDF certifiés.")
    ]
    for i, (title, desc) in enumerate(strengths):
        p = tf_f2.paragraphs[0] if i == 0 else tf_f2.add_paragraph()
        p.space_after = Pt(10)
        r1 = p.add_run()
        r1.text = "★ " + title + " "
        r1.font.bold = True
        r1.font.color.rgb = PRIMARY_BLUE
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = desc
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    add_card(s14, 6.8, 1.8, 5.7, 5.2, "Axes d'Évolution Stratégique (Roadmap)", PURPLE_ACC)
    tb_r = s14.shapes.add_textbox(Inches(7.05), Inches(2.4), Inches(5.2), Inches(4.3))
    tf_r = tb_r.text_frame
    tf_r.word_wrap = True
    
    roadmap = [
        ("Case Management (Gestion des Dossiers) :", "Workflow d'investigation, qualification des alertes, principe des 4 yeux (Maker-Checker) et Whitelisting des faux positifs."),
        ("Surveillance Continue (Ongoing Monitoring) :", "Re-screening automatique de la base clients lors de la publication de nouvelles sanctions avec alertes delta."),
        ("Connecteurs PEP & Médias Défavorables :", "Intégration de flux complémentaires d'Adverse Media et de Personnes Politiquement Exposées."),
        ("Intégration SI / API Webhook :", "Connecteurs temps réel vers les Core Banking et ERP financiers.")
    ]
    for i, (title, desc) in enumerate(roadmap):
        p = tf_r.paragraphs[0] if i == 0 else tf_r.add_paragraph()
        p.space_after = Pt(10)
        r1 = p.add_run()
        r1.text = "🚀 " + title + " "
        r1.font.bold = True
        r1.font.color.rgb = PURPLE_ACC
        r1.font.size = Pt(11)
        r2 = p.add_run()
        r2.text = desc
        r2.font.color.rgb = TEXT_MUTED
        r2.font.size = Pt(10.5)

    # Save presentation with fallback if file is open in PowerPoint
    primary_path = os.path.join(os.path.dirname(__file__), "SwiftCheck_AML_Presentation_Technique.pptx")
    v2_path = os.path.join(os.path.dirname(__file__), "SwiftCheck_AML_Presentation_Technique_v2.pptx")
    
    saved_path = None
    try:
        prs.save(primary_path)
        saved_path = primary_path
        print(f"Presentation saved successfully to: {primary_path}")
    except PermissionError:
        prs.save(v2_path)
        saved_path = v2_path
        print(f"File was locked, saved successfully to: {v2_path}")

    return saved_path

if __name__ == "__main__":
    create_presentation()
