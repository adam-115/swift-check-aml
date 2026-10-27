import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CountryDTO, CoverageDTO, PepCategoryDTO, PepFunctionDTO } from '../models/appTypes';

/** Référentiel PEP (pays, catégories, fonctions) et couverture par les parsers (voir PepReferenceController côté Java). */
@Injectable({ providedIn: 'root' })
export class PepReferenceService {
  private apiUrl = '/api/pep';

  constructor(private http: HttpClient) {}

  countries(): Observable<CountryDTO[]> {
    return this.http.get<CountryDTO[]>(`${this.apiUrl}/countries`);
  }

  /** Catégories d'un pays, actives et désactivées, dans l'ordre d'affichage. */
  categories(countryIso: string): Observable<PepCategoryDTO[]> {
    return this.http.get<PepCategoryDTO[]>(`${this.apiUrl}/countries/${countryIso}/categories`);
  }

  /** Crée la catégorie si {@code id} vaut 0, sinon la modifie. Réservé aux administrateurs. */
  saveCategory(category: PepCategoryDTO): Observable<PepCategoryDTO> {
    return category.id
      ? this.http.put<PepCategoryDTO>(`${this.apiUrl}/categories/${category.id}`, category)
      : this.http.post<PepCategoryDTO>(`${this.apiUrl}/categories`, category);
  }

  setCategoryActive(id: number, active: boolean): Observable<PepCategoryDTO> {
    const params = new HttpParams().set('value', active);
    return this.http.put<PepCategoryDTO>(`${this.apiUrl}/categories/${id}/active`, null, { params });
  }

  /** Refusé par le serveur si la catégorie a encore des fonctions. */
  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/categories/${id}`);
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
