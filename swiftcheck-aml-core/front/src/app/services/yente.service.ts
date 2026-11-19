import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { YenteEventDTO, YenteStatusDTO } from '../models/appTypes';

/** Actions sur Yente et écran de monitoring (voir YenteController côté Java). */
@Injectable({ providedIn: 'root' })
export class YenteService {
  constructor(private http: HttpClient) {}

  /** État de Yente et de chaque dataset (écran « Yente »). */
  status(): Observable<YenteStatusDTO> {
    return this.http.get<YenteStatusDTO>('/api/yente/status');
  }

  /** Historique d'indexation d'un dataset, du plus récent au plus ancien. */
  events(name: string, limit = 50): Observable<YenteEventDTO[]> {
    return this.http.get<YenteEventDTO[]>(`/api/yente/datasets/${encodeURIComponent(name)}/events`, { params: { limit } });
  }

  /** « Tout reconstruire » : Yente reconstruit tous les index (forcé, long), en tâche de fond. Administrateur. */
  reindex(): Observable<{ message: string }> {
    return this.http.post<{ message: string }>('/api/yente/reindex', null);
  }

  /** Yente réindexe seulement ce dataset, à son prochain passage (2 minutes au plus). */
  reindexDataset(name: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`/api/yente/datasets/${encodeURIComponent(name)}/reindex`, null);
  }
}
