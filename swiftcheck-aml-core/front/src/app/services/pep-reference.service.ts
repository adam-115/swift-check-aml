import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CountryDTO, CoverageDTO, PepFunctionDTO } from '../models/appTypes';

/** Référentiel PEP (pays, fonctions) et couverture par les parsers (voir PepReferenceController côté Java). */
@Injectable({ providedIn: 'root' })
export class PepReferenceService {
  private apiUrl = '/api/pep';

  constructor(private http: HttpClient) {}

  countries(): Observable<CountryDTO[]> {
    return this.http.get<CountryDTO[]>(`${this.apiUrl}/countries`);
  }

  /** Fonctions PEP d'un pays, ou de tous les pays sans paramètre. */
  functions(countryIso?: string): Observable<PepFunctionDTO[]> {
    const params = countryIso ? new HttpParams().set('country', countryIso) : new HttpParams();
    return this.http.get<PepFunctionDTO[]>(`${this.apiUrl}/functions`, { params });
  }

  coverage(countryIso: string): Observable<CoverageDTO> {
    return this.http.get<CoverageDTO>(`${this.apiUrl}/countries/${countryIso}/coverage`);
  }
}
