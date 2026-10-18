import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PluginInfo } from '../models/appTypes';

/** Plugins PF4J (un JAR par liste) : lister, charger, recharger, décharger. */
@Injectable({ providedIn: 'root' })
export class PluginService {
  private apiUrl = '/api/ftm/plugins';

  constructor(private http: HttpClient) {}

  list(): Observable<PluginInfo[]> {
    return this.http.get<PluginInfo[]>(this.apiUrl);
  }

  /** Charge un JAR (chemin sur le serveur). Une version déjà chargée avec le même Plugin-Id est remplacée. */
  load(path: string): Observable<{ pluginId: string }> {
    const params = new HttpParams().set('path', path);
    return this.http.post<{ pluginId: string }>(`${this.apiUrl}/load`, null, { params });
  }

  reload(pluginId: string): Observable<{ pluginId: string }> {
    return this.http.post<{ pluginId: string }>(`${this.apiUrl}/${pluginId}/reload`, null);
  }

  unload(pluginId: string): Observable<{ pluginId: string }> {
    return this.http.post<{ pluginId: string }>(`${this.apiUrl}/${pluginId}/unload`, null);
  }
}
