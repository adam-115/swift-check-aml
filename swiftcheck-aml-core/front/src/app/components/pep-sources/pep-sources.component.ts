import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import {
  Completeness, CountryDTO, PepFunctionDTO, PepSourceDTO, PepSourceFunctionLink, PepSourceStatus, SourceAccess,
} from '../../models/appTypes';
import { PepReferenceService } from '../../services/pep-reference.service';
import { KeycloakService } from '../../services/keycloak.service';
import { apiError } from '../../services/api-error';
import { PARSER_KINDS } from '../parser/parser.component';

export const ACCESS_LABELS: Record<SourceAccess, string> = {
  OPEN: 'Libre',
  RESTRICTED: 'Restreint',
  PAID: 'Payant',
};

/** Fonctions d'une catégorie, pour les cases à cocher du formulaire. */
interface FunctionGroup {
  code: string;
  label: string;
  functions: PepFunctionDTO[];
}

/**
 * Sources PEP (/sources) : où récupérer les titulaires de chaque fonction. Une source sans parser est une
 * piste de travail ; elle devient « implémentée » quand un parser la lit (formulaire du parser, champ Source).
 * Lecture pour tous, modification par l'administrateur.
 */
@Component({
  selector: 'app-pep-sources',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pep-sources.component.html',
})
export class PepSourcesComponent implements OnInit {
  countries: CountryDTO[] = [];
  countryIso = 'LU';
  sources: PepSourceDTO[] = [];
  functions: PepFunctionDTO[] = [];
  isLoading = true;
  errorMessage = '';

  // Formulaire (panneau latéral)
  draft?: PepSourceDTO;
  /** Fonctions cochées dans le formulaire, par identifiant. */
  links = new Map<number, PepSourceFunctionLink>();
  formError = '';
  isSaving = false;

  readonly parserKinds = PARSER_KINDS;
  readonly accessLabels = ACCESS_LABELS;
  readonly accesses: SourceAccess[] = ['OPEN', 'RESTRICTED', 'PAID'];

  constructor(private referenceService: PepReferenceService, public keycloak: KeycloakService) {}

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
    this.referenceService.functions(this.countryIso).subscribe({ next: functions => (this.functions = functions) });
    this.referenceService.sources(this.countryIso).subscribe({
      next: sources => {
        this.sources = sources;
        this.isLoading = false;
      },
      error: err => {
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  /** Sources lues par au moins un parser. */
  get implementedCount(): number {
    return this.sources.filter(s => s.parsers?.length).length;
  }

  kindLabel(kind?: string): string {
    return PARSER_KINDS.find(k => k.value === kind)?.label ?? '—';
  }

  // ---------------------------------------------------------------- formulaire

  openForm(source?: PepSourceDTO): void {
    this.formError = '';
    this.draft = source
      ? { ...source }
      : { countryIso: this.countryIso, name: '', access: 'OPEN', status: 'CANDIDATE', functions: [] };
    this.links = new Map((source?.functions ?? []).map(l => [l.functionId, { ...l }]));
  }

  closeForm(): void {
    this.draft = undefined;
  }

  /** Fonctions du pays de la source, rangées par catégorie. */
  get functionGroups(): FunctionGroup[] {
    const groups = new Map<string, FunctionGroup>();
    for (const f of this.functions) {
      const group = groups.get(f.categoryCode) ?? { code: f.categoryCode, label: f.categoryLabel, functions: [] };
      group.functions.push(f);
      groups.set(f.categoryCode, group);
    }
    return [...groups.values()];
  }

  toggleFunction(id: number): void {
    if (this.links.has(id)) {
      this.links.delete(id);
    } else {
      this.links.set(id, { functionId: id, completeness: 'COMPLETE', includesFormer: false });
    }
  }

  setCompleteness(id: number, completeness: Completeness): void {
    const link = this.links.get(id);
    if (link) {
      link.completeness = completeness;
    }
  }

  setIncludesFormer(id: number, value: boolean): void {
    const link = this.links.get(id);
    if (link) {
      link.includesFormer = value;
    }
  }

  setStatus(status: PepSourceStatus): void {
    if (this.draft) {
      this.draft.status = status;
    }
  }

  save(): void {
    if (!this.draft) {
      return;
    }
    if (!this.draft.name?.trim()) {
      this.formError = 'Nom de la source obligatoire.';
      return;
    }
    this.isSaving = true;
    this.formError = '';
    this.referenceService.saveSource({ ...this.draft, functions: [...this.links.values()] }).subscribe({
      next: () => {
        this.isSaving = false;
        this.draft = undefined;
        this.load();
      },
      error: err => {
        this.isSaving = false;
        this.formError = apiError(err);
      },
    });
  }

  remove(source: PepSourceDTO): void {
    if (!source.id || !confirm(`Supprimer la source « ${source.name} » et ses liens avec les fonctions ?`)) {
      return;
    }
    this.referenceService.deleteSource(source.id).subscribe({
      next: () => this.load(),
      error: err => (this.errorMessage = apiError(err)),
    });
  }
}
