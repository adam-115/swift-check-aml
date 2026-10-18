import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ListProviderDTO, Page } from '../models/appTypes';

@Injectable({
  providedIn: 'root'
})
export class ListProviderService {
  private apiUrl = '/api/providers';

  constructor(private http: HttpClient) {}

  getAll(page: number = 0, size: number = 10): Observable<Page<ListProviderDTO>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<Page<ListProviderDTO>>(this.apiUrl, { params });
  }

  getById(id: number): Observable<ListProviderDTO> {
    return this.http.get<ListProviderDTO>(`${this.apiUrl}/${id}`);
  }

  /** Création si pas d'id, sinon modification. */
  save(item: ListProviderDTO): Observable<ListProviderDTO> {
    return item.id
      ? this.http.put<ListProviderDTO>(`${this.apiUrl}/${item.id}`, item)
      : this.http.post<ListProviderDTO>(this.apiUrl, item);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
