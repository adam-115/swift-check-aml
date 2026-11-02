import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

/** Photo affichée : origine, dataset qui l'a publiée (jamais partagée entre datasets), mention et page source. */
export interface PepPhoto {
  id: number;
  origin: 'OFFICIAL' | 'WEB_VALIDATED';
  dataset: string;
  licence: string | null;
  pageUrl: string | null;
  validatedBy: string | null;
  fetchedAt: string;
}

/** Image trouvée sur internet : non vérifiée, avec son score de contexte. */
export interface PhotoCandidate {
  imageUrl: string;
  thumbnailUrl: string;
  pageUrl: string;
  title: string;
  domain: string;
  engine: string;
  score: number;
  reasons: string[];
}

@Injectable({
  providedIn: 'root'
})
export class PepMediaService {
  private apiUrl = '/api/pep-media';

  constructor(private http: HttpClient) {}

  /** Photos de l'entité, publiées par son dataset : la principale (l'officielle) en premier ; liste vide sinon. */
  lookup(entityId: string): Observable<PepPhoto[]> {
    return this.http.get<PepPhoto[]>(`${this.apiUrl}/lookup`, { params: { entityId } })
      .pipe(map(photos => photos || []));
  }

  /** Fichier de la photo, servi par SwiftCheck (requête authentifiée, d'où le blob). */
  file(id: number): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/${id}/file`, { responseType: 'blob' });
  }

  webSearch(name: string, functions: string[]): Observable<PhotoCandidate[]> {
    return this.http.post<PhotoCandidate[]>(`${this.apiUrl}/web-search`, { name, functions });
  }

  /** Vignette d'un candidat, relayée par SwiftCheck : le navigateur n'appelle pas le site de l'image. */
  thumbnail(url: string): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/thumbnail`, { params: { url }, responseType: 'blob' });
  }

  /** Enregistre la photo retenue pour l'entité, dans le dataset de la correspondance. */
  validate(entityId: string, dataset: string, imageUrl: string, pageUrl: string): Observable<PepPhoto> {
    return this.http.post<PepPhoto>(`${this.apiUrl}/validate`, { entityId, dataset, imageUrl, pageUrl });
  }
}
