import { Injectable } from '@angular/core';
import Keycloak from 'keycloak-js';

@Injectable({
  providedIn: 'root'
})
export class KeycloakService {
  private keycloakInstance?: Keycloak;
  private authenticated = false;

  public async init(): Promise<boolean> {
    const keycloakUrl = 'https://si-legal-auth.duckdns.org';
    const realm = 'swift-check-test';
    const clientId = 'swiftcheck-frontend';

    this.keycloakInstance = new Keycloak({
      url: keycloakUrl,
      realm: realm,
      clientId: clientId
    });

    try {
      this.authenticated = await this.keycloakInstance.init({
        onLoad: 'login-required',
        checkLoginIframe: false,
        enableLogging: false
      });
      return this.authenticated;
    } catch (error) {
      console.error('Erreur lors de l\'initialisation de Keycloak:', error);
      return false;
    }
  }

  public isLoggedIn(): boolean {
    return this.authenticated;
  }

  public async getToken(): Promise<string> {
    if (this.keycloakInstance) {
      try {
        await this.keycloakInstance.updateToken(30);
      } catch (err) {
        console.warn('Impossible de rafraîchir le token, redirection login...', err);
        this.login();
      }
      return this.keycloakInstance.token || '';
    }
    return '';
  }

  public getUsername(): string {
    return (this.keycloakInstance?.tokenParsed as any)?.preferred_username || 'Utilisateur';
  }

  public getFullName(): string {
    const parsed = this.keycloakInstance?.tokenParsed as any;
    if (parsed?.name) return parsed.name;
    if (parsed?.given_name || parsed?.family_name) {
      return `${parsed.given_name || ''} ${parsed.family_name || ''}`.trim();
    }
    return this.getUsername();
  }

  public getEmail(): string {
    return (this.keycloakInstance?.tokenParsed as any)?.email || '';
  }

  public getUserRoles(): string[] {
    const realmRoles: string[] = (this.keycloakInstance?.tokenParsed as any)?.realm_access?.roles || [];
    const resourceAccess = (this.keycloakInstance?.tokenParsed as any)?.resource_access || {};
    const clientRoles: string[] = [];
    Object.values(resourceAccess).forEach((client: any) => {
      if (client?.roles && Array.isArray(client.roles)) {
        clientRoles.push(...client.roles);
      }
    });
    return [...realmRoles, ...clientRoles].map((r: string) => r.toLowerCase());
  }

  public hasRole(role: string): boolean {
    const roles = this.getUserRoles();
    return roles.includes(role.toLowerCase());
  }

  public isAdmin(): boolean {
    return this.hasRole('admin');
  }

  public isTester(): boolean {
    return this.hasRole('tester') || this.isAdmin();
  }

  public login(): void {
    this.keycloakInstance?.login();
  }

  public logout(): void {
    this.keycloakInstance?.logout({
      redirectUri: window.location.origin
    });
  }
}
