package com.swiftcheck.aml.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import lombok.Setter;

/** Une catégorie de fonctions PEP d'un pays, ex. {@code LU-b} « Parlementaires et Conseil d'État ». */
@Getter
@Setter
@Entity
public class PepCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    @JoinColumn(name = "country_iso")
    private Country country;

    /** Code unique : pays + lettre de la liste officielle, ex. {@code LU-b}, ou {@code LU-MUN}. */
    private String code;

    private String label;

    @Enumerated(EnumType.STRING)
    private PepCategoryKind kind;

    /** Classe de risque : 1 = la plus élevée, 4 = la plus faible. */
    private int riskClass;

    /** Ordre d'affichage dans la page « Couverture PEP ». */
    private int sortOrder;

    /** Texte qui définit la catégorie, ex. « Directive (UE) 2015/849, art. 3, point 9 b) ». */
    private String legalReference;

    /** Fonctions de la catégorie. */
    @JsonIgnore
    @OneToMany(mappedBy = "category")
    @OrderBy("id")
    private List<PepFunction> functions = new ArrayList<>();
}
