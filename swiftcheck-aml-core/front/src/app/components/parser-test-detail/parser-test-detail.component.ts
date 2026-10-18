import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ParserTestDTO, ParserTestFailureDTO, TestFailureReason } from '../../models/appTypes';
import { ParserTestService } from '../../services/parser-test.service';
import { apiError } from '../../services/api-error';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { TestResultBarComponent } from '../shared/test-result-bar.component';
import { fileSize, sourceFileName } from '../shared/format';

/** Libellé, explication et couleur de chaque raison d'échec : elle dit où chercher le problème. */
export const TEST_REASONS: Record<TestFailureReason, { label: string; help: string; css: string }> = {
  NOT_CONVERTED: { label: 'Non converti', help: 'Dans la source, absent du fichier publié : à corriger dans le plugin.', css: 'text-rose-700 bg-rose-50' },
  NOT_INDEXED:   { label: 'Non indexé', help: 'Publié, mais inconnu de Yente : vérifier l\'indexation (manifest, /updatez).', css: 'text-amber-800 bg-amber-50' },
  NOT_MATCHED:   { label: 'Non retrouvé par le nom', help: 'Indexé, mais une recherche par son nom ne le renvoie pas.', css: 'text-sky-800 bg-sky-50' },
  YENTE_ERROR:   { label: 'Erreur Yente', help: 'Yente n\'a pas répondu : élément non vérifié.', css: 'text-slate-700 bg-slate-100' },
};

/**
 * Détail d'un test (/tests/:id) : compteurs par résultat et liste des éléments de la source non retrouvés
 * dans Yente, filtrable par raison.
 */
@Component({
  selector: 'app-parser-test-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent, TestResultBarComponent],
  templateUrl: './parser-test-detail.component.html',
})
export class ParserTestDetailComponent implements OnInit {
  readonly reasons = TEST_REASONS;
  readonly reasonKeys = Object.keys(TEST_REASONS) as TestFailureReason[];
  readonly fileName = sourceFileName;
  readonly fileSize = fileSize;

  test?: ParserTestDTO;
  errorMessage = '';

  filter: TestFailureReason | null = null;
  failures: ParserTestFailureDTO[] = [];
  page = 0;
  totalPages = 0;
  total = 0;

  constructor(private route: ActivatedRoute, private testService: ParserTestService) {}

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.testService.getById(id).subscribe({
      next: test => (this.test = test),
      error: err => (this.errorMessage = apiError(err)),
    });
    this.loadFailures(id);
  }

  /** Nombre d'éléments pour une raison, affiché sur la carte et le filtre. */
  count(reason: TestFailureReason): number {
    const t = this.test;
    if (!t) {
      return 0;
    }
    return { NOT_CONVERTED: t.notConvertedCount, NOT_INDEXED: t.notIndexedCount,
      NOT_MATCHED: t.notMatchedCount, YENTE_ERROR: t.errorCount }[reason];
  }

  setFilter(reason: TestFailureReason | null): void {
    this.filter = reason;
    this.page = 0;
    this.loadFailures(this.test!.id);
  }

  changePage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.page = page;
      this.loadFailures(this.test!.id);
    }
  }

  private loadFailures(id: number): void {
    this.testService.getFailures(id, this.filter, this.page).subscribe({
      next: page => {
        this.failures = page.content;
        this.totalPages = page.totalPages;
        this.total = page.totalElements;
      },
    });
  }
}
