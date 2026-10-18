import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChangeType, DatasetChangeDTO, Page, ParserExecutionDTO, SourceFileDTO } from '../models/appTypes';

/** Historique des exécutions, en lecture seule (voir ParserExecutionController côté Java). */
@Injectable({ providedIn: 'root' })
export class ParserExecutionService {
  private apiUrl = '/api/executions';

  constructor(private http: HttpClient) {}

  getAll(page = 0, size = 20): Observable<Page<ParserExecutionDTO>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<ParserExecutionDTO>>(this.apiUrl, { params });
  }

  getByParser(parserId: number, page = 0, size = 20): Observable<Page<ParserExecutionDTO>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<ParserExecutionDTO>>(`${this.apiUrl}/parser/${parserId}`, { params });
  }

  getById(id: number): Observable<ParserExecutionDTO> {
    return this.http.get<ParserExecutionDTO>(`${this.apiUrl}/${id}`);
  }

  /** Fichiers sources signés et archivés par l'exécution. */
  getFiles(id: number): Observable<SourceFileDTO[]> {
    return this.http.get<SourceFileDTO[]>(`${this.apiUrl}/${id}/files`);
  }

  /** Changements publiés par l'exécution ; type absent = tous. */
  getChanges(id: number, type: ChangeType | null, page = 0, size = 50): Observable<Page<DatasetChangeDTO>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (type) {
      params = params.set('type', type);
    }
    return this.http.get<Page<DatasetChangeDTO>>(`${this.apiUrl}/${id}/changes`, { params });
  }
}
