import { Component, EventEmitter, HostListener, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

import { Completeness, ParserDTO, PepFunctionDTO } from '../../models/appTypes';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/** Une fonction couverte, avec son libellé lu dans le référentiel. */
interface CoverageRow {
  label: string;
  institution?: string;
  countryIso?: string;
  completeness: Completeness;
  includesFormer: boolean;
}

interface CoverageGroup {
  code: string;
  label: string;
  rows: CoverageRow[];
}

/**
 * Fenêtre « Afficher » : toutes les informations d'un parser en lecture (identité, plugin et source,
 * paramètres, planification, dernière exécution, dernier test, fonctions PEP couvertes).
 */
@Component({
  selector: 'app-parser-detail-dialog',
  standalone: true,
  imports: [CommonModule, RouterLink, StatusBadgeComponent],
  templateUrl: './parser-detail-dialog.component.html',
})
export class ParserDetailDialogComponent implements OnChanges {
  @Input({ required: true }) parser!: ParserDTO;
  /** Référentiel des fonctions PEP, pour afficher les libellés de la couverture. */
  @Input() functions: PepFunctionDTO[] = [];
  /** Libellés calculés par l'écran Parsers (type de source, nature, type de liste). */
  @Input() sourceLabel = '';
  @Input() kindLabel = '';
  @Input() listTypeLabel = '';
  /** Faux quand une autre fenêtre est ouverte par-dessus : Échap ne ferme alors que celle-ci. */
  @Input() escapeEnabled = true;
  @Output() closed = new EventEmitter<void>();
  @Output() edit = new EventEmitter<void>();
  @Output() showDescription = new EventEmitter<void>();

  coverageGroups: CoverageGroup[] = [];
  parametersText = '';

  ngOnChanges(): void {
    const byId = new Map(this.functions.map(f => [f.id, f]));
    const groups = new Map<string, CoverageGroup>();
    for (const c of this.parser.coverage ?? []) {
      const f = byId.get(c.functionId);
      const code = f?.categoryCode ?? '?';
      const group = groups.get(code) ?? { code, label: f?.categoryLabel ?? 'Fonction inconnue', rows: [] };
      group.rows.push({
        label: f?.label ?? `Fonction n° ${c.functionId}`,
        institution: f?.institution,
        countryIso: f?.countryIso,
        completeness: c.completeness,
        includesFormer: c.includesFormer,
      });
      groups.set(code, group);
    }
    this.coverageGroups = [...groups.values()].sort((a, b) => a.code.localeCompare(b.code));
    this.parametersText = prettyJson(this.parser.parameters);
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.escapeEnabled) {
      this.closed.emit();
    }
  }
}

/** JSON indenté pour la lecture ; le texte tel quel s'il n'est pas du JSON valide. */
function prettyJson(text?: string): string {
  if (!text?.trim()) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
