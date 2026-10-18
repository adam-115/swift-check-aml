import { Component, OnDestroy, OnInit } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { KeycloakService } from './services/keycloak.service';
import { AlertService } from './services/alert.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CommonModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit, OnDestroy {
  title = 'Swift Check';
  mobileMenuOpen = false;

  /** Nombre d'alertes ouvertes, en badge sur le lien « Alertes ». */
  readonly alertCount$;
  private alertTimer?: ReturnType<typeof setInterval>;

  constructor(public keycloak: KeycloakService, private alertService: AlertService) {
    this.alertCount$ = alertService.openCount$;
  }

  ngOnInit(): void {
    // Relu toutes les 5 minutes : une exécution planifiée peut ouvrir une alerte à tout moment
    this.alertService.refreshCount();
    this.alertTimer = setInterval(() => this.alertService.refreshCount(), 5 * 60 * 1000);
  }

  ngOnDestroy(): void {
    clearInterval(this.alertTimer);
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen = !this.mobileMenuOpen;
  }

  closeMobileMenu(): void {
    this.mobileMenuOpen = false;
  }

  logout(): void {
    this.keycloak.logout();
  }
}