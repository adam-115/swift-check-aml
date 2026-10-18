package com.swiftcheck.aml.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Élément XML simplifié (nom, attributs, texte, enfants), facile à parcourir dans un parser.
 *
 * <p>Toutes les méthodes sont « sans null » pour éviter les {@code NullPointerException} :
 * <ul>
 *   <li>{@link #attribute(String)} et {@link #text()} renvoient {@code null} si la valeur est absente ou vide
 *       (la bibliothèque commune ignore les {@code null}) ;</li>
 *   <li>{@link #child(String)} renvoie un élément vide si l'enfant n'existe pas, on peut donc enchaîner
 *       {@code fiche.child("regulation").attribute("programme")} sans vérification.</li>
 * </ul>
 * Les noms sont comparés sans espace de noms (namespace) : {@code <ns0:nameAlias>} se lit {@code "nameAlias"}.
 */
public final class XmlElement {

    /** Élément vide renvoyé quand un enfant demandé n'existe pas. */
    public static final XmlElement EMPTY = new XmlElement("");

    private final String name;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<XmlElement> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    XmlElement(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** Valeur de l'attribut, sans espaces autour, ou {@code null} si absent ou vide. */
    public String attribute(String attribute) {
        return blankToNull(attributes.get(attribute));
    }

    /** Texte contenu dans l'élément, sans espaces autour, ou {@code null} s'il est vide. */
    public String text() {
        return blankToNull(text.toString());
    }

    /** Premier enfant portant ce nom, ou {@link #EMPTY} s'il n'existe pas. */
    public XmlElement child(String childName) {
        for (XmlElement c : children) {
            if (c.name.equals(childName)) {
                return c;
            }
        }
        return EMPTY;
    }

    /** Texte du premier enfant portant ce nom (raccourci pour {@code child(nom).text()}). */
    public String childText(String childName) {
        return child(childName).text();
    }

    /** Tous les enfants portant ce nom (liste vide s'il n'y en a pas). */
    public List<XmlElement> children(String childName) {
        List<XmlElement> result = new ArrayList<>();
        for (XmlElement c : children) {
            if (c.name.equals(childName)) {
                result.add(c);
            }
        }
        return result;
    }

    /** Tous les enfants directs, dans l'ordre du fichier. */
    public List<XmlElement> children() {
        return Collections.unmodifiableList(children);
    }

    public boolean isEmpty() {
        return this == EMPTY;
    }

    // ---- utilisé uniquement par XmlRecordReader pendant la lecture ----

    void putAttribute(String key, String value) {
        attributes.put(key, value);
    }

    void addChild(XmlElement child) {
        children.add(child);
    }

    void appendText(String value) {
        text.append(value);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public String toString() {
        return "<" + name + " " + attributes + ">";
    }
}
