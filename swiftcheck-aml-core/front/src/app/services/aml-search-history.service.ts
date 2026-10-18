import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AmlSearchHistoryDTO {
  id: number;
  searchDate: string;
  schema: string;
  dataset: string;
  searchedProperties: string;
  matchesCount: number;
  resultsJson: string;
}

@Injectable({
  providedIn: 'root'
})
export class AmlSearchHistoryService {
  private apiUrl = '/api/history';

  constructor(private http: HttpClient) { }

  getAllHistory(): Observable<AmlSearchHistoryDTO[]> {
    return this.http.get<AmlSearchHistoryDTO[]>(this.apiUrl);
  }
}
