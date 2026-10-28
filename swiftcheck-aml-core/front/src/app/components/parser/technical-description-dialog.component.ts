import { Component, EventEmitter, HostListener, Input, OnInit, Output, ViewEncapsulation } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Marked } from 'marked';

import { ParserDTO } from '../../models/appTypes';
import { ParserService } from '../../services/parser.service';
import { apiError } from '../../services/api-error';

/**
 * Rendu Markdown des descriptions. Les liens absolus s'ouvrent dans un nouvel onglet ; les liens relatifs des
 * README (« ../../luxembourg/… ») ne mènent nulle part dans l'application : seul leur texte est gardé.
 * Le HTML produit passe ensuite par le nettoyage d'Angular ([innerHTML]).
 */
const markdown = new Marked({
  gfm: true,
  renderer: {
    link(href: string, title: string | null | undefined, text: string): string {
      if (!/^(https?:|mailto:)/i.test(href ?? '')) {
        return text;
      }
      const t = title ? ` title="${title}"` : '';
      return `<a href="${href}"${t} target="_blank" rel="noopener noreferrer">${text}</a>`;
    },
  },
});

/**
 * Fenêtre « Description technique » d'un parser : ce que fait le parser exactement, en Markdown (en général le
 * README du plugin, complété au besoin). Lecture, puis modification avec aperçu ; enregistrée en base, sans
 * recompiler ni redéployer le plugin.
 */
@Component({
  selector: 'app-technical-description-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './technical-description-dialog.component.html',
  encapsulation: ViewEncapsulation.None,
  styles: [`
    .md-doc { color: rgb(51 65 85); font-size: 0.875rem; line-height: 1.6; }
    .md-doc > :first-child { margin-top: 0; }
    .md-doc h1 { font-size: 1.35rem; font-weight: 700; color: rgb(15 23 42); margin: 0 0 0.75rem; line-height: 1.3; }
    .md-doc h2 { font-size: 1.05rem; font-weight: 700; color: rgb(15 23 42); margin: 1.5rem 0 0.5rem;
                 padding-bottom: 0.25rem; border-bottom: 1px solid rgb(226 232 240); }
    .md-doc h3 { font-size: 0.95rem; font-weight: 600; color: rgb(15 23 42); margin: 1.1rem 0 0.4rem; }
    .md-doc p { margin: 0.5rem 0; }
    .md-doc ul, .md-doc ol { margin: 0.5rem 0; padding-left: 1.4rem; }
    .md-doc ul { list-style: disc; }
    .md-doc ol { list-style: decimal; }
    .md-doc li { margin: 0.15rem 0; }
    .md-doc a { color: rgb(3 105 161); text-decoration: underline; overflow-wrap: anywhere; }
    .md-doc strong { color: rgb(15 23 42); font-weight: 600; }
    .md-doc code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.8em;
                   background: rgb(241 245 249); color: rgb(15 23 42); padding: 0.1rem 0.3rem; border-radius: 0.25rem;
                   overflow-wrap: anywhere; }
    .md-doc pre { background: rgb(15 23 42); color: rgb(226 232 240); padding: 0.75rem 1rem; border-radius: 0.5rem;
                  overflow-x: auto; margin: 0.75rem 0; font-size: 0.8rem; }
    .md-doc pre code { background: none; color: inherit; padding: 0; overflow-wrap: normal; }
    .md-doc blockquote { border-left: 3px solid rgb(203 213 225); padding-left: 0.75rem; color: rgb(71 85 105); margin: 0.75rem 0; }
    .md-doc hr { border: 0; border-top: 1px solid rgb(226 232 240); margin: 1.25rem 0; }
    .md-doc .md-table { overflow-x: auto; margin: 0.75rem 0; }
    .md-doc table { border-collapse: collapse; width: 100%; font-size: 0.8125rem; }
    .md-doc th, .md-doc td { border: 1px solid rgb(226 232 240); padding: 0.4rem 0.6rem; text-align: left; vertical-align: top; }
    .md-doc th { background: rgb(248 250 252); font-weight: 600; color: rgb(15 23 42); }
    .md-doc th:empty { display: none; }
  `],
})
export class TechnicalDescriptionDialogComponent implements OnInit {
  @Input({ required: true }) parser!: ParserDTO;
  /** Le texte a été enregistré : la liste peut mettre à jour l'indicateur « description renseignée ». */
  @Output() saved = new EventEmitter<boolean>();
  @Output() closed = new EventEmitter<void>();

  text = '';
  updatedAt?: string;
  draft = '';
  editing = false;
  /** Petit écran : édition ou aperçu (sur grand écran les deux sont côte à côte). */
  mobileTab: 'edit' | 'preview' = 'edit';
  isLoading = true;
  isSaving = false;
  errorMessage = '';

  constructor(private parserService: ParserService) {}

  ngOnInit(): void {
    this.parserService.technicalDescription(this.parser.id!).subscribe({
      next: d => {
        this.text = d.markdown;
        this.updatedAt = d.updatedAt;
        this.isLoading = false;
      },
      error: err => {
        this.errorMessage = apiError(err);
        this.isLoading = false;
      },
    });
  }

  get dirty(): boolean {
    return this.editing && this.draft !== this.text;
  }

  /** HTML du Markdown ; le dernier rendu est gardé, le modèle étant réévalué à chaque détection de changements. */
  render(source: string): string {
    if (source !== this.rendered.source) {
      const html = (markdown.parse(source, { async: false }) as string)
        .replace(/<table>/g, '<div class="md-table"><table>')
        .replace(/<\/table>/g, '</table></div>');
      this.rendered = { source, html };
    }
    return this.rendered.html;
  }

  private rendered = { source: '', html: '' };

  edit(): void {
    this.draft = this.text;
    this.mobileTab = 'edit';
    this.errorMessage = '';
    this.editing = true;
  }

  cancelEdit(): void {
    if (!this.dirty || confirm('Abandonner les modifications ?')) {
      this.editing = false;
      this.errorMessage = '';
    }
  }

  save(): void {
    this.isSaving = true;
    this.errorMessage = '';
    this.parserService.saveTechnicalDescription(this.parser.id!, this.draft).subscribe({
      next: d => {
        this.text = d.markdown;
        this.updatedAt = d.updatedAt;
        this.isSaving = false;
        this.editing = false;
        this.saved.emit(!!d.markdown);
      },
      error: err => {
        this.errorMessage = apiError(err);
        this.isSaving = false;
      },
    });
  }

  /** Échap : quitte l'édition (avec confirmation si le texte a changé), sinon ferme la fenêtre. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.editing) {
      this.cancelEdit();
    } else {
      this.close();
    }
  }

  close(): void {
    if (!this.dirty || confirm('Abandonner les modifications ?')) {
      this.closed.emit();
    }
  }

  /** Tab dans la zone de texte insère deux espaces au lieu de quitter le champ (listes imbriquées). */
  onTab(event: Event, area: HTMLTextAreaElement): void {
    event.preventDefault();
    const start = area.selectionStart;
    this.draft = this.draft.slice(0, start) + '  ' + this.draft.slice(area.selectionEnd);
    setTimeout(() => area.setSelectionRange(start + 2, start + 2));
  }
}
