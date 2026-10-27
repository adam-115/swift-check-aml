import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { CountryDTO, PepCategoryDTO, PepCategoryKind } from '../../models/appTypes';
import { PepReferenceService } from '../../services/pep-reference.service';
import { apiError } from '../../services/api-error';
import { KINDS } from '../pep-coverage/pep-coverage.component';

/**
 * Page « Catégories PEP » (/pep-categories, administrateurs) : catégories de fonctions PEP d'un pays.
 * L'ajout et la modification se font dans une boîte de dialogue (fermée par Échap ou un clic à côté).
 * Création, modification, activation / désactivation et suppression (seulement si la catégorie n'a plus de
 * fonction). Une catégorie désactivée garde ses fonctions mais disparaît de la page « Couverture PEP ».
 */
@Component({
  selector: 'app-pep-categories',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pep-categories.component.html',
})
export class PepCategoriesComponent implements OnInit {
  countries: CountryDTO[] = [];
  countryIso = 'LU';
  categories: PepCategoryDTO[] = [];
  isLoading = true;
  errorMessage = '';

  /** Catégorie en cours de saisie dans le dialogue ; {@code undefined} = dialogue fermé. */
  editing?: PepCategoryDTO;
  formError = '';
  isSaving = false;

  readonly kinds = KINDS;
  readonly kindValues = Object.keys(KINDS) as PepCategoryKind[];
  readonly riskClasses = [1, 2, 3, 4];

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
    this.referenceService.categories(this.countryIso).subscribe({
      next: categories => {
        this.categories = categories;
        this.isLoading = false;
      },
      error: err => {
        this.categories = [];
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  get activeCount(): number {
    return this.categories.filter(c => c.active).length;
  }

  openNew(): void {
    // Proposé à la suite des catégories existantes
    const nextOrder = Math.max(0, ...this.categories.map(c => c.sortOrder)) + 10;
    this.open({
      id: 0, countryIso: this.countryIso, code: `${this.countryIso}-`, label: '', kind: 'OFFICIAL', riskClass: 1,
      sortOrder: nextOrder, legalReference: '', active: true, functionCount: 0,
    });
  }

  openEdit(category: PepCategoryDTO): void {
    this.open({ ...category });
  }

  private open(category: PepCategoryDTO): void {
    this.editing = category;
    this.formError = '';
  }

  @HostListener('document:keydown.escape')
  closeForm(): void {
    if (!this.isSaving) {
      this.editing = undefined;
    }
  }

  save(): void {
    const c = this.editing;
    if (!c) {
      return;
    }
    if (!c.code?.trim() || !c.label?.trim()) {
      this.formError = 'Le code et le libellé sont obligatoires.';
      return;
    }
    this.isSaving = true;
    this.formError = '';
    this.referenceService.saveCategory({ ...c, countryIso: this.countryIso }).subscribe({
      next: () => {
        this.isSaving = false;
        this.editing = undefined;
        this.load();
      },
      error: err => {
        this.isSaving = false;
        this.formError = apiError(err);
      },
    });
  }

  toggleActive(category: PepCategoryDTO): void {
    const active = !category.active;
    if (!active && !confirm(`Désactiver « ${category.code} » ? Elle n'apparaîtra plus dans la page Couverture PEP.\n`
        + 'Ses fonctions et les liens avec les parsers sont conservés.')) {
      return;
    }
    this.errorMessage = '';
    this.referenceService.setCategoryActive(category.id, active).subscribe({
      next: updated => (category.active = updated.active),
      error: err => (this.errorMessage = apiError(err)),
    });
  }

  remove(category: PepCategoryDTO): void {
    if (!confirm(`Supprimer définitivement la catégorie « ${category.code} — ${category.label} » ?`)) {
      return;
    }
    this.errorMessage = '';
    this.referenceService.deleteCategory(category.id).subscribe({
      next: () => this.load(),
      error: err => (this.errorMessage = apiError(err)),
    });
  }

  trackCategory(_: number, category: PepCategoryDTO): number {
    return category.id;
  }
}
