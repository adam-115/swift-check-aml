import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { CountryDTO, CoverageDTO, CoverageStatus, PepCategoryKind } from '../../models/appTypes';
import { PepReferenceService } from '../../services/pep-reference.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/** Libellé et couleur de chaque nature de catégorie. */
const KINDS: Record<PepCategoryKind, { label: string; css: string }> = {
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

  /** Pourcentage de catégories officielles entièrement couvertes. */
  get officialPercent(): number {
    const c = this.coverage;
    return c && c.officialCategories ? Math.round((c.coveredOfficialCategories * 100) / c.officialCategories) : 0;
  }

  /** Nombre de fonctions couvertes / total, toutes catégories confondues. */
  get functionCounts(): { covered: number; total: number } {
    const functions = this.coverage?.categories.flatMap(c => c.functions) ?? [];
    return { covered: functions.filter(f => f.status === 'COVERED').length, total: functions.length };
  }
}
