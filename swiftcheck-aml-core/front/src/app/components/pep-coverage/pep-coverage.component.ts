import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { CategoryCoverage, CountryDTO, CoverageDTO, CoverageStatus, PepCategoryKind } from '../../models/appTypes';
import { PepReferenceService } from '../../services/pep-reference.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/** Libellé et couleur de chaque nature de catégorie. */
export const KINDS: Record<PepCategoryKind, { label: string; css: string }> = {
  OFFICIAL:   { label: 'Liste officielle', css: 'bg-slate-900 text-white' },
  AMLR:       { label: 'AMLR 2027',        css: 'bg-violet-100 text-violet-800' },
  RISK_BASED: { label: 'Approche risques', css: 'bg-sky-100 text-sky-800' },
  EU:         { label: 'Fonction UE',      css: 'bg-blue-100 text-blue-800' },
  RCA:        { label: 'Famille, proches', css: 'bg-amber-100 text-amber-800' },
};

/** Libellé et couleur de chaque état de couverture. */
const STATUSES: Record<CoverageStatus, { label: string; css: string }> = {
  COVERED: { label: 'Couverte',      css: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20' },
  PARTIAL: { label: 'Partielle',     css: 'bg-amber-50 text-amber-700 ring-amber-600/20' },
  NONE:    { label: 'Non couverte',  css: 'bg-rose-50 text-rose-700 ring-rose-600/20' },
};

/**
 * Page « Couverture PEP » (/coverage) : pour chaque catégorie de la liste officielle d'un pays, les fonctions,
 * les parsers qui les couvrent et le nombre de personnes publiées à leur dernière publication.
 * Une fonction est couverte si un parser actif qui la déclare a déjà publié.
 */
@Component({
  selector: 'app-pep-coverage',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, StatusBadgeComponent],
  templateUrl: './pep-coverage.component.html',
})
export class PepCoverageComponent implements OnInit {
  countries: CountryDTO[] = [];
  countryIso = 'LU';
  coverage?: CoverageDTO;
  isLoading = true;
  errorMessage = '';

  readonly kinds = KINDS;
  readonly statuses = STATUSES;

  constructor(private referenceService: PepReferenceService) {}

  ngOnInit(): void {
    this.referenceService.countries().subscribe({
      next: countries => (this.countries = countries),
      error: err => (this.errorMessage = apiError(err)),
    });
    this.load();
  }

  load(): void {
    this.isLoading = true;
    this.errorMessage = '';
    this.expanded = new Set();
    this.referenceService.coverage(this.countryIso).subscribe({
      next: coverage => {
        this.coverage = coverage;
        this.isLoading = false;
      },
      error: err => {
        this.coverage = undefined;
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  /** Rapport PDF détaillé en cours de génération (« all » = tous les pays, sinon le pays courant). */
  reportBusy: 'all' | 'country' | null = null;

  /** Télécharge le rapport PDF détaillé du pays affiché, ou de tous les pays. */
  downloadReport(all: boolean): void {
    this.reportBusy = all ? 'all' : 'country';
    this.errorMessage = '';
    this.referenceService.coverageReport(all ? undefined : this.countryIso).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `couverture_pep_${all ? 'tous_pays' : this.countryIso}.pdf`;
        link.click();
        URL.revokeObjectURL(url);
        this.reportBusy = null;
      },
      error: () => {
        this.errorMessage = 'Le rapport n\'a pas pu être généré (voir les journaux du serveur).';
        this.reportBusy = null;
      },
    });
  }

  /** Pourcentage de catégories officielles entièrement couvertes. */
  get officialPercent(): number {
    const c = this.coverage;
    return c && c.officialCategories ? Math.round((c.coveredOfficialCategories * 100) / c.officialCategories) : 0;
  }

  /** Fonctions dont le détail de couverture est déplié. */
  expanded = new Set<number>();

  toggleDetail(id: number): void {
    if (this.expanded.has(id)) {
      this.expanded.delete(id);
    } else {
      this.expanded.add(id);
    }
  }

  expandAll(): void {
    this.expanded = new Set(this.coverage?.categories.flatMap(c => c.functions.map(f => f.id)) ?? []);
  }

  collapseAll(): void {
    this.expanded.clear();
  }

  /** Résumé d'une catégorie : pourquoi elle est couverte, partielle ou non couverte. */
  categoryReason(category: CategoryCoverage): string {
    const total = category.functions.length;
    const count = (s: CoverageStatus) => category.functions.filter(f => f.status === s).length;
    const covered = count('COVERED');
    if (total === 0) {
      return 'Aucune fonction définie dans cette catégorie.';
    }
    if (covered === total) {
      return total === 1 ? 'Couverte à 100 % : sa fonction est couverte à 100 %.'
        : `Couverte à 100 % : ses ${total} fonctions sont couvertes à 100 %.`;
    }
    const parts = [`${covered} sur ${total} couverte${covered > 1 ? 's' : ''} à 100 %`];
    if (count('PARTIAL')) {
      parts.push(`${count('PARTIAL')} partielle${count('PARTIAL') > 1 ? 's' : ''}`);
    }
    if (count('NONE')) {
      parts.push(`${count('NONE')} non couverte${count('NONE') > 1 ? 's' : ''}`);
    }
    return `${category.status === 'NONE' ? 'Non couverte' : 'Partielle'} : ${parts.join(', ')}. `
      + 'Ouvrir le détail des fonctions pour voir ce qui manque.';
  }

  /** Nombre de fonctions couvertes / total, toutes catégories confondues. */
  get functionCounts(): { covered: number; total: number } {
    const functions = this.coverage?.categories.flatMap(c => c.functions) ?? [];
    return { covered: functions.filter(f => f.status === 'COVERED').length, total: functions.length };
  }
}
