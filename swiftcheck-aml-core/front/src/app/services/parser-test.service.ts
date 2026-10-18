import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page, ParserTestDTO, ParserTestFailureDTO, TestFailureReason } from '../models/appTypes';

/** Historique et détail des tests de parser (voir ParserTestController côté Java). Lancement : ParserService.test(). */
@Injectable({ providedIn: 'root' })
export class ParserTestService {
  private apiUrl = '/api/parser-tests';

  constructor(private http: HttpClient) {}

  getAll(page = 0, parserId?: number): Observable<Page<ParserTestDTO>> {
    let params = new HttpParams().set('page', page).set('size', 20);
    if (parserId) {
      params = params.set('parserId', parserId);
    }
    return this.http.get<Page<ParserTestDTO>>(this.apiUrl, { params });
  }

  getById(id: number): Observable<ParserTestDTO> {
    return this.http.get<ParserTestDTO>(`${this.apiUrl}/${id}`);
  }

  /** Éléments de la source non retrouvés dans Yente, filtrables par raison. */
  getFailures(id: number, reason: TestFailureReason | null, page = 0): Observable<Page<ParserTestFailureDTO>> {
    let params = new HttpParams().set('page', page).set('size', 50);
    if (reason) {
      params = params.set('reason', reason);
    }
    return this.http.get<Page<ParserTestFailureDTO>>(`${this.apiUrl}/${id}/failures`, { params });
  }
}
