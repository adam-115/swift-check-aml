import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Page, ParserDTO, ParserExecutionDTO, ParserTestDTO, ParserTestPreviewDTO, PluginCheck, RetainedEntityDTO,
  TechnicalDescriptionDTO,
} from '../models/appTypes';

/** Appels HTTP de l'écran « Parsers » (voir ParserController côté Java). */
@Injectable({ providedIn: 'root' })
export class ParserService {
  private apiUrl = '/api/parsers';

  constructor(private http: HttpClient) {}

  getAll(page = 0, size = 20): Observable<Page<ParserDTO>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<ParserDTO>>(this.apiUrl, { params });
  }

  /** Tous les parsers, sans pagination (sélecteur de listes de l'écran de vérification). */
  getAllForProviders(): Observable<Page<ParserDTO>> {
    return this.getAll(0, 1000);
  }

  getById(id: number): Observable<ParserDTO> {
    return this.http.get<ParserDTO>(`${this.apiUrl}/${id}`);
  }

  /** Création si pas d'id, sinon modification. */
  save(parser: ParserDTO): Observable<ParserDTO> {
    return parser.id
      ? this.http.put<ParserDTO>(`${this.apiUrl}/${parser.id}`, parser)
      : this.http.post<ParserDTO>(this.apiUrl, parser);
  }

  /** Désactive le parser et retire son dataset de Yente (l'historique est conservé). */
  disable(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  /** Description technique (Markdown) ; texte vide si le parser n'en a pas. */
  technicalDescription(id: number): Observable<TechnicalDescriptionDTO> {
    return this.http.get<TechnicalDescriptionDTO>(`${this.apiUrl}/${id}/technical-description`);
  }

  /** Remplace la description technique ; un texte vide l'efface. */
  saveTechnicalDescription(id: number, markdown: string): Observable<TechnicalDescriptionDTO> {
    return this.http.put<TechnicalDescriptionDTO>(`${this.apiUrl}/${id}/technical-description`, { markdown });
  }

  /** Entités disparues de la source mais encore publiées grâce à la rétention. */
  retained(id: number): Observable<RetainedEntityDTO[]> {
    return this.http.get<RetainedEntityDTO[]>(`${this.apiUrl}/${id}/retained`);
  }

  /** Ce que le test va vérifier (publication et fichier source), affiché avant confirmation. */
  testPreview(id: number): Observable<ParserTestPreviewDTO> {
    return this.http.get<ParserTestPreviewDTO>(`${this.apiUrl}/${id}/test-preview`);
  }

  /** Test : chaque élément du fichier source de la dernière publication est cherché dans Yente. */
  test(id: number): Observable<ParserTestDTO> {
    return this.http.post<ParserTestDTO>(`${this.apiUrl}/${id}/test`, null);
  }

  /** Bouton « Vérifier » : PF4J charge le JAR (chemin sur le serveur) et y cherche le parser. */
  verifyPlugin(path: string): Observable<PluginCheck> {
    const params = new HttpParams().set('path', path);
    return this.http.post<PluginCheck>(`${this.apiUrl}/verify-plugin`, null, { params });
  }

  /**
   * Lance le parser et attend la fin de l'exécution.
   * force = true : convertit et publie même si la source n'a pas changé (après une mise à jour du plugin).
   */
  run(id: number, force = false): Observable<ParserExecutionDTO> {
    const params = new HttpParams().set('force', force);
    return this.http.post<ParserExecutionDTO>(`${this.apiUrl}/${id}/run`, null, { params });
  }
}
